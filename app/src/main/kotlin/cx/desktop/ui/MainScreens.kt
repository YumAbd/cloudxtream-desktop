package cx.desktop.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import cx.desktop.core.Download
import cx.desktop.core.Downloads
import cx.desktop.core.History
import cx.desktop.core.Library
import cx.desktop.core.Plugins
import cx.desktop.core.Prefs
import cx.desktop.core.Remote
import cx.desktop.core.Settings
import cx.desktop.core.WatchStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/** Providers whose media types are among the preferred ones (General > Preferred media). */
fun visibleProviders(): List<MainAPI> {
    val types = Prefs.preferredTypes
    return Plugins.providers(true).filter { api -> api.supportedTypes.any { it.name in types } }
}

@Composable
fun MainScreens(navigator: Navigator, account: AccountInfo, warning: String?) {
    val screen = navigator.current
    if (screen is Screen.Player) {
        PlayerScreen(screen.request, onBack = { navigator.back() }, onPlayNext = { navigator.replace(Screen.Player(it)) })
        return
    }
    Row(Modifier.fillMaxSize().background(Palette.background)) {
        NavRail(navigator, account)
        Column(Modifier.weight(1f).fillMaxHeight()) {
            warning?.let {
                Text(it, color = Palette.text, fontSize = 13.sp,
                    modifier = Modifier.fillMaxWidth().background(Palette.surfaceHigh).padding(horizontal = 24.dp, vertical = 8.dp))
            }
            UpdateBanner()
            if (navigator.stack.size > 1) {
                TextButton(onClick = { navigator.back() }, modifier = Modifier.padding(start = 12.dp, top = 8.dp)) { Text("← Kembali") }
            }
            Box(Modifier.weight(1f)) {
                when (screen) {
                    Screen.Home -> HomeScreen(navigator)
                    is Screen.Search -> key(screen.query) { SearchScreen(navigator, screen.query) }
                    Screen.Library -> LibraryScreen(navigator)
                    Screen.Downloads -> DownloadsScreen(navigator)
                    is Screen.Settings -> SettingsScreen(screen.section, account) { navigator.replace(Screen.Settings(it)) }
                    is Screen.Details -> key(screen) { DetailsScreen(navigator, screen) }
                    is Screen.Player -> Unit
                }
            }
        }
    }
}

data class AccountInfo(val displayName: String, val licenseExpiresMs: Long?, val onSignOut: () -> Unit)

@Composable
private fun NavRail(navigator: Navigator, account: AccountInfo) {
    val root = navigator.stack.first()
    Column(
        Modifier.width(92.dp).fillMaxHeight().background(Palette.surface).padding(vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Image(painterResource("icon.png"), null, Modifier.size(44.dp).clip(RoundedCornerShape(10.dp)).clickable { navigator.home() })
        Spacer(Modifier.height(14.dp))
        RailItem(Icons.Filled.Home, "Utama", root == Screen.Home) { navigator.root(Screen.Home) }
        RailItem(Icons.Filled.Search, "Cari", root is Screen.Search) { navigator.root(Screen.Search()) }
        RailItem(Icons.Filled.VideoLibrary, "Pustaka", root == Screen.Library) { navigator.root(Screen.Library) }
        RailItem(Icons.Filled.Download, "Muat turun", root == Screen.Downloads) { navigator.root(Screen.Downloads) }
        RailItem(Icons.Filled.Settings, "Tetapan", root is Screen.Settings && (root as Screen.Settings).section != "account") {
            navigator.root(Screen.Settings())
        }
        Spacer(Modifier.weight(1f))
        RailItem(Icons.Filled.AccountCircle, account.displayName.ifBlank { "Akaun" }, (root as? Screen.Settings)?.section == "account") {
            navigator.root(Screen.Settings("account"))
        }
    }
}

@Composable
private fun RailItem(icon: ImageVector, label: String, selected: Boolean, onClick: () -> Unit) {
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    Column(
        Modifier.width(80.dp).clip(RoundedCornerShape(12.dp))
            .background(if (selected) Palette.primary.copy(alpha = 0.22f) else if (hovered) Palette.surfaceHigh else Palette.surface)
            .hoverable(hover).clickable(onClick = onClick).padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(icon, label, tint = if (selected) Palette.primary else Palette.muted, modifier = Modifier.size(26.dp))
        Text(label, color = if (selected) Palette.text else Palette.muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Home rows are kept per provider so Back does not reload them. */
private object HomeCache {
    val rows = mutableMapOf<String, List<HomePageList>>()
}

@Composable
private fun HomeScreen(navigator: Navigator) {
    val providers = remember { visibleProviders().filter { it.hasMainPage && it.mainPage.isNotEmpty() } }
    var selected by remember { mutableStateOf<MainAPI?>(null) }
    LaunchedEffect(Unit) {
        val chosen = Settings.string("home_provider")?.let { name -> providers.firstOrNull { it.name == name } }
        val server = Remote.defaultProvider()?.let { internal -> providers.firstOrNull { it.sourcePlugin == internal } }
        selected = chosen ?: server ?: providers.firstOrNull()
    }
    val api = selected
    val rows = remember(api) { mutableStateListOf<HomePageList>().apply { api?.let { HomeCache.rows[it.name]?.let(::addAll) } } }
    var loading by remember(api) { mutableStateOf(false) }
    var failed by remember(api) { mutableStateOf<String?>(null) }

    LaunchedEffect(api) {
        if (api == null || rows.isNotEmpty()) return@LaunchedEffect
        loading = true
        val slots = Semaphore(3)
        coroutineScope {
            api.mainPage.take(14).map { page ->
                async {
                    slots.withPermit {
                        runCatching {
                            withTimeoutOrNull(30_000) { withContext(Dispatchers.IO) { api.getMainPage(1, MainPageRequest(page.name, page.data, page.horizontalImages)) } }
                        }.onFailure { failed = Plugins.describe(it) }.getOrNull()?.items.orEmpty()
                            .filter { it.list.isNotEmpty() }
                            .forEach { rows.add(it) }
                    }
                }
            }.awaitAll()
        }
        HomeCache.rows[api.name] = rows.toList()
        loading = false
    }

    val history = remember { History.all() }
    LazyColumn(contentPadding = PaddingValues(vertical = 20.dp), verticalArrangement = Arrangement.spacedBy(26.dp)) {
        item {
            ProviderPicker(providers, api) {
                selected = it
                Settings.set("home_provider", it.name)
            }
        }
        if (Prefs.showContinueWatching && history.isNotEmpty()) item {
            CardRow("Teruskan menonton", history.take(20).mapNotNull { entry ->
                val provider = Plugins.provider(entry.provider) ?: return@mapNotNull null
                CardItem(
                    title = entry.title,
                    poster = entry.poster,
                    headers = null,
                    subtitle = listOfNotNull(entry.episodeLabel, provider.name).joinToString(" · "),
                    progress = if (entry.durationMs > 0) entry.positionMs.toFloat() / entry.durationMs else null,
                ) { navigator.open(Screen.Details(provider, entry.url, entry.title, entry.poster)) }
            })
        }
        items(rows.toList()) { row ->
            CardRow(row.name, row.list.map { it.toCard(navigator, api!!) }, row.isHorizontalImages)
        }
        if (loading && rows.isEmpty()) item {
            Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        }
        if (!loading && rows.isEmpty()) item {
            Text(
                if (api == null) "Tiada sumber. Semak Tetapan → Kemas kini → Extension."
                else "Tiada kandungan daripada ${api.name}" + (failed?.let { " ($it)" } ?: "") + ". Cuba sumber lain.",
                color = Palette.muted, modifier = Modifier.padding(horizontal = 24.dp),
            )
        }
    }
}

fun SearchResponse.toCard(navigator: Navigator, api: MainAPI) = CardItem(
    title = name,
    poster = posterUrl,
    headers = posterHeaders,
    subtitle = quality?.name,
) { navigator.open(Screen.Details(api, url, name, posterUrl)) }

@Composable
private fun ProviderPicker(providers: List<MainAPI>, selected: MainAPI?, onSelect: (MainAPI) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Row(Modifier.padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Sumber:", color = Palette.muted)
        Box {
            Pill((selected?.name ?: "Pilih sumber") + "  ▾", true) { open = true }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                providers.forEach { api ->
                    DropdownMenuItem(text = { Text(api.name) }, onClick = { open = false; onSelect(api) })
                }
            }
        }
    }
}

@Composable
private fun SearchScreen(navigator: Navigator, initial: String) {
    var query by remember { mutableStateOf(initial) }
    val focus = remember { FocusRequester() }
    val results = remember { mutableStateListOf<Pair<MainAPI, List<SearchResponse>>>() }
    var pending by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) { if (initial.isBlank()) runCatching { focus.requestFocus() } }
    LaunchedEffect(initial) {
        if (initial.isBlank()) return@LaunchedEffect
        val providers = visibleProviders()
        pending = providers.size
        val slots = Semaphore(8)
        coroutineScope {
            providers.forEach { api ->
                launch {
                    slots.withPermit {
                        val found = runCatching { withTimeoutOrNull(25_000) { withContext(Dispatchers.IO) { api.search(initial, 1)?.items } } }.getOrNull().orEmpty()
                        if (found.isNotEmpty()) results.add(api to found)
                        pending--
                    }
                }
            }
        }
    }
    LazyColumn(contentPadding = PaddingValues(vertical = 20.dp), verticalArrangement = Arrangement.spacedBy(26.dp)) {
        item {
            Row(Modifier.padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    placeholder = { Text("Cari filem, siri, anime di semua sumber…") },
                    leadingIcon = { Icon(Icons.Filled.Search, null) },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { if (query.isNotBlank()) navigator.replace(Screen.Search(query.trim())) }),
                    modifier = Modifier.width(560.dp).focusRequester(focus),
                )
                if (pending > 0) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text("$pending sumber lagi…", color = Palette.muted, fontSize = 13.sp)
                }
            }
        }
        items(results.toList()) { (api, list) ->
            CardRow(api.name, list.map { it.toCard(navigator, api) })
        }
        if (initial.isNotBlank() && pending == 0 && results.isEmpty()) item {
            Text("Tiada hasil untuk “$initial”.", color = Palette.muted, modifier = Modifier.padding(horizontal = 24.dp))
        }
    }
}

@Composable
private fun LibraryScreen(navigator: Navigator) {
    val tabs = WatchStatus.entries.map { it.label to it.name } + listOf("Kegemaran" to "fav", "Langganan" to "sub")
    var tab by remember { mutableStateOf(Settings.string("library_tab") ?: WatchStatus.WATCHING.name) }
    val entries = remember(tab) {
        Library.all().filter {
            when (tab) {
                "fav" -> it.favourite
                "sub" -> it.subscribed
                else -> it.status?.name == tab
            }
        }
    }
    Column(Modifier.fillMaxSize().padding(top = 20.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Text("Pustaka", color = Palette.text, fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 24.dp))
        Row(Modifier.padding(horizontal = 24.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            tabs.forEach { (label, id) -> Pill(label, id == tab) { tab = id; Settings.set("library_tab", id) } }
        }
        if (entries.isEmpty()) {
            Text("Tiada tajuk dalam senarai ini. Tambah dari halaman butiran tajuk.", color = Palette.muted, modifier = Modifier.padding(horizontal = 24.dp))
        }
        LazyVerticalGrid(
            GridCells.Adaptive(posterWidth() + 14.dp),
            contentPadding = PaddingValues(horizontal = 24.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            items(entries) { e ->
                val provider = Plugins.provider(e.provider)
                PosterCard(CardItem(e.title, e.poster, null, provider?.name ?: "${e.provider} (tiada)") {
                    if (provider != null) navigator.open(Screen.Details(provider, e.url, e.title, e.poster))
                })
            }
        }
    }
}

@Composable
private fun DownloadsScreen(navigator: Navigator) {
    val items = Downloads.items
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Muat turun", color = Palette.text, fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Text(Prefs.downloadDir.absolutePath, color = Palette.muted, fontSize = 12.sp)
            TextButton(onClick = { openFolder(Prefs.downloadDir) }) { Text("Buka folder") }
        }
        if (items.isEmpty()) {
            Text("Belum ada muat turun. Pilih \"Muat turun\" pada sumber video dalam pemain atau halaman butiran.", color = Palette.muted)
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(items.toList(), key = { it.id }) { d -> DownloadRow(d, navigator) }
        }
    }
}

@Composable
private fun DownloadRow(d: Download, navigator: Navigator) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Palette.surface).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        RemoteImage(d.poster, null, Modifier.size(60.dp, 90.dp).clip(RoundedCornerShape(6.dp)))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(d.title + (d.label?.let { " · $it" } ?: ""), color = Palette.text, fontWeight = FontWeight.SemiBold)
            val size = "%.1f MB".format(d.bytes / 1_048_576.0)
            Text(
                when (d.state) {
                    "running" -> "Memuat turun… ${(d.progress * 100).toInt()}% · $size"
                    "queued" -> "Menunggu…"
                    "done" -> "Selesai · $size"
                    "cancelled" -> "Dibatalkan"
                    else -> "Gagal" + (d.error?.let { ": $it" } ?: "")
                },
                color = if (d.state == "failed") Palette.error else Palette.muted, fontSize = 12.sp,
            )
            if (d.state == "running") LinearProgressIndicator(progress = { d.progress }, Modifier.fillMaxWidth())
        }
        if (d.state == "done") TextButton(onClick = {
            navigator.open(Screen.Player(PlayRequest(null, d.file, d.title, d.poster, d.file, d.label, 0, localFile = d.file)))
        }) { Text("▶ Main") }
        if (d.state == "running" || d.state == "queued") TextButton(onClick = { Downloads.cancel(d.id) }) { Text("Batal") }
        TextButton(onClick = { Downloads.delete(d.id) }) { Text("Padam", color = Palette.error) }
    }
}

fun openFolder(dir: File) {
    runCatching {
        dir.mkdirs()
        java.awt.Desktop.getDesktop().open(dir)
    }
}

@Composable
fun posterWidth() = when (Prefs.posterSize) { 0 -> 120.dp; 2 -> 180.dp; else -> 150.dp }

@Composable
private fun UpdateBanner() {
    var release by remember { mutableStateOf<cx.desktop.core.Updates.Release?>(null) }
    var progress by remember { mutableStateOf<Float?>(null) }
    var failed by remember { mutableStateOf(false) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    LaunchedEffect(Unit) { if (Prefs.autoCheckUpdates) release = cx.desktop.core.Updates.check() }
    val r = release ?: return
    Row(
        Modifier.fillMaxWidth().background(Palette.primary.copy(alpha = 0.25f)).padding(horizontal = 24.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            when {
                failed -> "Muat turun kemas kini gagal. Cuba lagi."
                progress != null -> "Memuat turun CloudXtream ${r.version}… ${((progress ?: 0f) * 100).toInt()}%"
                else -> "Versi baharu CloudXtream ${r.version} tersedia."
            },
            color = Palette.text, modifier = Modifier.weight(1f),
        )
        if (progress == null) TextButton(onClick = {
            failed = false
            progress = 0f
            scope.launch {
                runCatching { cx.desktop.core.Updates.download(r) { progress = it } }
                    .onSuccess { cx.desktop.core.Updates.install(it); cx.desktop.player.Vlc.release(); kotlin.system.exitProcess(0) }
                    .onFailure { failed = true; progress = null }
            }
        }) { Text("Kemas kini sekarang") }
    }
}
