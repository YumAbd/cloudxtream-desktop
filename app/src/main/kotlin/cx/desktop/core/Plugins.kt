package cx.desktop.core

import android.content.Context
import com.fasterxml.jackson.module.kotlin.readValue
import com.googlecode.d2j.dex.Dex2jar
import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.Plugin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URI
import java.net.URLClassLoader
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.MessageDigest
import java.time.Duration
import java.util.zip.ZipFile

/** One extension of the CloudXtream catalog (the same catalog as the Android app). */
data class CatalogEntry(
    val internalName: String = "",
    val name: String? = null,
    val url: String = "",
    val version: Int? = null,
    val tvTypes: List<String>? = null,
    val iconUrl: String? = null,
    val language: String? = null,
    val status: Int? = null,
)

/**
 * Keeps the extensions in step with the catalog and loads them: each .cs3 (Android DEX) is converted
 * to Java bytecode with dex2jar (cached), then its plugin class is loaded in its own class loader,
 * as the Android app does.
 */
object Plugins {
    const val CATALOG_URL = "${Activation.ORIGIN}/catalog/sumber.json"
    private val catalogFile = File(AppDirs.plugins, "catalog.json")
    private val converted = File(AppDirs.plugins, "converted").apply { mkdirs() }
    private val http = HttpClient.newBuilder().proxy(java.net.ProxySelector.getDefault()).connectTimeout(Duration.ofSeconds(15))
        .followRedirects(HttpClient.Redirect.NORMAL).build()

    data class Failure(val name: String, val reason: String)
    data class Summary(val loaded: Int, val total: Int, val failures: List<Failure>, val offline: Boolean)

    @Volatile var catalog: List<CatalogEntry> = emptyList()
        private set
    private val loadedFiles = mutableSetOf<String>()

    /** Downloads what changed in the catalog, then loads every extension not loaded yet. */
    suspend fun sync(progress: (String, Float) -> Unit): Summary = withContext(Dispatchers.IO) {
        progress("Menyemak katalog extension…", 0f)
        val fresh = runCatching { download(CATALOG_URL) }.getOrNull()
            ?.let { bytes -> runCatching { json.readValue<List<CatalogEntry>>(bytes) }.getOrNull()?.let { bytes to it } }
        if (fresh != null) catalogFile.writeAtomically(fresh.first)
        val entries = fresh?.second ?: runCatching { json.readValue<List<CatalogEntry>>(catalogFile) }.getOrElse { emptyList() }
        catalog = entries.filter { it.internalName.matches(Regex("^[A-Za-z0-9_.-]{1,80}$")) && it.url.startsWith("https://") }

        val failures = java.util.Collections.synchronizedList(mutableListOf<Failure>())
        var done = 0
        val slots = Semaphore(6)
        coroutineScope {
            catalog.map { entry ->
                async {
                    slots.withPermit {
                        runCatching { ensureDownloaded(entry) }.onFailure { failures += Failure(entry.name ?: entry.internalName, "muat turun gagal: " + describe(it)) }
                        synchronized(this@Plugins) { done++ }
                        progress("Memuat turun extension ($done/${catalog.size})…", 0.5f * done / catalog.size.coerceAtLeast(1))
                    }
                }
            }.awaitAll()
        }
        // Files of extensions removed from the catalog.
        val keep = catalog.map { "${it.internalName}.cs3" }.toSet()
        AppDirs.plugins.listFiles { f -> f.extension == "cs3" && f.name !in keep }?.forEach { it.delete() }

        catalog.forEachIndexed { i, entry ->
            progress("Memuatkan ${entry.name ?: entry.internalName} (${i + 1}/${catalog.size})…", 0.5f + 0.5f * (i + 1) / catalog.size.coerceAtLeast(1))
            val file = File(AppDirs.plugins, "${entry.internalName}.cs3")
            if (!file.exists() || file.name in loadedFiles) return@forEachIndexed
            runCatching { load(file) }.onSuccess { loadedFiles += file.name }
                .onFailure { failures += Failure(entry.name ?: entry.internalName, describe(it)) }
        }
        Summary(loadedFiles.size, catalog.size, failures.toList(), offline = fresh == null)
    }

    private fun ensureDownloaded(entry: CatalogEntry) {
        val file = File(AppDirs.plugins, "${entry.internalName}.cs3")
        val marker = File(AppDirs.plugins, "${entry.internalName}.url")
        if (file.exists() && marker.exists() && marker.readText() == entry.url) return
        val bytes = download(entry.url)
        java.util.zip.ZipInputStream(bytes.inputStream()).use { zip ->
            require(generateSequence { zip.nextEntry }.any { it.name == "classes.dex" }) { "not an extension" }
        }
        file.writeAtomically(bytes)
        marker.writeText(entry.url)
    }

    private fun download(url: String): ByteArray {
        val request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(60))
            .header("User-Agent", "CloudXtream-Desktop/${AppInfo.version}").GET().build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofByteArray())
        require(response.statusCode() == 200) { "HTTP ${response.statusCode()}" }
        return response.body()
    }

    private fun load(cs3: File) {
        val bytes = cs3.readBytes()
        val hash = MessageDigest.getInstance("SHA-1").digest(bytes).joinToString("") { "%02x".format(it) }.take(16)
        val jar = File(converted, "${cs3.nameWithoutExtension}-$hash.jar")
        val manifest: Map<String, Any?> = ZipFile(cs3).use { zip ->
            if (!jar.exists()) {
                // Older conversions of this extension.
                converted.listFiles { f -> f.name.startsWith(cs3.nameWithoutExtension + "-") }?.forEach { it.delete() }
                val dex = zip.getInputStream(zip.getEntry("classes.dex")).readBytes()
                val tmp = File(converted, "tmp-" + jar.name)
                tmp.delete()
                // Keep names as they are: Kotlin uses names such as "constructor-impl".
                Dex2jar.from(dex).reUseReg(false).topoLogicalSort().skipDebug(false).dontSanitizeNames(true)
                    .optimizeSynchronized().computeFrames(true).to(tmp.toPath())
                check(tmp.renameTo(jar)) { "conversion failed" }
            }
            zip.getEntry("manifest.json")?.let { json.readValue<Map<String, Any?>>(zip.getInputStream(it)) } ?: emptyMap()
        }
        val className = manifest["pluginClassName"] as? String ?: error("manifest.json has no pluginClassName")
        val loader = URLClassLoader(arrayOf(jar.toURI().toURL()), Plugin::class.java.classLoader)
        val plugin = loader.loadClass(className).getDeclaredConstructor().newInstance() as BasePlugin
        plugin.filename = cs3.nameWithoutExtension
        if (plugin is Plugin) plugin.load(Context()) else plugin.load()
    }

    fun describe(e: Throwable): String {
        var root = e
        while (root.cause != null && root.cause !== root) root = root.cause!!
        return root.javaClass.simpleName + (root.message?.let { ": " + it.take(120) } ?: "")
    }

    /** Every provider registered by the loaded extensions. */
    fun providers(includeNsfw: Boolean): List<MainAPI> = APIHolder.allProviders.toList()
        .filter { includeNsfw || TvType.NSFW !in it.supportedTypes }
        .sortedBy { it.name.lowercase() }

    fun provider(name: String?): MainAPI? = name?.let { n -> APIHolder.allProviders.toList().firstOrNull { it.name == n } }
}
