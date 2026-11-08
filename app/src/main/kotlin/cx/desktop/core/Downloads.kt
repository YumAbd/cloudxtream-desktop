package cx.desktop.core

import androidx.compose.runtime.mutableStateListOf
import com.fasterxml.jackson.module.kotlin.readValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.net.URI
import java.time.Duration
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/** A video saved to the downloads folder (direct files, or HLS joined into one .ts file). */
data class Download(
    val id: String = "",
    val title: String = "",
    val label: String? = null,
    val poster: String? = null,
    val file: String = "",
    val state: String = "queued", // queued, running, done, failed, cancelled
    val progress: Float = 0f,
    val bytes: Long = 0,
    val error: String? = null,
    val createdMs: Long = 0,
)

object Downloads {
    private val index = File(AppDirs.root, "downloads.json")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = mutableMapOf<String, Job>()

    /** Observed by the Downloads screen. */
    val items = mutableStateListOf<Download>().apply {
        addAll(runCatching { json.readValue<List<Download>>(index) }.getOrElse { emptyList() }
            .map { if (it.state == "running" || it.state == "queued") it.copy(state = "failed", error = "Terhenti") else it })
    }

    private fun save() = synchronized(this) { index.writeAtomically(json.writeValueAsBytes(items.toList())) }

    private fun set(id: String, change: (Download) -> Download) {
        val i = items.indexOfFirst { it.id == id }
        if (i >= 0) items[i] = change(items[i])
    }

    fun start(title: String, label: String?, poster: String?, url: String, referer: String?, headers: Map<String, String>, hls: Boolean) {
        val dir = Prefs.downloadDir.apply { mkdirs() }
        val safe = (title + (label?.let { " - $it" } ?: "")).replace(Regex("[\\\\/:*?\"<>|]"), " ").trim().take(120)
        var file = File(dir, safe + if (hls) ".ts" else ".mp4")
        var n = 2
        while (file.exists()) file = File(dir, "$safe ($n)" + if (hls) ".ts" else ".mp4").also { n++ }
        val id = System.currentTimeMillis().toString(36) + (0..999).random()
        items.add(0, Download(id, title, label, poster, file.absolutePath, "queued", createdMs = System.currentTimeMillis()))
        save()
        val all = buildMap {
            putAll(headers)
            if (keys.none { it.equals("user-agent", true) }) put("User-Agent", cx.desktop.ui.Images.USER_AGENT)
            referer?.takeIf { it.isNotBlank() && keys.none { k -> k.equals("referer", true) } }?.let { put("Referer", it) }
        }
        jobs[id] = scope.launch {
            withContext(Dispatchers.Main) { set(id) { it.copy(state = "running") } }
            val result = runCatching { if (hls) hls(id, url, all, file) else direct(id, url, all, file) }
            withContext(Dispatchers.Main) {
                set(id) { d ->
                    when {
                        d.state == "cancelled" -> d
                        result.isSuccess -> d.copy(state = "done", progress = 1f, bytes = file.length())
                        else -> d.copy(state = "failed", error = Plugins.describe(result.exceptionOrNull()!!))
                    }
                }
                save()
            }
        }
    }

    fun cancel(id: String) {
        jobs.remove(id)?.cancel()
        set(id) { it.copy(state = "cancelled") }
        save()
    }

    fun delete(id: String) {
        jobs.remove(id)?.cancel()
        items.firstOrNull { it.id == id }?.let { File(it.file).delete() }
        items.removeAll { it.id == id }
        save()
    }

    private fun request(url: String, headers: Map<String, String>): okhttp3.Request =
        okhttp3.Request.Builder().url(url).apply { headers.forEach { (k, v) -> runCatching { header(k, v) } } }.build()

    private fun bytes(url: String, headers: Map<String, String>): ByteArray =
        Net.client.newCall(request(url, headers)).execute().use { r ->
            require(r.isSuccessful) { "HTTP ${r.code}" }
            r.body.bytes()
        }

    private suspend fun progress(id: String, fraction: Float, bytes: Long) = withContext(Dispatchers.Main) {
        set(id) { it.copy(progress = fraction, bytes = bytes) }
    }

    private suspend fun direct(id: String, url: String, headers: Map<String, String>, file: File) {
        Net.client.newCall(request(url, headers)).execute().use { response ->
            require(response.isSuccessful) { "HTTP ${response.code}" }
            val total = response.body.contentLength()
            var done = 0L
            var last = 0L
            response.body.byteStream().use { input ->
                file.outputStream().use { out ->
                    val buffer = ByteArray(256 * 1024)
                    while (true) {
                        kotlin.coroutines.coroutineContext.ensureActive()
                        val n = input.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        done += n
                        if (done - last > 2_000_000) {
                            last = done
                            progress(id, if (total > 0) done.toFloat() / total else 0f, done)
                        }
                    }
                }
            }
        }
    }

    private fun text(url: String, headers: Map<String, String>): Pair<String, URI> =
        Net.client.newCall(request(url, headers)).execute().use { r ->
            require(r.isSuccessful) { "HTTP ${r.code}" }
            r.body.string() to URI.create(r.request.url.toString())
        }

    /** HLS: best variant, every segment in order (AES-128 decrypted), joined into one MPEG-TS file. */
    private suspend fun hls(id: String, url: String, headers: Map<String, String>, file: File) {
        var (playlist, base) = text(url, headers)
        if (playlist.contains("#EXT-X-STREAM-INF")) {
            val lines = playlist.lines()
            val best = lines.indices.filter { lines[it].startsWith("#EXT-X-STREAM-INF") }.maxByOrNull { i ->
                Regex("BANDWIDTH=(\\d+)").find(lines[i])?.groupValues?.get(1)?.toLongOrNull() ?: 0L
            } ?: error("no variant")
            val variant = lines.drop(best + 1).first { it.isNotBlank() && !it.startsWith("#") }.trim()
            val next = text(base.resolve(variant).toString(), headers)
            playlist = next.first
            base = next.second
        }
        data class Segment(val uri: String, val key: String?, val iv: ByteArray?, val sequence: Long)
        val segments = mutableListOf<Segment>()
        var sequence = Regex("#EXT-X-MEDIA-SEQUENCE:(\\d+)").find(playlist)?.groupValues?.get(1)?.toLong() ?: 0L
        var key: String? = null
        var iv: ByteArray? = null
        for (line in playlist.lines().map { it.trim() }) {
            when {
                line.startsWith("#EXT-X-KEY") -> {
                    val method = Regex("METHOD=([A-Z0-9-]+)").find(line)?.groupValues?.get(1)
                    require(method == null || method == "NONE" || method == "AES-128") { "unsupported encryption $method" }
                    key = if (method == "AES-128") Regex("URI=\"([^\"]+)\"").find(line)?.groupValues?.get(1)?.let { base.resolve(it).toString() } else null
                    iv = Regex("IV=0[xX]([0-9a-fA-F]+)").find(line)?.groupValues?.get(1)?.let { hex ->
                        hex.padStart(32, '0').chunked(2).map { it.toInt(16).toByte() }.toByteArray()
                    }
                }
                line.isNotEmpty() && !line.startsWith("#") -> segments += Segment(base.resolve(line).toString(), key, iv, sequence++)
            }
        }
        require(segments.isNotEmpty()) { "empty playlist" }
        val keys = mutableMapOf<String, ByteArray>()
        var bytes = 0L
        RandomAccessFile(file, "rw").use { out ->
            out.setLength(0)
            segments.forEachIndexed { i, s ->
                kotlin.coroutines.coroutineContext.ensureActive()
                var data: ByteArray? = null
                for (attempt in 1..3) {
                    data = runCatching { bytes(s.uri, headers) }.getOrNull()
                    if (data != null) break
                }
                var chunk = data ?: error("segment ${i + 1} failed")
                s.key?.let { k ->
                    val keyBytes = keys.getOrPut(k) { bytes(k, headers) }
                    val ivBytes = s.iv ?: ByteArray(16).also { b -> for (j in 0 until 8) b[15 - j] = (s.sequence shr (8 * j)).toByte() }
                    chunk = Cipher.getInstance("AES/CBC/PKCS5Padding").apply {
                        init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"), IvParameterSpec(ivBytes))
                    }.doFinal(chunk)
                }
                out.write(chunk)
                bytes += chunk.size
                if (i % 5 == 0) progress(id, (i + 1).toFloat() / segments.size, bytes)
            }
        }
    }
}
