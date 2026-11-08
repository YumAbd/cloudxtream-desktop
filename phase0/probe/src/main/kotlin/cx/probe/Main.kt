package cx.probe

import android.content.Context
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.googlecode.d2j.dex.Dex2jar
import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.AnimeLoadResponse
import com.lagradost.cloudstream3.LiveStreamLoadResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.MovieLoadResponse
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvSeriesLoadResponse
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.utils.ExtractorLink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeout
import java.io.File
import java.net.URLClassLoader
import java.util.jar.JarFile
import java.util.zip.ZipFile

/**
 * Phase 0 probe. Loads extensions the way the Android app does, then tries Home -> search -> details
 * -> video links like a user would.
 *
 * The directory may hold:
 *  - .cs3 files exactly as published in the catalog (Android DEX): converted to Java bytecode with
 *    dex2jar, then the class named in manifest.json is loaded. This is how the desktop app would work.
 *  - .jar files compiled from the extension sources: @CloudstreamPlugin classes are loaded.
 *
 * Usage: probe <dir> <report.json> <links.json> [names... | all]
 * "all" exercises every provider except NSFW ones; names match the plugin file or the provider name.
 */
fun main(args: Array<String>) {
    val files = File(args[0]).listFiles { f -> f.extension == "jar" || f.extension == "cs3" }.orEmpty().sortedBy { it.name }
    val wanted = args.drop(3).map { it.lowercase() }.toSet()
    val cache = File(args[0], "converted").apply { mkdirs() }
    val report = linkedMapOf<String, Any?>()
    val loaded = mutableListOf<Map<String, Any?>>()

    for (file in files) {
        val started = System.currentTimeMillis()
        val (jar, classNames) = try {
            if (file.extension == "cs3") convert(file, cache) else file to pluginClasses(file)
        } catch (e: Throwable) {
            loaded += mapOf("file" to file.name, "ok" to false, "stage" to "convert", "error" to describe(e))
            continue
        }
        val converted = System.currentTimeMillis() - started
        // One class loader per plugin, like the app: plugins from one repository share package names.
        val loader = URLClassLoader(arrayOf(jar.toURI().toURL()), Plugin::class.java.classLoader)
        for (name in classNames) {
            val before = APIHolder.allProviders.size
            val result = runCatching {
                val plugin = loader.loadClass(name).getDeclaredConstructor().newInstance() as BasePlugin
                plugin.filename = file.nameWithoutExtension
                if (plugin is Plugin) plugin.load(Context()) else plugin.load()
            }
            loaded += mapOf(
                "file" to file.name, "plugin" to name, "ok" to result.isSuccess, "stage" to "load",
                "providers" to APIHolder.allProviders.size - before, "convert_ms" to converted,
                "error" to result.exceptionOrNull()?.let(::describe),
            )
        }
    }
    val providers = APIHolder.allProviders.toList()
    report["plugins"] = loaded
    report["providers"] = providers.map {
        mapOf("name" to it.name, "plugin" to it.sourcePlugin, "url" to it.mainUrl, "types" to it.supportedTypes.map(TvType::name))
    }

    val chosen = providers.filter { api ->
        val plugin = api.sourcePlugin.orEmpty().lowercase()
        when {
            "all" in wanted -> TvType.NSFW !in api.supportedTypes
            else -> plugin in wanted || api.name.lowercase() in wanted
        }
    }
    val tests: List<Pair<Map<String, Any?>, Map<String, Any?>?>> = runBlocking(Dispatchers.IO) {
        val slots = Semaphore(6)
        chosen.map { api -> async { slots.withPermit { exercise(api) } } }.awaitAll()
    }
    report["tests"] = tests.map { it.first }
    val json = jacksonObjectMapper().writerWithDefaultPrettyPrinter()
    File(args[1]).writeText(json.writeValueAsString(report))
    File(args[2]).writeText(json.writeValueAsString(tests.mapNotNull { it.second }))

    println("Plugins: ${loaded.count { it["ok"] == true }}/${loaded.size} loaded, providers: ${providers.size}")
    loaded.filter { it["ok"] != true }.forEach { println("  NOT LOADED ${it["file"]}: ${it["error"]}") }
    println("Provider tests (${tests.size}):")
    tests.forEach { println("  " + it.first) }
    System.exit(0) // extensions may leave non-daemon threads (OkHttp pools, timers) running
}

/** .cs3 -> Java bytecode jar (cached by file size and time), plus the plugin class from manifest.json. */
private fun convert(cs3: File, cache: File): Pair<File, List<String>> {
    val out = File(cache, "${cs3.nameWithoutExtension}-${cs3.length()}-${cs3.lastModified()}.jar")
    val manifest: Map<String, Any?> = ZipFile(cs3).use { zip ->
        if (!out.exists()) {
            val dex = zip.getInputStream(zip.getEntry("classes.dex") ?: error("no classes.dex")).readBytes()
            val tmp = File(cache, out.name + ".tmp").apply { delete() }
            Dex2jar.from(dex).reUseReg(false).topoLogicalSort().skipDebug(false)
                .optimizeSynchronized().computeFrames(true).to(tmp.toPath())
            tmp.renameTo(out)
        }
        zip.getEntry("manifest.json")?.let { jacksonObjectMapper().readValue(zip.getInputStream(it)) } ?: emptyMap()
    }
    val main = manifest["pluginClassName"] as? String
    return out to (main?.let(::listOf) ?: pluginClasses(out))
}

private fun pluginClasses(jar: File): List<String> {
    val names = JarFile(jar).use { file ->
        file.entries().asSequence().map { it.name }
            .filter { it.endsWith(".class") && !it.contains('$') }
            .map { it.removeSuffix(".class").replace('/', '.') }.toList()
    }
    val loader = URLClassLoader(arrayOf(jar.toURI().toURL()), Plugin::class.java.classLoader)
    return names.filter { name ->
        runCatching {
            val cls = loader.loadClass(name)
            cls.isAnnotationPresent(CloudstreamPlugin::class.java) && BasePlugin::class.java.isAssignableFrom(cls)
        }.getOrDefault(false)
    }
}

private fun describe(e: Throwable): String {
    var root = e
    while (root.cause != null && root.cause !== root) root = root.cause!!
    val where = root.stackTrace.firstOrNull { !it.className.startsWith("java.") && !it.className.startsWith("kotlin") }
    return root.javaClass.simpleName + ": " + root.message.orEmpty().take(200) + (where?.let { " @ ${it.className}.${it.methodName}" } ?: "")
}

/** Home -> search -> details -> links, each step with a time limit. */
private suspend fun exercise(api: MainAPI): Pair<Map<String, Any?>, Map<String, Any?>?> {
    val result = linkedMapOf<String, Any?>("provider" to api.name, "plugin" to api.sourcePlugin)
    suspend fun <T> step(label: String, block: suspend () -> T): T? {
        val started = System.currentTimeMillis()
        return try {
            withTimeout(60_000) { block() }.also { result[label] = "ok ${System.currentTimeMillis() - started}ms" }
        } catch (e: Throwable) {
            result[label] = "FAIL " + describe(e)
            null
        }
    }

    val home = if (api.hasMainPage && api.mainPage.isNotEmpty()) step("home") {
        val page = api.mainPage.first()
        api.getMainPage(1, MainPageRequest(page.name, page.data, page.horizontalImages))
    } else null
    val homeItems: List<SearchResponse> = home?.items?.flatMap { it.list }.orEmpty()
    result["home_items"] = homeItems.size
    val query = homeItems.firstOrNull()?.name?.split(' ')?.firstOrNull { it.length > 3 } ?: "love"
    val found = step("search") { api.search(query) }.orEmpty()
    result["search_query"] = query
    result["search_results"] = found.size
    val target = homeItems.firstOrNull() ?: found.firstOrNull() ?: return result to null
    result["title"] = target.name

    val details: LoadResponse = step("load") { api.load(target.url) } ?: return result to null
    val data = when (details) {
        is MovieLoadResponse -> details.dataUrl
        is TvSeriesLoadResponse -> details.episodes.firstOrNull()?.data
        is AnimeLoadResponse -> details.episodes.values.flatten().firstOrNull()?.data
        is LiveStreamLoadResponse -> details.dataUrl
        else -> null
    }
    result["type"] = details.type.name
    if (data == null) {
        result["links"] = "FAIL no playable entry"
        return result to null
    }
    val videos = java.util.Collections.synchronizedList(mutableListOf<ExtractorLink>())
    val subtitles = java.util.Collections.synchronizedList(mutableListOf<SubtitleFile>())
    step("links") { api.loadLinks(data, false, { subtitles += it }, { videos += it }) }
    result["video_links"] = videos.size
    result["subtitles"] = subtitles.size
    result["link_types"] = videos.map { it.type.name }.distinct()
    val first = videos.firstOrNull() ?: return result to null
    return result to mapOf(
        "provider" to api.name, "title" to target.name, "url" to first.url, "referer" to first.referer,
        "headers" to first.headers, "type" to first.type.name, "quality" to first.quality,
    )
}
