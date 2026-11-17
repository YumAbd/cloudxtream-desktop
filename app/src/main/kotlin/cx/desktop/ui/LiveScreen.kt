package cx.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Article
import androidx.compose.material.icons.filled.ChildCare
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.SportsSoccer
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.newExtractorLink
import cx.desktop.core.Live
import cx.desktop.core.LiveChannel
import cx.desktop.core.LiveProblem
import cx.desktop.core.LiveSource
import cx.desktop.core.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** The player's "provider" for a Live TV channel or a radio station: the channel itself is the link. */
object LiveApi : MainAPI() {
    override var name = "Live TV"
    override var mainUrl = "https://cloudxpanel.infopc.xyz"
    override val supportedTypes = setOf(TvType.Live)
    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val c = LiveChannel.fromJson(JSONObject(data))
        callback(newExtractorLink("Live TV", c.name, c.url) {
            c.referer?.let { referer = it }
            c.userAgent?.let { headers = mapOf("User-Agent" to it) }
        })
        return true
    }
}

private const val FAV = "\u0000fav"
private const val RECENT = "\u0000recent"
private const val ALL = "\u0000all"

private fun categoryIcon(name: String): ImageVector {
    val n = name.lowercase()
    return when {
        listOf("sport", "sukan", "bola", "football", "soccer", "olahraga").any { n.contains(it) } -> Icons.Filled.SportsSoccer
        listOf("movie", "film", "cinema", "wayang", "drama").any { n.contains(it) } -> Icons.Filled.Movie
        listOf("news", "berita", "info").any { n.contains(it) } -> Icons.Filled.Article
        listOf("kid", "kanak", "anak", "cartoon", "kartun", "anime").any { n.contains(it) } -> Icons.Filled.ChildCare
        listOf("music", "muzik", "lagu", "radio").any { n.contains(it) } -> Icons.Filled.MusicNote
        listOf("agama", "relig", "islam", "quran", "dakwah").any { n.contains(it) } -> Icons.Filled.MenuBook
        listOf("docu", "dokumen", "knowledge", "edu", "pendidikan", "science").any { n.contains(it) } -> Icons.Filled.School
        else -> Icons.Filled.PlayCircle
    }
}

/** Live TV (radio = false) or Radio: sources, categories, then channels; play in the app's player. */
@Composable
fun LiveScreen(navigator: Navigator, radio: Boolean) {
    val scope = rememberCoroutineScope()
    val scopeKey = if (radio) "radio" else "tv"
    var sources by remember { mutableStateOf<List<LiveSource>>(emptyList()) }
    var allowOwn by remember { mutableStateOf(false) }
    var current by rememberSaveable(radio) { mutableStateOf("") }
    var channels by remember { mutableStateOf<List<LiveChannel>>(emptyList()) }
    var group by rememberSaveable(radio) { mutableStateOf<String?>(null) }
    var query by rememberSaveable(radio) { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var favourites by remember { mutableStateOf(Live.favourites(scopeKey).map { it.key }.toSet()) }
    var adding by remember { mutableStateOf(false) }
    var reloadTick by remember { mutableStateOf(0) }
    val grid = rememberLazyGridState()

    fun visible(list: List<LiveChannel>) = if (radio || Live.showAdult) list else list.filter { !Live.isAdult(it) }

    fun select(id: String, fresh: Boolean) {
        if (id != current) group = null
        current = id
        message = null
        when (id) {
            FAV -> channels = visible(Live.favourites(scopeKey))
            RECENT -> channels = visible(Live.recent(scopeKey))
            else -> {
                val source = sources.firstOrNull { it.id == id } ?: return
                loading = true
                channels = emptyList()
                scope.launch {
                    try {
                        val list = withContext(Dispatchers.IO) { Live.channels(source, fresh) }
                        if (current == id) channels = visible(list)
                        if (current == id && list.isEmpty()) message = tr("Tiada saluran dalam senarai ini.", "No channels in this list.")
                    } catch (e: Exception) {
                        if (current == id) message = (e as? LiveProblem)?.message ?: (tr("Gagal memuatkan", "Could not load") + " (" + e.javaClass.simpleName + ").")
                    } finally {
                        if (current == id) loading = false
                    }
                }
            }
        }
    }

    LaunchedEffect(reloadTick) {
        loading = true
        val config = withContext(Dispatchers.IO) { Live.config(reloadTick > 0 || Settings.long("live_config_ms") == null) }
        allowOwn = config.allowOwn && !radio
        val admin = config.admin.filter { (it.media == "radio") == radio }
        sources = if (radio) admin + Live.RADIO_MALAYSIA else admin + if (allowOwn) Live.ownSources() else emptyList()
        loading = false
        val keep = current.takeIf { c -> c == FAV || c == RECENT || sources.any { it.id == c } }
        select(keep ?: sources.firstOrNull()?.id ?: FAV, reloadTick > 0)
    }

    // Categories (Live TV, more than one, no search): first the categories, then the chosen one's channels.
    val keys = remember(channels) { channels.map { c -> Live.groupsOf(c).map { it.first }.toSet() } }
    val groups = remember(channels) {
        val names = LinkedHashMap<String, String>(); val counts = HashMap<String, Int>()
        for (c in channels) for ((k, n) in Live.groupsOf(c)) { names.getOrPut(k) { n }; counts[k] = (counts[k] ?: 0) + 1 }
        names.map { (k, n) -> n to (counts[k] ?: 0) }
    }
    val byCategory = !radio && current != FAV && current != RECENT && groups.size > 1
    val q = query.trim().lowercase()
    val showingCategories = byCategory && group == null && q.isEmpty()
    val key = group?.takeIf { it != ALL }?.let { Live.groupKey(it) }
    val shown = remember(channels, group, q) {
        if (showingCategories) emptyList() else channels.filterIndexed { i, c ->
            (key == null || q.isNotEmpty() || keys.getOrNull(i)?.contains(key) == true) && (q.isEmpty() || c.name.lowercase().contains(q))
        }
    }

    fun play(c: LiveChannel, list: List<LiveChannel>) {
        Live.addRecent(c, scopeKey)
        if (c.isYoutube) {
            runCatching { java.awt.Desktop.getDesktop().browse(java.net.URI("https://www.youtube.com/" + c.url.removePrefix("youtube:") + "/live")) }
            return
        }
        if (c.drm != null) { message = tr("Saluran ini dilindungi DRM dan hanya boleh dimainkan dalam app Android.", "This channel is DRM-protected and plays only in the Android app."); return }
        val playable = list.filter { !it.isYoutube && it.drm == null }.ifEmpty { listOf(c) }
        val at = playable.indexOfFirst { it.key == c.key }.coerceAtLeast(0)
        val entry = { x: LiveChannel -> x.toJson().toString() to x.name }
        navigator.open(Screen.Player(PlayRequest(LiveApi, "live:" + c.url, c.name, c.logo, c.toJson().toString(),
            group?.takeIf { it != ALL } ?: sources.firstOrNull { it.id == current }?.name, 0,
            next = playable.drop(at + 1).map(entry), prev = playable.take(at).reversed().map(entry))))
    }

    Column(Modifier.fillMaxSize().padding(top = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(if (radio) "Radio" else "Live TV", color = Palette.text, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                val noun = if (radio) tr("stesen", "stations") else tr("saluran", "channels")
                Text(when {
                    channels.isEmpty() -> ""
                    showingCategories -> "${groups.size} " + tr("kategori", "categories") + " · ${channels.size} $noun"
                    else -> "${shown.size} $noun" + (group?.takeIf { it != ALL && q.isEmpty() }?.let { " · $it" } ?: "")
                }, color = Palette.muted, fontSize = 13.sp)
            }
            if (loading) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
            IconButton(onClick = { reloadTick++ }) { Icon(Icons.Filled.Refresh, tr("Muat semula", "Reload"), tint = Palette.text) }
            if (allowOwn) IconButton(onClick = { adding = true }) { Icon(Icons.Filled.Add, tr("Tambah playlist", "Add playlist"), tint = Palette.text) }
        }
        Row(Modifier.padding(horizontal = 24.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            sources.forEach { s -> Pill(s.name + if (s.own) "  ✕" else "", s.id == current) {
                if (s.id == current && s.own) { Live.removeOwn(s.id); if (current == s.id) current = ""; reloadTick++ } else select(s.id, false)
            } }
            Pill("★ " + tr("Kegemaran", "Favourites"), current == FAV) { select(FAV, false) }
            Pill(tr("Baru dimainkan", "Recently played"), current == RECENT) { select(RECENT, false) }
        }
        OutlinedTextField(query, { query = it }, singleLine = true, placeholder = { Text(if (radio) tr("Cari stesen", "Search stations") else tr("Cari saluran", "Search channels")) },
            modifier = Modifier.padding(horizontal = 24.dp).fillMaxWidth())
        val last = if (radio || current == RECENT || q.isNotEmpty() || (byCategory && group != null)) null else Live.recent(scopeKey).firstOrNull()
        if (last != null) {
            Text("▶  " + tr("Sambung tonton: ", "Continue: ") + last.name, color = Palette.text, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 24.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Palette.surfaceHigh)
                    .handCursor().clickable {
                        val same = channels.filter { it.group == last.group }
                        play(last, if (same.any { it.key == last.key }) same else listOf(last))
                    }.padding(horizontal = 16.dp, vertical = 10.dp))
        }
        if (byCategory && group != null && q.isEmpty()) {
            Text("‹  " + tr("Kategori", "Categories") + "  ·  " + (if (group == ALL) tr("Semua", "All") else group ?: ""), color = Palette.accent, fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 24.dp).handCursor().clickable { group = null })
        }
        message?.let { Text(it, color = Palette.muted, modifier = Modifier.padding(horizontal = 24.dp)) }
        if (current == FAV && channels.isEmpty()) Text(tr("Tiada kegemaran lagi. Klik ★ pada saluran untuk menambahnya.", "No favourites yet. Click ★ on a channel to add it."), color = Palette.muted, modifier = Modifier.padding(horizontal = 24.dp))

        LazyVerticalGrid(
            GridCells.Adaptive(if (showingCategories) 180.dp else 150.dp),
            state = grid,
            contentPadding = PaddingValues(horizontal = 24.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (showingCategories) {
                val tiles = listOf((tr("Semua", "All") to channels.size) to ALL) + groups.map { it to it.first }
                itemsIndexed(tiles) { i, (pair, id) ->
                    Tile(pair.first, "${pair.second} " + tr("saluran", "channels"), null, if (i == 0) Icons.Filled.Apps else categoryIcon(pair.first), false, null) { group = id }
                }
            } else {
                itemsIndexed(shown, key = { i, c -> i.toString() + c.key }) { _, c ->
                    val fav = c.key in favourites
                    Tile(c.name, if (c.isYoutube) "YouTube" else (group?.takeIf { it != ALL } ?: Live.groupsOf(c).firstOrNull()?.second ?: c.group),
                        c.logo, null, fav, {
                            Live.toggleFavourite(c, scopeKey)
                            favourites = Live.favourites(scopeKey).map { it.key }.toSet()
                            if (current == FAV) channels = visible(Live.favourites(scopeKey))
                        }) { play(c, shown) }
                }
            }
        }
    }

    if (adding) AddPlaylistDialog(onDismiss = { adding = false }) { source -> Live.addOwn(source); adding = false; current = source.id; reloadTick++ }
}

@Composable
private fun Tile(title: String, sub: String, logo: String?, icon: ImageVector?, favourite: Boolean, onFavourite: (() -> Unit)?, onClick: () -> Unit) {
    Column(Modifier.clip(RoundedCornerShape(16.dp)).background(Palette.surface).handCursor().clickable(onClick = onClick).padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 10f).clip(RoundedCornerShape(12.dp)).background(Palette.surfaceHigh), contentAlignment = Alignment.Center) {
            when {
                icon != null -> Icon(icon, null, tint = Palette.accent, modifier = Modifier.size(42.dp))
                logo != null -> RemoteImage(logo, null, Modifier.fillMaxSize().padding(12.dp), ContentScale.Fit)
                else -> Text(title.split(' ').filter { it.isNotBlank() }.take(2).joinToString("") { it.take(1).uppercase() }, color = Palette.text, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            }
            if (onFavourite != null) Icon(Icons.Filled.Star, tr("Kegemaran", "Favourite"), tint = if (favourite) Palette.accent else Palette.muted.copy(alpha = 0.5f),
                modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).size(22.dp).handCursor().clickable(onClick = onFavourite))
        }
        Text(title, color = Palette.text, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(sub, color = Palette.muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** The user's own playlist (when the admin allows it): a link in any form, or an Xtream login. */
@Composable
private fun AddPlaylistDialog(onDismiss: () -> Unit, onAdded: (LiveSource) -> Unit) {
    val scope = rememberCoroutineScope()
    var xtream by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var link by remember { mutableStateOf("") }
    var server by remember { mutableStateOf("") }
    var user by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("Tambah playlist", "Add playlist")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Pill(tr("Link playlist", "Playlist link"), !xtream) { xtream = false }
                    Pill("Xtream Codes", xtream) { xtream = true }
                }
                OutlinedTextField(name, { name = it }, singleLine = true, label = { Text(tr("Nama (pilihan)", "Name (optional)")) })
                if (xtream) {
                    OutlinedTextField(server, { server = it }, singleLine = true, label = { Text("Server (http://...)") })
                    OutlinedTextField(user, { user = it }, singleLine = true, label = { Text("Username") })
                    OutlinedTextField(pass, { pass = it }, singleLine = true, label = { Text("Password") })
                } else {
                    OutlinedTextField(link, { link = it }, singleLine = true, label = { Text(tr("Link (M3U, shortlink atau Xtream penuh)", "Link (M3U, shortlink or full Xtream)")) })
                }
                error?.let { Text(it, color = Palette.error, fontSize = 13.sp) }
            }
        },
        confirmButton = {
            TextButton(enabled = !busy, onClick = {
                busy = true; error = null
                val source = if (xtream) LiveSource(Live.newId(), name.ifBlank { "Xtream" }, "xtream", server = server.trim(), username = user.trim(), password = pass, own = true)
                    else LiveSource(Live.newId(), name.ifBlank { tr("Playlist saya", "My playlist") }, "m3u", url = link.trim(), own = true)
                scope.launch {
                    try {
                        withContext(Dispatchers.IO) { Live.channels(source, true) }
                        onAdded(source)
                    } catch (e: Exception) {
                        error = (e as? LiveProblem)?.message ?: tr("Playlist tidak dapat ditambah.", "The playlist could not be added.")
                    } finally { busy = false }
                }
            }) { Text(if (busy) tr("Menyemak…", "Checking…") else tr("Tambah", "Add")) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Batal", "Cancel")) } },
    )
}
