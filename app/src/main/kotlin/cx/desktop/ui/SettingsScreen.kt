package cx.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cx.desktop.core.AppInfo
import cx.desktop.core.Logs
import cx.desktop.core.Plugins
import cx.desktop.core.Prefs
import cx.desktop.core.Updates
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date

private fun sections() = listOf(
    "general" to tr("Umum", "General"),
    "player" to tr("Pemain", "Player"),
    "layout" to tr("Susun atur", "Layout"),
    "updates" to tr("Kemas kini", "Updates"),
    "account" to tr("Akaun", "Account"),
    "about" to tr("Tentang", "About"),
)

/** Settings grouped like the Android app: General, Player, Layout, Updates, plus account and about. */
@Composable
fun SettingsScreen(section: String, account: AccountInfo, onSection: (String) -> Unit) {
    Row(Modifier.fillMaxSize()) {
        Column(
            Modifier.width(220.dp).fillMaxHeight().padding(vertical = 20.dp, horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(tr("Tetapan", "Settings"), color = Palette.text, fontSize = 24.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(12.dp))
            sections().forEach { (id, label) ->
                Text(
                    label,
                    color = if (id == section) Palette.text else Palette.muted,
                    fontWeight = if (id == section) FontWeight.SemiBold else FontWeight.Normal,
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                        .background(if (id == section) Palette.primary.copy(alpha = 0.22f) else Palette.background)
                        .handCursor().clickable { onSection(id) }.padding(horizontal = 14.dp, vertical = 11.dp),
                )
            }
        }
        Column(
            Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(24.dp).widthIn(max = 760.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            when (section) {
                "general" -> General()
                "player" -> PlayerSettings()
                "layout" -> Layout()
                "updates" -> UpdatesSettings()
                "account" -> Account(account)
                else -> About()
            }
        }
    }
}

@Composable
private fun Group(title: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Palette.surface).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(title, color = Palette.primary, fontWeight = FontWeight.SemiBold)
        content()
    }
}

@Composable
private fun Toggle(title: String, detail: String?, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Palette.text)
            detail?.let { Text(it, color = Palette.muted, fontSize = 12.sp) }
        }
        Switch(value, onChange, Modifier.handCursor(), colors = SwitchDefaults.colors(checkedTrackColor = Palette.primary))
    }
}

@Composable
private fun <T> Choice(title: String, options: List<Pair<T, String>>, value: T, onChange: (T) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, color = Palette.text)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { (v, label) -> Pill(label, v == value) { onChange(v) } }
        }
    }
}

@Composable
private fun General() {
    var types by remember { mutableStateOf(Prefs.preferredTypes) }
    var dir by remember { mutableStateOf(Prefs.downloadDir) }
    var confirmAdult by remember { mutableStateOf(false) }
    Group(tr("Bahasa", "Language")) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Lang.choices.forEach { (code, label) -> Pill(label, Lang.code == code) { Lang.set(code) } }
        }
    }
    Group(tr("Media pilihan", "Preferred media")) {
        Text(tr("Jenis kandungan yang dipaparkan di Utama dan Cari. Sumber lain disembunyikan.", "Content types shown in Home and Search. Other sources are hidden."), color = Palette.muted, fontSize = 12.sp)
        Prefs.allTypes.forEach { (id, _) ->
            val label = if (id == "NSFW") "NSFW" else typeLabel(id)
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().handCursor().clickable {
                if (id == "NSFW" && id !in types) confirmAdult = true
                else { types = if (id in types) types - id else types + id; Prefs.preferredTypes = types }
            }) {
                Checkbox(id in types, onCheckedChange = null, colors = CheckboxDefaults.colors(checkedColor = Palette.primary))
                Text(label, color = Palette.text, modifier = Modifier.padding(start = 8.dp))
            }
        }
        Text(tr("Perubahan berkuat kuasa apabila anda kembali ke Utama.", "Changes apply when you go back to Home."), color = Palette.muted, fontSize = 12.sp)
    }
    Group(tr("Muat turun", "Downloads")) {
        Text(tr("Folder: ", "Folder: ") + dir.absolutePath, color = Palette.text)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                val chooser = javax.swing.JFileChooser(dir).apply {
                    fileSelectionMode = javax.swing.JFileChooser.DIRECTORIES_ONLY
                    dialogTitle = tr("Pilih folder muat turun", "Choose the downloads folder")
                }
                if (chooser.showOpenDialog(null) == javax.swing.JFileChooser.APPROVE_OPTION) {
                    dir = chooser.selectedFile
                    Prefs.downloadDir = dir
                }
            }) { Text(tr("Tukar folder…", "Change folder…")) }
            TextButton(onClick = { openFolder(dir) }) { Text(tr("Buka folder", "Open folder")) }
        }
    }
    var dns by remember { mutableStateOf(cx.desktop.core.Settings.string("dns") ?: "google") }
    Group("DNS over HTTPS") {
        Text(tr("Elak sekatan DNS ISP pada sumber filem (sama seperti app Android). Google disyorkan.",
            "Avoids ISP DNS blocks on movie sources (like the Android app). Google is recommended."), color = Palette.muted, fontSize = 12.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            cx.desktop.core.Net.dnsChoices.forEach { (id, label) ->
                Pill(if (id == "system") tr("DNS sistem (ISP)", "System DNS (ISP)") else label, dns == id) {
                    dns = id
                    cx.desktop.core.Settings.set("dns", id)
                    cx.desktop.core.Net.apply()
                }
            }
        }
    }
    var cleared by remember { mutableStateOf<String?>(null) }
    Group(tr("Data", "Data")) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { cx.desktop.core.History.clear(); cleared = tr("Sejarah tontonan dikosongkan.", "Watch history cleared.") }) {
                Text(tr("Kosongkan \"Teruskan menonton\"", "Clear \"Continue watching\""))
            }
            TextButton(onClick = { cx.desktop.core.Settings.set("recent_searches", null); cleared = tr("Carian terkini dikosongkan.", "Recent searches cleared.") }) {
                Text(tr("Kosongkan carian terkini", "Clear recent searches"))
            }
            cleared?.let { Text(it, color = Palette.muted, fontSize = 12.sp) }
        }
    }
    if (confirmAdult) AlertDialog(
        onDismissRequest = { confirmAdult = false },
        title = { Text("NSFW") },
        text = { Text(tr("Paparkan sumber NSFW (kandungan dewasa 18+)? Pastikan PC ini tidak digunakan oleh kanak-kanak.", "Show NSFW sources (adult 18+ content)? Make sure children do not use this PC.")) },
        confirmButton = { TextButton(onClick = { types = types + "NSFW"; Prefs.preferredTypes = types; confirmAdult = false }) { Text(tr("Ya, paparkan", "Yes, show")) } },
        dismissButton = { TextButton(onClick = { confirmAdult = false }) { Text(tr("Batal", "Cancel")) } },
    )
}

@Composable
private fun PlayerSettings() {
    var autoNext by remember { mutableStateOf(Prefs.autoNext) }
    var subs by remember { mutableStateOf(Prefs.subtitleLanguage) }
    var seek by remember { mutableStateOf(Prefs.seekSeconds) }
    var quality by remember { mutableStateOf(Prefs.preferredQuality) }
    var caching by remember { mutableStateOf(Prefs.networkCachingMs) }
    Group(tr("Main balik", "Playback")) {
        Toggle(tr("Episod seterusnya secara automatik", "Play next episode automatically"), tr("Mainkan episod berikutnya apabila episod tamat.", "Starts the next episode when one ends."), autoNext) { autoNext = it; Prefs.autoNext = it }
        Choice(tr("Kualiti pilihan", "Preferred quality"), listOf(0 to tr("Tertinggi", "Highest"), 1080 to "1080p", 720 to "720p", 480 to "480p"), quality) { quality = it; Prefs.preferredQuality = it }
        Choice(tr("Langkau (anak panah / butang)", "Seek step (arrows / buttons)"), listOf(5 to "5 s", 10 to "10 s", 15 to "15 s", 30 to "30 s"), seek) { seek = it; Prefs.seekSeconds = it }
        Choice(tr("Penimbal rangkaian", "Network buffer"), listOf(1500 to tr("Cepat", "Fast"), 3000 to tr("Biasa", "Normal"), 8000 to tr("Internet perlahan", "Slow internet")), caching) { caching = it; Prefs.networkCachingMs = it }
    }
    Group(tr("Sarikata", "Subtitles")) {
        Choice(tr("Bahasa sarikata pilihan", "Preferred subtitle language"), listOf("ms" to "Melayu", "id" to "Indonesia", "en" to tr("Inggeris", "English"), "none" to tr("Tiada", "None")), subs) { subs = it; Prefs.subtitleLanguage = it }
        Text(tr("Jika bahasa pilihan tiada, bahasa lain dicuba mengikut turutan Melayu → Indonesia → Inggeris.", "If it is missing, Malay → Indonesian → English are tried."), color = Palette.muted, fontSize = 12.sp)
    }
    Group(tr("Papan kekunci", "Keyboard")) {
        Text(tr("Ruang: main/jeda · ←/→: langkau · ↑/↓: kelantangan · F atau F11: skrin penuh · Esc: keluar skrin penuh · Backspace: kembali", "Space: play/pause · ←/→: seek · ↑/↓: volume · F or F11: full screen · Esc: leave full screen · Backspace: back"),
            color = Palette.muted, fontSize = 13.sp)
    }
}

@Composable
private fun Layout() {
    var size by remember { mutableStateOf(Prefs.posterSize) }
    var cont by remember { mutableStateOf(Prefs.showContinueWatching) }
    Group(tr("Paparan", "Display")) {
        Choice(tr("Saiz poster", "Poster size"), listOf(0 to tr("Kecil", "Small"), 1 to tr("Sederhana", "Medium"), 2 to tr("Besar", "Large")), size) { size = it; Prefs.posterSize = it }
        Toggle(tr("Tunjuk \"Teruskan menonton\" di Utama", "Show \"Continue watching\" on Home"), null, cont) { cont = it; Prefs.showContinueWatching = it }
    }
}

@Composable
private fun UpdatesSettings() {
    val scope = rememberCoroutineScope()
    var auto by remember { mutableStateOf(Prefs.autoCheckUpdates) }
    var checking by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var syncing by remember { mutableStateOf<String?>(null) }
    var summary by remember { mutableStateOf(Plugins.lastSummary) }
    Group(tr("Aplikasi", "App")) {
        Text(tr("Versi semasa: ", "Current version: ") + AppInfo.version, color = Palette.text)
        Toggle(tr("Semak versi baharu secara automatik", "Check for new versions automatically"), null, auto) { auto = it; Prefs.autoCheckUpdates = it }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(enabled = !checking, onClick = {
                checking = true
                scope.launch {
                    val r = Updates.check()
                    status = if (r == null) tr("Anda menggunakan versi terkini.", "You have the latest version.") else tr("Versi ${r.version} tersedia: mulakan semula app untuk memasangnya.", "Version ${r.version} is available: restart the app to install it.")
                    checking = false
                }
            }) { Text(tr("Semak sekarang", "Check now")) }
            status?.let { Text(it, color = Palette.muted, fontSize = 13.sp) }
        }
    }
    Group(tr("Extension", "Extensions")) {
        val s = summary
        Text(
            if (s == null) tr("Extension belum dimuatkan.", "Extensions are not loaded yet.") else tr("${s.loaded} daripada ${s.total} extension dimuatkan · ${Plugins.providers(true).size} sumber.", "${s.loaded} of ${s.total} extensions loaded · ${Plugins.providers(true).size} sources."),
            color = Palette.text,
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(enabled = syncing == null, onClick = {
                syncing = tr("Menyemak…", "Checking…")
                scope.launch {
                    summary = Plugins.sync { text, _ -> syncing = text }
                    syncing = null
                }
            }) { Text(tr("Kemas kini extension sekarang", "Update extensions now")) }
            syncing?.let {
                CircularProgressIndicator(Modifier.width(18.dp).height(18.dp), strokeWidth = 2.dp)
                Text(it, color = Palette.muted, fontSize = 13.sp)
            }
        }
        s?.failures?.takeIf { it.isNotEmpty() }?.let { failures ->
            Spacer(Modifier.height(4.dp))
            Text(tr("Tidak dapat dimuatkan:", "Could not be loaded:"), color = Palette.error, fontSize = 13.sp)
            failures.forEach { Text("• ${it.name}: ${it.reason}", color = Palette.muted, fontSize = 12.sp) }
        }
    }
}

@Composable
private fun Account(account: AccountInfo) {
    var confirm by remember { mutableStateOf(false) }
    Group(tr("Lesen", "License")) {
        Text(tr("Pengguna: ", "User: ") + account.displayName.ifBlank { "-" }, color = Palette.text)
        Text(tr("Tamat tempoh: ", "Expires: ") + (account.licenseExpiresMs?.let { SimpleDateFormat("d MMM yyyy, HH:mm").format(Date(it)) } ?: tr("Tiada (tanpa had)", "Never")),
            color = Palette.text)
        Text(tr("Peranti ini: ", "This device: ") + (System.getenv("COMPUTERNAME")?.let { "Windows PC ($it)" } ?: "Windows PC"), color = Palette.text)
        Text(tr("Untuk menambah atau mengeluarkan peranti, hubungi admin CloudXtream.", "To add or remove devices, contact the CloudXtream admin."), color = Palette.muted, fontSize = 12.sp)
    }
    Group(tr("Tukar PIN", "Change PIN")) {
        Text(tr("Gunakan PIN lain pada PC ini (contohnya lesen baharu). Anda perlu memasukkan PIN semula.", "Use another PIN on this PC (for example a new license). You will enter a PIN again."), color = Palette.muted, fontSize = 13.sp)
        Button(onClick = { confirm = true }) { Text(tr("Log keluar PC ini", "Sign out this PC")) }
    }
    if (confirm) AlertDialog(
        onDismissRequest = { confirm = false },
        title = { Text(tr("Log keluar?", "Sign out?")) },
        text = { Text(tr("CloudXtream akan meminta PIN semula. Slot peranti dalam lesen lama hanya boleh dikeluarkan oleh admin CloudXtream.", "CloudXtream will ask for a PIN again. Only the CloudXtream admin can free this device slot in the old license.")) },
        confirmButton = { TextButton(onClick = { confirm = false; account.onSignOut() }) { Text(tr("Log keluar", "Sign out")) } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text(tr("Batal", "Cancel")) } },
    )
}

@Composable
private fun About() {
    Group(tr("CloudXtream untuk Windows", "CloudXtream for Windows")) {
        Text(tr("Versi ", "Version ") + AppInfo.version, color = Palette.text)
        Text(tr("Dibina daripada CloudStream (GPL-3.0). Video dimainkan dengan VLC (libVLC).", "Built on CloudStream (GPL-3.0). Video plays with VLC (libVLC)."), color = Palette.muted, fontSize = 13.sp)
        Text(tr("Data aplikasi: ", "App data: ") + cx.desktop.core.AppDirs.root.absolutePath, color = Palette.muted, fontSize = 12.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { openFolder(Logs.file.parentFile) }) { Text(tr("Buka folder log", "Open logs folder")) }
        }
    }
}
