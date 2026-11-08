package cx.desktop.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import cx.desktop.core.History
import cx.desktop.core.Plugins
import cx.desktop.core.Remote
import cx.desktop.core.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

@Composable
fun MainScreens(navigator: Navigator, displayName: String, licenseExpiresMs: Long?, warning: String?) {
    val screen = navigator.current
    if (screen is Screen.Player) {
        PlayerScreen(screen.request, onBack = { navigator.back() }, onPlayNext = { navigator.replace(Screen.Player(it)) })
        return
    }
    Column(Modifier.fillMaxSize().background(Palette.background)) {
        TopBar(navigator, displayName)
        warning?.let {
            Text(it, color = Palette.text, fontSize = 13.sp,
                modifier = Modifier.fillMaxWidth().background(Palette.surfaceHigh).padding(horizontal = 24.dp, vertical = 8.dp))
        }
        Box(Modifier.weight(1f)) {
            when (screen) {
                Screen.Home -> HomeScreen(navigator)
                is Screen.Search -> key(screen.query) { SearchScreen(navigator, screen.query) }
                is Screen.Details -> key(screen) { DetailsScreen(navigator, screen) }
                Screen.Settings -> SettingsScreen(displayName, licenseExpiresMs)
                is Screen.Player -> Unit
            }
        }
    }
}

@Composable
private fun TopBar(navigator: Navigator, displayName: String) {
    var query by remember { mutableStateOf("") }
    Row(
        Modifier.fillMaxWidth().background(Palette.surface).padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(Modifier.clickable { navigator.home() }, verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Image(painterResource("icon.png"), null, Modifier.size(34.dp).clip(RoundedCornerShape(8.dp)))
            Text("CloudXtream", color = Palette.text, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        }
        if (navigator.stack.size > 1) TextButton(onClick = { navigator.back() }) { Text("← Kembali") }
        Spacer(Modifier.weight(1f))
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            singleLine = true,
            placeholder = { Text("Cari filem, siri, anime…") },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = {
                if (query.isNotBlank()) navigator.open(Screen.Search(query.trim()))
            }),
            modifier = Modifier.width(360.dp).height(52.dp),
        )
        TextButton(onClick = { if (navigator.current != Screen.Settings) navigator.open(Screen.Settings) }) {
            Text(displayName.ifBlank { "Tetapan" })
        }
    }
}

/** Home rows are kept per provider so Back does not reload them. */
private object HomeCache {
    val rows = mutableMapOf<String, List<HomePageList>>()
}

@Composable
private fun HomeScreen(navigator: Navigator) {
    val providers = remember { Plugins.providers(Settings.bool("show_nsfw", false)).filter { it.hasMainPage && it.mainPage.isNotEmpty() } }
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
        if (history.isNotEmpty()) item {
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
        if (!loading && rows.isEmpty() && api != null) item {
            Text("Tiada kandungan daripada ${api.name}" + (failed?.let { " ($it)" } ?: "") + ". Cuba provider lain.",
                color = Palette.muted, modifier = Modifier.padding(horizontal = 24.dp))
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
private fun SearchScreen(navigator: Navigator, query: String) {
    val results = remember { mutableStateListOf<Pair<MainAPI, List<SearchResponse>>>() }
    var pending by remember { mutableStateOf(0) }
    LaunchedEffect(query) {
        val providers = Plugins.providers(Settings.bool("show_nsfw", false))
        pending = providers.size
        val slots = Semaphore(8)
        coroutineScope {
            providers.forEach { api ->
                launch {
                    slots.withPermit {
                        val found = runCatching { withTimeoutOrNull(25_000) { withContext(Dispatchers.IO) { api.search(query, 1)?.items } } }.getOrNull().orEmpty()
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
                Text("Hasil carian “$query”", color = Palette.text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                if (pending > 0) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text("$pending sumber lagi…", color = Palette.muted, fontSize = 13.sp)
                }
            }
        }
        items(results.toList()) { (api, list) ->
            CardRow(api.name, list.map { it.toCard(navigator, api) })
        }
        if (pending == 0 && results.isEmpty()) item {
            Text("Tiada hasil.", color = Palette.muted, modifier = Modifier.padding(horizontal = 24.dp))
        }
    }
}

@Composable
private fun SettingsScreen(displayName: String, licenseExpiresMs: Long?) {
    var nsfw by remember { mutableStateOf(Settings.bool("show_nsfw", false)) }
    Column(Modifier.padding(32.dp).width(640.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Text("Tetapan", color = Palette.text, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Section("Lesen") {
            Text("Pengguna: " + displayName.ifBlank { "-" }, color = Palette.text)
            Text("Tamat tempoh: " + (licenseExpiresMs?.let { java.text.SimpleDateFormat("d MMM yyyy, HH:mm").format(java.util.Date(it)) } ?: "Tiada"),
                color = Palette.text)
        }
        Section("Kandungan") {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Tunjuk sumber dewasa (18+)", color = Palette.text, modifier = Modifier.weight(1f))
                Pill(if (nsfw) "Ya" else "Tidak", nsfw) {
                    nsfw = !nsfw
                    Settings.set("show_nsfw", nsfw)
                }
            }
            Text("Extension: ${Plugins.catalog.size} dalam katalog, ${Plugins.providers(true).size} sumber dimuatkan.", color = Palette.muted, fontSize = 13.sp)
        }
        Section("Tentang") {
            Text("CloudXtream untuk Windows ${cx.desktop.core.AppInfo.version}", color = Palette.text)
            Text("Dibina daripada CloudStream (GPL-3.0). Video dimainkan dengan VLC.", color = Palette.muted, fontSize = 13.sp)
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Palette.surface).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, color = Palette.primary, fontWeight = FontWeight.SemiBold)
        content()
        Spacer(Modifier.height(2.dp))
    }
}
