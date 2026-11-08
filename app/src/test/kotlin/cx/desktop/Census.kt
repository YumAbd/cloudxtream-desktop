package cx.desktop

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import cx.desktop.core.Net
import cx.desktop.core.Plugins
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/**
 * Content census for the admin panel (CI job, never packaged): loads every catalog extension like the
 * app and counts the distinct titles on its Home page (first page of every category). Sites do not
 * publish their full catalog size, so this is what a user sees when opening the extension.
 * Args: output JSON file.
 */
fun main(args: Array<String>) = runBlocking(Dispatchers.IO) {
    val out = File(args.getOrElse(0) { "census.json" })
    Net.apply()
    val summary = Plugins.sync { _, _ -> }
    println("CENSUS extensions loaded: ${summary.loaded}/${summary.total}")
    val providers = Plugins.providers(includeNsfw = true)
    val slots = Semaphore(8)

    data class Count(val provider: String, val plugin: String, val sections: Int, val urls: Set<String>, val error: String?)

    suspend fun count(api: MainAPI): Count {
        val pages = api.mainPage.take(30)
        val urls = java.util.Collections.synchronizedSet(mutableSetOf<String>())
        var error: String? = null
        var ok = 0
        pages.forEach { page ->
            val result = runCatching {
                withTimeoutOrNull(30_000) { api.getMainPage(1, MainPageRequest(page.name, page.data, page.horizontalImages)) }
            }
            result.exceptionOrNull()?.let { error = Plugins.describe(it) }
            result.getOrNull()?.items?.let { lists ->
                ok++
                lists.forEach { list -> list.list.forEach { urls += it.url } }
            }
        }
        if (pages.isNotEmpty() && ok == 0 && error == null) error = "no response"
        return Count(api.name, api.sourcePlugin.orEmpty(), pages.size, urls.toSet(), if (urls.isEmpty()) error ?: "empty" else null)
    }

    val counts = providers.map { api -> async { slots.withPermit { count(api) } } }.awaitAll()
    val now = System.currentTimeMillis()
    val byExtension = Plugins.catalog.map { entry ->
        val own = counts.filter { it.plugin == entry.internalName }
        val titles = own.flatMap { it.urls }.toSet().size
        mapOf(
            "internalName" to entry.internalName,
            "titles" to titles,
            "sections" to own.sumOf { it.sections },
            "providers" to own.map { it.provider },
            "error" to when {
                own.isEmpty() -> "not loaded" + (summary.failures.firstOrNull { it.name == (entry.name ?: entry.internalName) }?.let { ": " + it.reason } ?: "")
                titles == 0 -> own.mapNotNull { it.error }.firstOrNull() ?: "empty"
                else -> null
            },
            "checked_ms" to now,
        )
    }
    out.writeText(jacksonObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(mapOf("checked_ms" to now, "extensions" to byExtension)))
    byExtension.forEach { println("CENSUS ${it["internalName"]}: ${it["titles"]} titles in ${it["sections"]} sections" + (it["error"]?.let { e -> " ($e)" } ?: "")) }
    System.exit(0)
}
