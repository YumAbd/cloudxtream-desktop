package cx.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lagradost.cloudstream3.AnimeLoadResponse
import com.lagradost.cloudstream3.DubStatus
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.LiveStreamLoadResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MovieLoadResponse
import com.lagradost.cloudstream3.TorrentLoadResponse
import com.lagradost.cloudstream3.TvSeriesLoadResponse
import cx.desktop.core.History
import cx.desktop.core.Plugins
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** A playable item of a title: the movie itself or one episode. */
private data class Playable(val data: String, val label: String?, val season: Int?, val episode: Episode?)

private fun episodeLabel(e: Episode): String =
    listOfNotNull(e.season?.let { "S$it" }, e.episode?.let { "E$it" }).joinToString("") +
        (e.name?.takeIf { it.isNotBlank() }?.let { (if (e.episode != null) " · " else "") + it } ?: "")

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DetailsScreen(navigator: Navigator, screen: Screen.Details) {
    var details by remember { mutableStateOf<LoadResponse?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableStateOf(0) }
    LaunchedEffect(attempt) {
        error = null
        runCatching { withTimeout(45_000) { withContext(Dispatchers.IO) { screen.api.load(screen.url) } } }
            .onSuccess { if (it == null) error = "Tiada maklumat." else details = it }
            .onFailure { error = Plugins.describe(it) }
    }
    val d = details
    if (d == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            if (error == null) CircularProgressIndicator() else Column(horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Gagal membuka “${screen.title}”: $error", color = Palette.muted)
                Button(onClick = { attempt++ }) { Text("Cuba lagi") }
            }
        }
        return
    }

    // Movies have one playable item; series and anime have episodes (anime: per dub/sub version).
    val dubs: List<DubStatus> = (d as? AnimeLoadResponse)?.episodes?.filterValues { it.isNotEmpty() }?.keys?.toList().orEmpty()
    var dub by remember { mutableStateOf(dubs.firstOrNull()) }
    val episodes: List<Episode> = when (d) {
        is TvSeriesLoadResponse -> d.episodes
        is AnimeLoadResponse -> d.episodes[dub].orEmpty()
        else -> emptyList()
    }
    val single: String? = when (d) {
        is MovieLoadResponse -> d.dataUrl
        is LiveStreamLoadResponse -> d.dataUrl
        is TorrentLoadResponse -> null
        else -> null
    }
    val seasons = episodes.map { it.season }.distinct().sortedBy { it ?: 0 }
    var season by remember(dub) { mutableStateOf(seasons.firstOrNull()) }
    val shown = episodes.filter { it.season == season }
    val saved = remember { History.find(screen.api.name, screen.url) }
    val poster = d.posterUrl ?: screen.poster

    fun play(item: Playable, startMs: Long = 0) {
        val following = if (item.episode != null) {
            episodes.dropWhile { it !== item.episode }.drop(1).map { it.data to episodeLabel(it) }
        } else emptyList()
        navigator.open(Screen.Player(PlayRequest(screen.api, screen.url, d.name, poster, item.data, item.label, startMs, following)))
    }

    LazyColumn(contentPadding = PaddingValues(28.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                RemoteImage(poster, d.posterHeaders, Modifier.size(240.dp, 360.dp).clip(RoundedCornerShape(12.dp)))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(d.name, color = Palette.text, fontSize = 30.sp, fontWeight = FontWeight.Bold)
                    val meta = listOfNotNull(
                        d.year?.toString(), d.type.name, d.duration?.let { "$it min" },
                        d.score?.toStringNull(0.1, 10, 1)?.let { "★ $it" }, screen.api.name,
                    )
                    Text(meta.joinToString("  •  "), color = Palette.muted)
                    d.tags?.takeIf { it.isNotEmpty() }?.let { tags ->
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            tags.take(10).forEach { Pill(it, false) {} }
                        }
                    }
                    d.plot?.let { Text(it, color = Palette.text.copy(alpha = 0.85f), maxLines = 8, overflow = TextOverflow.Ellipsis) }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        val resumeData = saved?.takeIf { it.positionMs > 0 }?.let { it.episodeData ?: single }
                        if (saved != null && resumeData != null) {
                            Button(onClick = {
                                play(Playable(resumeData, saved.episodeLabel, null, episodes.firstOrNull { it.data == resumeData }), saved.positionMs)
                            }) { Text("▶  Sambung " + listOfNotNull(saved.episodeLabel, formatTime(saved.positionMs)).joinToString(" · ")) }
                        }
                        if (single != null) {
                            if (resumeData != null) OutlinedButton(onClick = { play(Playable(single, null, null, null)) }) { Text("Dari awal") }
                            else Button(onClick = { play(Playable(single, null, null, null)) }) { Text("▶  Tonton") }
                        } else if (episodes.isNotEmpty() && resumeData == null) {
                            Button(onClick = { episodes.first().let { play(Playable(it.data, episodeLabel(it), it.season, it)) } }) {
                                Text("▶  Tonton episod pertama")
                            }
                        }
                    }
                    if (single == null && episodes.isEmpty()) Text("Tiada video untuk tajuk ini.", color = Palette.muted)
                }
            }
        }
        if (dubs.size > 1) item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                dubs.forEach { s -> Pill(if (s == DubStatus.Dubbed) "Alih suara" else if (s == DubStatus.Subbed) "Sarikata" else s.name, s == dub) { dub = s } }
            }
        }
        if (seasons.size > 1) item {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                seasons.forEach { s -> Pill(s?.let { "Musim $it" } ?: "Lain-lain", s == season) { season = s } }
            }
        }
        items(shown) { e ->
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Palette.surface)
                    .clickable { play(Playable(e.data, episodeLabel(e), e.season, e)) }.padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (e.posterUrl != null) RemoteImage(e.posterUrl, d.posterHeaders, Modifier.size(160.dp, 90.dp).clip(RoundedCornerShape(8.dp)))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(episodeLabel(e).ifBlank { "Episod" }, color = Palette.text, fontWeight = FontWeight.SemiBold)
                    e.description?.let { Text(it, color = Palette.muted, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                }
                if (saved?.episodeData == e.data) Text("Sedang ditonton", color = Palette.primary, fontSize = 12.sp)
                Text("▶", color = Palette.primary, fontSize = 18.sp, modifier = Modifier.width(24.dp))
            }
        }
    }
}
