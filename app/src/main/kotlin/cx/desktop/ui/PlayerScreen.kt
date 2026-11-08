package cx.desktop.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import cx.desktop.AppWindow
import cx.desktop.core.Activation
import cx.desktop.core.AppInfo
import cx.desktop.core.History
import cx.desktop.core.Plugins
import cx.desktop.core.WatchEntry
import cx.desktop.player.Vlc
import cx.desktop.player.VideoPlayer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import androidx.compose.runtime.rememberCoroutineScope
import kotlin.math.min
import kotlin.math.roundToInt

private fun ExtractorLink.label(): String =
    listOfNotNull(name.takeIf { it.isNotBlank() } ?: source, quality.takeIf { it > 0 }?.let { "${it}p" }).joinToString(" · ")

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun PlayerScreen(request: PlayRequest, onBack: () -> Unit, onPlayNext: (PlayRequest) -> Unit) {
    if (!Vlc.available) {
        Box(Modifier.fillMaxSize().background(Palette.background), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(tr("VLC tidak dijumpai, jadi video tidak dapat dimainkan. Pasang semula CloudXtream.", "VLC was not found, so video cannot play. Reinstall CloudXtream."), color = Palette.text)
                Button(onClick = onBack) { Text(tr("Kembali", "Back")) }
            }
        }
        return
    }
    val player = remember { VideoPlayer() }
    val links = remember { mutableStateListOf<ExtractorLink>() }
    val subtitles = remember { mutableStateListOf<SubtitleFile>() }
    var loadingLinks by remember { mutableStateOf(true) }
    var current by remember { mutableStateOf<ExtractorLink?>(null) }
    var subtitle by remember { mutableStateOf<SubtitleFile?>(null) }
    var controlsVisible by remember { mutableStateOf(true) }
    var lastMove by remember { mutableStateOf(System.currentTimeMillis()) }
    var panel by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var localPlaying by remember { mutableStateOf(false) }
    var linkError by remember { mutableStateOf<String?>(null) }
    val tried = remember { mutableSetOf<String>() }
    val focus = remember { FocusRequester() }
    val seekMs = cx.desktop.core.Prefs.seekSeconds * 1000L
    val scope = rememberCoroutineScope()

    fun start(link: ExtractorLink, at: Long) {
        if (link.type == ExtractorLinkType.M3U8 || link.type == ExtractorLinkType.VIDEO) cx.desktop.core.Library.get(request.api?.name ?: "", request.url)
            ?: request.api?.let { api ->
                cx.desktop.core.Library.update(cx.desktop.core.LibraryEntry(api.name, request.url, request.title, request.poster)) {
                    it.copy(status = it.status ?: cx.desktop.core.WatchStatus.WATCHING)
                }
            }
        current = link
        tried += link.url
        player.play(link.url, link.referer, link.headers, at)
        subtitle?.let { player.addSubtitle(it.url, it.headers) }
    }

    fun save() {
        val api = request.api ?: return
        if (player.timeMs < 5_000) return
        History.save(WatchEntry(
            provider = api.name, url = request.url, title = request.title, poster = request.poster,
            episodeData = request.episodeData, episodeLabel = request.episodeLabel,
            positionMs = player.timeMs, durationMs = player.lengthMs,
        ))
    }

    DisposableEffect(Unit) {
        onDispose {
            save()
            player.release()
        }
    }

    // Collect the links from the provider; play the best one as soon as it arrives.
    LaunchedEffect(Unit) {
        if (request.localFile != null) {
            val f = java.io.File(request.localFile)
            player.play(f.toURI().toString(), null, emptyMap(), request.startMs)
            current = null
            loadingLinks = false
            localPlaying = true
            return@LaunchedEffect
        }
        val api = request.api ?: return@LaunchedEffect
        withTimeoutOrNull(60_000) {
            runCatching {
                withContext(Dispatchers.IO) {
                    api.loadLinks(request.episodeData, false,
                        { s -> scope.launch { if (subtitles.none { it.url == s.url }) subtitles.add(s) } },
                        { l -> scope.launch { if (l.type != ExtractorLinkType.TORRENT && l.type != ExtractorLinkType.MAGNET && links.none { it.url == l.url }) links.add(l) } })
                }
            }.onFailure { linkError = Plugins.describe(it) }
        } ?: run { linkError = tr("tamat masa", "timed out") }
        loadingLinks = false
    }
    LaunchedEffect(links.size) {
        if (current == null && links.isNotEmpty()) {
            delay(1500) // let a few sources arrive, then pick the best quality
            if (current == null) {
                val max = cx.desktop.core.Prefs.preferredQuality
                val pick = if (max == 0) links.maxBy { it.quality }
                    else links.filter { it.quality in 1..max }.maxByOrNull { it.quality } ?: links.maxBy { it.quality }
                start(pick, request.startMs)
            }
        }
    }
    // Default subtitle: Malay, then Indonesian, then English (like the Android app's auto subtitles).
    LaunchedEffect(subtitles.size) {
        if (subtitle == null && subtitles.isNotEmpty()) {
            val order = when (cx.desktop.core.Prefs.subtitleLanguage) {
                "none" -> emptyList()
                "id" -> listOf("indonesia", "id", "malay", "melayu", "ms", "english", "en")
                "en" -> listOf("english", "en", "malay", "melayu", "ms", "indonesia", "id")
                else -> listOf("malay", "melayu", "ms", "indonesia", "id", "english", "en")
            }
            val pick = order.firstNotNullOfOrNull { lang ->
                subtitles.firstOrNull { it.lang.lowercase().startsWith(lang) }
            }
            if (pick != null) {
                subtitle = pick
                if (current != null) player.addSubtitle(pick.url, pick.headers)
            }
        }
    }
    // A source that fails, or shows nothing for 25 seconds, is replaced by the next untried one.
    fun tryNext() {
        val next = links.sortedByDescending { it.quality }.firstOrNull { it.url !in tried }
        if (next != null) {
            notice = tr("Sumber gagal, mencuba ", "Source failed, trying ") + next.label() + "…"
            start(next, player.timeMs)
        } else {
            notice = tr("Semua sumber gagal dimainkan. Cuba muat turun, atau pilih sumber lain.", "No source could be played. Try downloading, or pick another source.")
        }
    }
    LaunchedEffect(player.failed) {
        if (player.failed) tryNext()
    }
    LaunchedEffect(current) {
        val link = current ?: return@LaunchedEffect
        val frames = player.frameCount
        delay(25_000)
        if (current == link && player.frameCount == frames && !player.ended && !player.failed) {
            System.err.println("player: no picture after 25 s from " + link.source)
            tryNext()
        }
    }
    LaunchedEffect(player.ended) {
        if (player.ended) {
            save()
            if (cx.desktop.core.Prefs.autoNext) request.next.firstOrNull()?.let { (data, label) ->
                onPlayNext(request.copy(episodeData = data, episodeLabel = label, startMs = 0, next = request.next.drop(1)))
            }
        }
    }
    LaunchedEffect(Unit) {
        while (true) {
            delay(10_000)
            save()
        }
    }
    LaunchedEffect(lastMove, panel) {
        controlsVisible = true
        if (panel == null) {
            delay(3_000)
            controlsVisible = false
        }
    }
    LaunchedEffect(Unit) { focus.requestFocus() }

    Box(
        Modifier.fillMaxSize().background(Color.Black)
            .focusRequester(focus).focusable()
            .onPointerEvent(PointerEventType.Move) { lastMove = System.currentTimeMillis() }
            .onKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onKeyEvent false
                lastMove = System.currentTimeMillis()
                when (e.key) {
                    Key.Spacebar, Key.K -> { player.togglePause(); true }
                    Key.DirectionRight, Key.L -> { player.seekBy(seekMs); true }
                    Key.DirectionLeft, Key.J -> { player.seekBy(-seekMs); true }
                    Key.DirectionUp -> { player.volume = player.volume + 10; true }
                    Key.DirectionDown -> { player.volume = player.volume - 10; true }
                    Key.F -> { AppWindow.toggleFullscreen(); true }
                    Key.Backspace -> { onBack(); true }
                    else -> false
                }
            }
    ) {
        val image = player.frame
        Canvas(Modifier.fillMaxSize().handCursor().clickable(indication = null, interactionSource = null) { player.togglePause() }) {
            player.frameCount // redraw on each decoded frame
            if (image != null && image.width > 0) synchronized(player.frameLock) {
                val scale = min(size.width / image.width, size.height / image.height)
                val w = image.width * scale
                val h = image.height * scale
                drawImage(image, IntOffset.Zero, IntSize(image.width, image.height),
                    IntOffset(((size.width - w) / 2).roundToInt(), ((size.height - h) / 2).roundToInt()),
                    IntSize(w.roundToInt(), h.roundToInt()), filterQuality = FilterQuality.Medium)
            }
        }
        if (player.buffering || (current == null && !localPlaying)) {
            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (current != null || loadingLinks || localPlaying) CircularProgressIndicator()
                Text(
                    when {
                        current != null || localPlaying -> tr("Memuatkan video…", "Loading video…")
                        loadingLinks -> tr("Mencari sumber video… (${links.size} dijumpai)", "Finding video sources… (${links.size} found)")
                        else -> tr("Tiada sumber video dijumpai untuk tajuk ini.", "No video source was found for this title.") + (linkError?.let { " ($it)" } ?: "")
                    },
                    color = Color.White,
                )
                if (current == null && !loadingLinks && !localPlaying) Button(onClick = onBack) { Text(tr("Kembali", "Back")) }
            }
        }
        if (controlsVisible || !player.playing) {
            // Top: title and back
            Row(
                Modifier.fillMaxWidth().background(Color.Black.copy(alpha = 0.55f)).padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                TextButton(onClick = onBack) { Text(tr("← Kembali", "← Back"), color = Color.White) }
                Column(Modifier.weight(1f)) {
                    Text(request.title, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                    request.episodeLabel?.let { Text(it, color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp) }
                }
                notice?.let { Text(it, color = Palette.muted, fontSize = 12.sp) }
            }
            // Bottom: seek bar and buttons
            Column(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color.Black.copy(alpha = 0.6f))
                    .padding(horizontal = 20.dp, vertical = 8.dp)
            ) {
                var dragging by remember { mutableStateOf<Float?>(null) }
                val length = player.lengthMs.coerceAtLeast(1)
                Slider(
                    value = dragging ?: (player.timeMs.toFloat() / length).coerceIn(0f, 1f),
                    onValueChange = { dragging = it; lastMove = System.currentTimeMillis() },
                    onValueChangeFinished = { dragging?.let { player.seekTo((it * length).toLong()) }; dragging = null },
                    colors = SliderDefaults.colors(thumbColor = Palette.primary, activeTrackColor = Palette.primary),
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { player.togglePause() }) { Text(if (player.playing) tr("❚❚ Jeda", "❚❚ Pause") else tr("▶ Main", "▶ Play"), color = Color.White) }
                    TextButton(onClick = { player.seekBy(-seekMs) }) { Text("⟲ ${seekMs / 1000}s", color = Color.White) }
                    TextButton(onClick = { player.seekBy(seekMs) }) { Text("${seekMs / 1000}s ⟳", color = Color.White) }
                    Text("${formatTime(player.timeMs)} / ${formatTime(player.lengthMs)}", color = Color.White, fontSize = 13.sp)
                    Spacer(Modifier.weight(1f))
                    if (request.next.isNotEmpty()) TextButton(onClick = {
                        save()
                        val (data, label) = request.next.first()
                        onPlayNext(request.copy(episodeData = data, episodeLabel = label, startMs = 0, next = request.next.drop(1)))
                    }) { Text(tr("Episod seterusnya ⏭", "Next episode ⏭"), color = Color.White) }
                    TextButton(onClick = { panel = if (panel == "sources") null else "sources" }) {
                        Text(tr("Sumber", "Sources") + " (${links.size})", color = Color.White)
                    }
                    TextButton(onClick = { panel = if (panel == "subs") null else "subs" }) {
                        Text(tr("Sarikata", "Subtitles") + " (${subtitles.size})", color = Color.White)
                    }
                    TextButton(onClick = { panel = if (panel == "report") null else "report" }) { Text(tr("Lapor", "Report"), color = Color.White) }
                    TextButton(onClick = { AppWindow.toggleFullscreen() }) { Text(tr("⛶ Skrin penuh", "⛶ Full screen"), color = Color.White) }
                }
            }
        }
        panel?.let { which ->
            Column(
                Modifier.align(Alignment.CenterEnd).padding(end = 20.dp).width(360.dp).heightIn(max = 480.dp)
                    .clip(RoundedCornerShape(12.dp)).background(Palette.surface.copy(alpha = 0.97f)).padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                when (which) {
                    "sources" -> {
                        Text(tr("Pilih sumber video", "Choose a video source"), color = Palette.text, fontWeight = FontWeight.SemiBold)
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            items(links.sortedByDescending { it.quality }) { link ->
                                Row(
                                    Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                                        .background(if (link == current) Palette.primary else Palette.surfaceHigh)
                                        .handCursor().clickable { panel = null; start(link, player.timeMs) }.padding(10.dp)
                                ) {
                                    Text(link.label() + "  (" + link.type.name + ")", color = Palette.text, fontSize = 13.sp, modifier = Modifier.weight(1f))
                                    if (link.type == ExtractorLinkType.M3U8 || link.type == ExtractorLinkType.VIDEO) Text("⬇", color = Palette.text,
                                        modifier = Modifier.handCursor().clickable {
                                            cx.desktop.core.Downloads.start(request.title, request.episodeLabel, request.poster, link.url, link.referer, link.headers,
                                                hls = link.type == ExtractorLinkType.M3U8)
                                            notice = tr("Muat turun bermula: lihat menu Muat turun.", "Download started: see Downloads.")
                                        }.padding(horizontal = 6.dp))
                                }
                            }
                        }
                    }
                    "subs" -> {
                        Text(tr("Sarikata", "Subtitles"), color = Palette.text, fontWeight = FontWeight.SemiBold)
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            item {
                                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                                    .background(if (subtitle == null) Palette.primary else Palette.surfaceHigh)
                                    .handCursor().clickable { subtitle = null; player.disableSubtitles(); panel = null }.padding(10.dp)) {
                                    Text(tr("Tiada", "Off"), color = Palette.text, fontSize = 13.sp)
                                }
                            }
                            items(subtitles.toList()) { s ->
                                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                                    .background(if (s == subtitle) Palette.primary else Palette.surfaceHigh)
                                    .handCursor().clickable { subtitle = s; player.addSubtitle(s.url, s.headers); panel = null }.padding(10.dp)) {
                                    Text(s.lang, color = Palette.text, fontSize = 13.sp)
                                }
                            }
                        }
                    }
                    "report" -> {
                        Text(tr("Laporkan masalah video ini kepada admin CloudXtream?", "Report a problem with this video to the CloudXtream admin?"), color = Palette.text)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = {
                                panel = null
                                scope.launch {
                                    val ok = Activation.report(
                                        request.title + (request.episodeLabel?.let { " · $it" } ?: ""),
                                        request.api?.name ?: "fail tempatan",
                                        "Windows · " + (current?.label() ?: "tiada sumber") + " · ${links.size} sumber",
                                        "win-" + AppInfo.version,
                                    )
                                    notice = if (ok) tr("Laporan dihantar. Terima kasih!", "Report sent. Thank you!") else tr("Laporan gagal dihantar.", "The report could not be sent.")
                                }
                            }) { Text(tr("Hantar laporan", "Send report")) }
                            TextButton(onClick = { panel = null }) { Text(tr("Batal", "Cancel")) }
                        }
                    }
                }
            }
        }
    }
}
