package cx.probe

import android.content.Context
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
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
import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.utils.ExtractorLink
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.net.URLClassLoader
import java.util.jar.JarFile

/**
 * Phase 0 probe: loads extension JARs the way the app loads .cs3 plugins, then for the providers
 * named on the command line tries Home -> search -> details -> video links, like a user would.
 *
 * Usage: probe <dir with extension jars> <report.json> <links.json> [plugin or provider names...]
 */
fun main(args: Array<String>) {
    val jars = File(args[0]).listFiles { f -> f.extension == "jar" }.orEmpty().sortedBy { it.name }
    val wanted = args.drop(3).map { it.lowercase() }.toSet()
    val report = linkedMapOf<String, Any?>()
    val loaded = mutableListOf<Map<String, Any?>>()

    val loader = URLClassLoader(jars.map { it.toURI().toURL() }.toTypedArray(), Plugin::class.java.classLoader)
    for (jar in jars) {
        val classNames = JarFile(jar).use { file ->
            file.entries().asSequence().map { it.name }
                .filter { it.endsWith(".class") && !it.contains('$') }
                .map { it.removeSuffix(".class").replace('/', '.') }.toList()
        }
        for (name in classNames) {
            val cls = runCatching { loader.loadClass(name) }.getOrNull() ?: continue
            if (!cls.isAnnotationPresent(CloudstreamPlugin::class.java) || !BasePlugin::class.java.isAssignableFrom(cls)) continue
            val result = runCatching {
                val plugin = cls.getDeclaredConstructor().newInstance() as BasePlugin
                plugin.filename = jar.nameWithoutExtension
                if (plugin is Plugin) plugin.load(Context()) else plugin.load()
            }
            loaded += mapOf("jar" to jar.name, "plugin" to name, "ok" to result.isSuccess,
                "error" to result.exceptionOrNull()?.let(::describe))
        }
    }
    val providers = APIHolder.allProviders.toList()
    report["plugins"] = loaded
    report["providers"] = providers.map { mapOf("name" to it.name, "plugin" to it.sourcePlugin, "url" to it.mainUrl) }

    val tests = mutableListOf<Map<String, Any?>>()
    val links = mutableListOf<Map<String, Any?>>()
    runBlocking {
        for (api in providers) {
            val plugin = api.sourcePlugin.orEmpty().removePrefix("ext-").lowercase()
            if (wanted.isNotEmpty() && plugin !in wanted && api.name.lowercase() !in wanted) continue
            val (test, link) = exercise(api)
            tests += test
            link?.let { links += it }
        }
    }
    report["tests"] = tests
    val json = jacksonObjectMapper().writerWithDefaultPrettyPrinter()
    File(args[1]).writeText(json.writeValueAsString(report))
    File(args[2]).writeText(json.writeValueAsString(links))
    println("Plugins: ${loaded.count { it["ok"] == true }}/${loaded.size} loaded, providers: ${providers.size}")
    tests.forEach { println(it) }
}

private fun describe(e: Throwable): String = (e.cause ?: e).let { it.javaClass.simpleName + ": " + it.message.orEmpty().take(200) }

/** Home -> search -> details -> links, each step with a time limit. */
private suspend fun exercise(api: MainAPI): Pair<Map<String, Any?>, Map<String, Any?>?> {
    val result = linkedMapOf<String, Any?>("provider" to api.name, "plugin" to api.sourcePlugin)
    suspend fun <T> step(label: String, block: suspend () -> T): T? = try {
        withTimeout(90_000) { block() }.also { result[label] = "ok" }
    } catch (e: Throwable) {
        result[label] = "FAIL " + describe(e)
        null
    }

    val home = step("home") {
        val page = api.mainPage.first()
        api.getMainPage(1, MainPageRequest(page.name, page.data, page.horizontalImages))
    }
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
    val videos = mutableListOf<ExtractorLink>()
    val subtitles = mutableListOf<SubtitleFile>()
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
