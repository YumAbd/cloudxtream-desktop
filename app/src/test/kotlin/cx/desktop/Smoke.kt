package cx.desktop

import com.lagradost.cloudstream3.AnimeLoadResponse
import com.lagradost.cloudstream3.LiveStreamLoadResponse
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.MovieLoadResponse
import com.lagradost.cloudstream3.TvSeriesLoadResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import cx.desktop.core.Plugins
import cx.desktop.player.VideoPlayer
import cx.desktop.player.Vlc
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/**
 * CI smoke test (test sources, never packaged): loads the catalog extensions like the app, then for a
 * few providers goes Home -> details -> links and plays the first link with the bundled libVLC,
 * checking that frames are decoded. Writes the first decoded frame to the given PNG.
 */
fun main(args: Array<String>) = runBlocking {
    val out = File(args.getOrElse(0) { "smoke-frame.png" })
    val summary = Plugins.sync { _, _ -> }
    println("SMOKE extensions: ${summary.loaded}/${summary.total} loaded, offline=${summary.offline}, failures=${summary.failures}")
    println("SMOKE libVLC available: ${Vlc.available}")
    var ok = false
    for (name in listOf("Pencurimovie", "Dutamovie", "Indomax", "MovieBox", "Sflix")) {
        val api = Plugins.providers(false).firstOrNull { it.name == name } ?: continue
        val links = mutableListOf<ExtractorLink>()
        val title = withTimeoutOrNull(90_000) {
            val page = api.mainPage.first()
            val item = api.getMainPage(1, MainPageRequest(page.name, page.data, page.horizontalImages))?.items?.flatMap { it.list }?.firstOrNull()
                ?: return@withTimeoutOrNull null
            val details = api.load(item.url) ?: return@withTimeoutOrNull null
            val data = when (details) {
                is MovieLoadResponse -> details.dataUrl
                is TvSeriesLoadResponse -> details.episodes.firstOrNull()?.data
                is AnimeLoadResponse -> details.episodes.values.flatten().firstOrNull()?.data
                is LiveStreamLoadResponse -> details.dataUrl
                else -> null
            } ?: return@withTimeoutOrNull null
            api.loadLinks(data, false, {}, { synchronized(links) { links += it } })
            item.name
        }
        val playable = synchronized(links) { links.filter { it.type == ExtractorLinkType.M3U8 || it.type == ExtractorLinkType.VIDEO }.sortedByDescending { it.quality } }
        println("SMOKE $name: title=$title, links=${playable.size}")
        if (!Vlc.available) break
        for (link in playable.take(3)) {
            val player = VideoPlayer()
            player.play(link.url, link.referer, link.headers, 0)
            var waited = 0
            while (waited < 40_000 && player.frameCount < 50 && !player.failed) { delay(500); waited += 500 }
            println("SMOKE $name play ${link.type} ${link.quality}p: frames=${player.frameCount}, size=${player.frame?.width}x${player.frame?.height}, time=${player.timeMs}ms, failed=${player.failed}")
            if (player.frameCount >= 50) {
                player.snapshotPng()?.let { out.writeBytes(it) }
                ok = true
            }
            player.release()
            if (ok) break
        }
        if (ok) break
    }
    println(if (ok) "SMOKE RESULT: video decoded and rendered" else "SMOKE RESULT: no video played")
    Vlc.release()
    System.exit(if (ok) 0 else 1)
}
