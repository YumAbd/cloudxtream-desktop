package cx.desktop.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.MessageDigest
import java.time.Duration

/** New versions of the Windows app, from the GitHub releases of this project. */
object Updates {
    private const val LATEST = "https://api.github.com/repos/YumAbd/cloudxtream-desktop/releases/latest"
    private val http = HttpClient.newBuilder().proxy(java.net.ProxySelector.getDefault())
        .connectTimeout(Duration.ofSeconds(15)).followRedirects(HttpClient.Redirect.NORMAL).build()

    data class Release(val version: String, val installerUrl: String, val sumsUrl: String?, val size: Long)

    private fun code(version: String): Long? = Regex("^v?(\\d+)\\.(\\d+)\\.(\\d+)").find(version)?.destructured
        ?.let { (a, b, c) -> a.toLong() * 1_000_000 + b.toLong() * 1_000 + c.toLong() }

    /** The latest release when it is newer than this app (never for development builds). */
    suspend fun check(): Release? = withContext(Dispatchers.IO) {
        val current = code(AppInfo.version) ?: return@withContext null
        runCatching {
            val request = HttpRequest.newBuilder(URI.create(LATEST)).timeout(Duration.ofSeconds(20))
                .header("Accept", "application/vnd.github+json").header("User-Agent", "CloudXtream-Desktop").GET().build()
            val body = JSONObject(http.send(request, HttpResponse.BodyHandlers.ofString()).body())
            val tag = body.getString("tag_name")
            if ((code(tag) ?: 0) <= current) return@runCatching null
            val assets = body.getJSONArray("assets")
            val all = (0 until assets.length()).map { assets.getJSONObject(it) }
            val installer = all.firstOrNull { it.getString("name").endsWith("-setup.exe") } ?: return@runCatching null
            Release(tag.removePrefix("v"), installer.getString("browser_download_url"),
                all.firstOrNull { it.getString("name") == "SHA256SUMS.txt" }?.getString("browser_download_url"),
                installer.optLong("size"))
        }.getOrNull()
    }

    /** Downloads the installer (checked against SHA256SUMS.txt when published) and returns it. */
    suspend fun download(release: Release, progress: (Float) -> Unit): File = withContext(Dispatchers.IO) {
        val target = File(System.getProperty("java.io.tmpdir"), "CloudXtream-${release.version}-setup.exe")
        val response = http.send(HttpRequest.newBuilder(URI.create(release.installerUrl)).GET().build(), HttpResponse.BodyHandlers.ofInputStream())
        require(response.statusCode() == 200) { "HTTP ${response.statusCode()}" }
        val total = response.headers().firstValueAsLong("Content-Length").orElse(release.size).coerceAtLeast(1)
        val digest = MessageDigest.getInstance("SHA-256")
        response.body().use { input ->
            target.outputStream().use { output ->
                val buffer = ByteArray(64 * 1024)
                var done = 0L
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    output.write(buffer, 0, n)
                    digest.update(buffer, 0, n)
                    done += n
                    progress((done.toFloat() / total).coerceIn(0f, 1f))
                }
            }
        }
        release.sumsUrl?.let { url ->
            val sums = http.send(HttpRequest.newBuilder(URI.create(url)).GET().build(), HttpResponse.BodyHandlers.ofString()).body()
            val expected = sums.lines().firstOrNull { it.trim().endsWith(target.name) || it.contains("-setup.exe") }?.trim()?.split(Regex("\\s+"))?.firstOrNull()
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            if (expected != null && !expected.equals(actual, ignoreCase = true)) {
                target.delete()
                error("checksum mismatch")
            }
        }
        target
    }

    /** Starts the installer; it upgrades the installed app in place. The caller then exits. */
    fun install(installer: File) {
        ProcessBuilder(installer.absolutePath).start()
    }
}
