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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.height
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
import kotlinx.coroutines.launch

/** A playable item of a title: the movie itself or one episode. */
private data class Playable(val data: String, val label: String?, val season: Int?, val episode: Episode?)

private fun episodeLabel(e: Episode): String =
    listOfNotNull(e.season?.let { "S$it" }, e.episode?.let { "E$it" }).joinToString("") +
        (e.name?.takeIf { it.isNotBlank() }?.let { (if (e.episode != null) " · " else "") + it } ?: "")

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DetailsScreen(navigator: Navigator, screen: Screen.Details) {
    // Kept for the session: coming back from the player shows the page at once.
    var details by remember { mutableStateOf<LoadResponse?>(HomeCache.details[screen.url]) }
    var error by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableStateOf(0) }
    LaunchedEffect(attempt) {
        if (details != null && attempt == 0) return@LaunchedEffect
        error = null
        runCatching { withTimeout(45_000) { withContext(Dispatchers.IO) { screen.api.load(screen.url) } } }
            .onSuccess { if (it == null) error = tr("Tiada maklumat.", "No information.") else { details = it; HomeCache.details[screen.url] = it } }
            .onFailure { error = Plugins.describe(it) }
    }
    val d = details
    if (d == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            if (error == null) CircularProgressIndicator() else Column(horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(tr("Gagal membuka", "Could not open") + " “${screen.title}”: $error", color = Palette.muted)
                Button(onClick = { attempt++ }) { Text(tr("Cuba lagi", "Try again")) }
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

    var downloadFor by remember { mutableStateOf<Playable?>(null) }
    downloadFor?.let { item ->
        DownloadPicker(screen.api, item.data, d.name, item.label, poster) { downloadFor = null }
    }

    fun play(item: Playable, startMs: Long = 0) {
        val following = if (item.episode != null) {
            episodes.dropWhile { it !== item.episode }.drop(1).map { it.data to episodeLabel(it) }
        } else emptyList()
        navigator.open(Screen.Player(PlayRequest(screen.api, screen.url, d.name, poster, item.data, item.label, startMs, following)))
    }

    LazyColumn(contentPadding = PaddingValues(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        item {
            Box(Modifier.fillMaxWidth().height(520.dp)) {
                RemoteImage(d.backgroundPosterUrl ?: poster, d.posterHeaders, Modifier.fillMaxSize().blur(if (d.backgroundPosterUrl == null) 18.dp else 0.dp))
                Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to Palette.background, 0.6f to Palette.background.copy(alpha = 0.7f), 1f to Palette.background.copy(alpha = 0.15f))))
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.5f to Color.Transparent, 1f to Palette.background)))
                Row(Modifier.align(Alignment.BottomStart).padding(32.dp), horizontalArrangement = Arrangement.spacedBy(32.dp)) {
                    RemoteImage(poster, d.posterHeaders, Modifier.size(250.dp, 375.dp).clip(RoundedCornerShape(14.dp)))
                    Column(Modifier.width(720.dp).align(Alignment.Bottom), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        Text(screen.api.name.uppercase(), color = Palette.accent, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
                        Text(d.name, color = Color.White, fontSize = 36.sp, fontWeight = FontWeight.ExtraBold, lineHeight = 40.sp)
                        val meta = listOfNotNull(
                            d.year?.toString(), d.score?.toStringNull(0.1, 10, 1)?.let { "★ $it" }, typeLabel(d.type.name),
                            d.duration?.let { "$it min" }, d.contentRating,
                            if (episodes.isNotEmpty()) tr("${episodes.size} episod", "${episodes.size} episodes") else null,
                        )
                        Text(meta.joinToString("   •   "), color = Color.White.copy(alpha = 0.85f))
                        d.tags?.takeIf { it.isNotEmpty() }?.let { tags ->
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                tags.take(8).forEach { Text(it, color = Color.White.copy(alpha = 0.9f), fontSize = 12.sp,
                                    modifier = Modifier.clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = 0.12f)).padding(horizontal = 12.dp, vertical = 5.dp)) }
                            }
                        }
                        d.plot?.let { Text(it, color = Color.White.copy(alpha = 0.8f), maxLines = 5, overflow = TextOverflow.Ellipsis, lineHeight = 20.sp) }
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            val resumeData = saved?.takeIf { it.positionMs > 0 }?.let { it.episodeData ?: single }
                            val white = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black)
                            val glass = ButtonDefaults.buttonColors(containerColor = Color.White.copy(alpha = 0.18f), contentColor = Color.White)
                            if (saved != null && resumeData != null) {
                                Button(colors = white, onClick = {
                                    play(Playable(resumeData, saved.episodeLabel, null, episodes.firstOrNull { it.data == resumeData }), saved.positionMs)
                                }) { Text("▶  " + tr("Sambung", "Resume") + " " + listOfNotNull(saved.episodeLabel, formatTime(saved.positionMs)).joinToString(" · "), fontWeight = FontWeight.Bold) }
                            }
                            if (single != null) {
                                if (resumeData != null) Button(colors = glass, onClick = { play(Playable(single, null, null, null)) }) { Text(tr("Dari awal", "From the start")) }
                                else Button(colors = white, onClick = { play(Playable(single, null, null, null)) }) { Text("▶  " + tr("Tonton", "Play"), fontWeight = FontWeight.Bold) }
                                Button(colors = glass, onClick = { downloadFor = Playable(single, null, null, null) }) { Text("⬇  " + tr("Muat turun", "Download")) }
                            } else if (episodes.isNotEmpty() && resumeData == null) {
                                Button(colors = white, onClick = { episodes.first().let { play(Playable(it.data, episodeLabel(it), it.season, it)) } }) {
                                    Text("▶  " + tr("Tonton episod 1", "Play episode 1"), fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                        LibraryActions(screen, d.name, poster, d.type.name)
                        if (single == null && episodes.isEmpty()) Text(tr("Tiada video untuk tajuk ini.", "No video for this title."), color = Palette.muted)
                    }
                }
            }
        }
        if (episodes.isNotEmpty()) item {
            Row(Modifier.padding(horizontal = 32.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(tr("Episod", "Episodes"), color = Palette.text, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                if (dubs.size > 1) dubs.forEach { s -> Pill(if (s == DubStatus.Dubbed) tr("Alih suara", "Dubbed") else if (s == DubStatus.Subbed) tr("Sarikata", "Subbed") else s.name, s == dub) { dub = s } }
            }
        }
        if (seasons.size > 1) item {
            FlowRow(Modifier.padding(horizontal = 32.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                seasons.forEach { s -> Pill(s?.let { tr("Musim $it", "Season $it") } ?: tr("Lain-lain", "Other"), s == season) { season = s } }
            }
        }
        items(shown) { e ->
            val hover = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
            val hovered by hover.collectIsHoveredAsState()
            Row(
                Modifier.padding(horizontal = 32.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp))
                    .background(if (hovered) Palette.surfaceHigh else Palette.surface).hoverable(hover)
                    .handCursor().clickable { play(Playable(e.data, episodeLabel(e), e.season, e)) }.padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(178.dp, 100.dp).clip(RoundedCornerShape(8.dp)).background(Palette.surfaceHigh), contentAlignment = Alignment.Center) {
                    if (e.posterUrl != null) RemoteImage(e.posterUrl, d.posterHeaders, Modifier.fillMaxSize())
                    else Text((e.episode ?: "▶").toString(), color = Palette.muted, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                    if (hovered) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.4f)), contentAlignment = Alignment.Center) {
                        Text("▶", color = Color.White, fontSize = 28.sp)
                    }
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(episodeLabel(e).ifBlank { tr("Episod", "Episode") }, color = Palette.text, fontWeight = FontWeight.SemiBold)
                    e.description?.let { Text(it, color = Palette.muted, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                    if (saved?.episodeData == e.data) Text(tr("Sedang ditonton · ", "Watching · ") + formatTime(saved.positionMs), color = Palette.primary, fontSize = 12.sp)
                }
                TextButton(onClick = { downloadFor = Playable(e.data, episodeLabel(e), e.season, e) }) { Text("⬇", fontSize = 18.sp) }
            }
        }
        d.recommendations?.takeIf { it.isNotEmpty() }?.let { recs ->
            item { CardRow(tr("Anda mungkin suka", "More like this"), recs.map { it.toCard(navigator, screen.api) }) }
        }
    }
}

@Composable
private fun LibraryActions(screen: Screen.Details, title: String, poster: String?, type: String) {
    val base = cx.desktop.core.LibraryEntry(screen.api.name, screen.url, title, poster, type)
    var entry by remember { mutableStateOf(cx.desktop.core.Library.get(screen.api.name, screen.url) ?: base) }
    var open by remember { mutableStateOf(false) }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box {
            Pill((entry.status?.let { statusLabel(it) } ?: tr("+ Tambah ke senarai", "+ Add to list")) + "  ▾", entry.status != null) { open = true }
            androidx.compose.material3.DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                cx.desktop.core.WatchStatus.entries.forEach { s ->
                    androidx.compose.material3.DropdownMenuItem(modifier = Modifier.handCursor(), text = { Text(statusLabel(s)) }, onClick = {
                        open = false
                        entry = cx.desktop.core.Library.update(base) { it.copy(status = s, title = title, poster = poster) }
                    })
                }
                if (entry.status != null) androidx.compose.material3.DropdownMenuItem(modifier = Modifier.handCursor(), text = { Text(tr("Buang daripada senarai", "Remove from list")) }, onClick = {
                    open = false
                    entry = cx.desktop.core.Library.update(base) { it.copy(status = null) }
                })
            }
        }
        Pill(if (entry.favourite) "♥ " + tr("Kegemaran", "Favourite") else "♡ " + tr("Kegemaran", "Favourite"), entry.favourite) {
            entry = cx.desktop.core.Library.update(base) { it.copy(favourite = !it.favourite, title = title, poster = poster) }
        }
        Pill(if (entry.subscribed) "🔔 " + tr("Dilanggan", "Subscribed") else "🔔 " + tr("Langgan", "Subscribe"), entry.subscribed) {
            entry = cx.desktop.core.Library.update(base) { it.copy(subscribed = !it.subscribed, title = title, poster = poster) }
        }
    }
}

/** Loads the links of one movie/episode and lets the user pick the source to download. */
@Composable
private fun DownloadPicker(api: com.lagradost.cloudstream3.MainAPI, data: String, title: String, label: String?, poster: String?, onClose: () -> Unit) {
    val links = remember { androidx.compose.runtime.mutableStateListOf<com.lagradost.cloudstream3.utils.ExtractorLink>() }
    var loading by remember { mutableStateOf(true) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    LaunchedEffect(data) {
        kotlinx.coroutines.withTimeoutOrNull(60_000) {
            runCatching {
                withContext(Dispatchers.IO) {
                    api.loadLinks(data, false, {}, { l -> scope.launch(Dispatchers.Main) { if (links.none { it.url == l.url }) links.add(l) } })
                }
            }
        }
        loading = false
    }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onClose,
        title = { Text(tr("Muat turun: ", "Download: ") + title + (label?.let { " · $it" } ?: "")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (loading) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text(tr("Mencari sumber…", "Finding sources…") + " (${links.size})", color = Palette.muted)
                }
                val usable = links.filter { it.type == com.lagradost.cloudstream3.utils.ExtractorLinkType.VIDEO || it.type == com.lagradost.cloudstream3.utils.ExtractorLinkType.M3U8 }
                    .sortedByDescending { it.quality }
                if (!loading && usable.isEmpty()) Text(tr("Tiada sumber yang boleh dimuat turun.", "No downloadable source."), color = Palette.muted)
                usable.take(12).forEach { link ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Palette.surfaceHigh).handCursor().clickable {
                            cx.desktop.core.Downloads.start(title, label, poster, link.url, link.referer, link.headers,
                                hls = link.type == com.lagradost.cloudstream3.utils.ExtractorLinkType.M3U8)
                            onClose()
                        }.padding(10.dp)
                    ) {
                        Text((link.name.ifBlank { link.source }) + (if (link.quality > 0) " · ${link.quality}p" else "") + " (${link.type.name})",
                            color = Palette.text, fontSize = 13.sp)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text(tr("Tutup", "Close")) } },
    )
}
