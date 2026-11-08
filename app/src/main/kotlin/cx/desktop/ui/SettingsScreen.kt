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
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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

private val sections = listOf(
    "general" to "Umum",
    "player" to "Pemain",
    "layout" to "Susun atur",
    "updates" to "Kemas kini",
    "account" to "Akaun",
    "about" to "Tentang",
)

/** Settings grouped like the Android app: General, Player, Layout, Updates, plus account and about. */
@Composable
fun SettingsScreen(section: String, account: AccountInfo, onSection: (String) -> Unit) {
    Row(Modifier.fillMaxSize()) {
        Column(
            Modifier.width(220.dp).fillMaxHeight().padding(vertical = 20.dp, horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text("Tetapan", color = Palette.text, fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(12.dp))
            sections.forEach { (id, label) ->
                Text(
                    label,
                    color = if (id == section) Palette.text else Palette.muted,
                    fontWeight = if (id == section) FontWeight.SemiBold else FontWeight.Normal,
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                        .background(if (id == section) Palette.primary.copy(alpha = 0.22f) else Palette.background)
                        .clickable { onSection(id) }.padding(horizontal = 14.dp, vertical = 11.dp),
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
        Switch(value, onChange, colors = SwitchDefaults.colors(checkedTrackColor = Palette.primary))
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
    Group("Media pilihan") {
        Text("Jenis kandungan yang dipaparkan di Utama dan Cari. Sumber lain disembunyikan.", color = Palette.muted, fontSize = 12.sp)
        Prefs.allTypes.forEach { (id, label) ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable {
                if (id == "NSFW" && id !in types) confirmAdult = true
                else { types = if (id in types) types - id else types + id; Prefs.preferredTypes = types }
            }) {
                Checkbox(id in types, onCheckedChange = null, colors = CheckboxDefaults.colors(checkedColor = Palette.primary))
                Text(label, color = Palette.text, modifier = Modifier.padding(start = 8.dp))
            }
        }
        Text("Perubahan berkuat kuasa apabila anda kembali ke Utama.", color = Palette.muted, fontSize = 12.sp)
    }
    Group("Muat turun") {
        Text("Folder: " + dir.absolutePath, color = Palette.text)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                val chooser = javax.swing.JFileChooser(dir).apply {
                    fileSelectionMode = javax.swing.JFileChooser.DIRECTORIES_ONLY
                    dialogTitle = "Pilih folder muat turun"
                }
                if (chooser.showOpenDialog(null) == javax.swing.JFileChooser.APPROVE_OPTION) {
                    dir = chooser.selectedFile
                    Prefs.downloadDir = dir
                }
            }) { Text("Tukar folder…") }
            TextButton(onClick = { openFolder(dir) }) { Text("Buka folder") }
        }
    }
    if (confirmAdult) AlertDialog(
        onDismissRequest = { confirmAdult = false },
        title = { Text("Kandungan dewasa") },
        text = { Text("Paparkan sumber kandungan dewasa (18+)? Pastikan PC ini tidak digunakan oleh kanak-kanak.") },
        confirmButton = { TextButton(onClick = { types = types + "NSFW"; Prefs.preferredTypes = types; confirmAdult = false }) { Text("Ya, paparkan") } },
        dismissButton = { TextButton(onClick = { confirmAdult = false }) { Text("Batal") } },
    )
}

@Composable
private fun PlayerSettings() {
    var autoNext by remember { mutableStateOf(Prefs.autoNext) }
    var subs by remember { mutableStateOf(Prefs.subtitleLanguage) }
    var seek by remember { mutableStateOf(Prefs.seekSeconds) }
    var quality by remember { mutableStateOf(Prefs.preferredQuality) }
    var caching by remember { mutableStateOf(Prefs.networkCachingMs) }
    Group("Main balik") {
        Toggle("Episod seterusnya secara automatik", "Mainkan episod berikutnya apabila episod tamat.", autoNext) { autoNext = it; Prefs.autoNext = it }
        Choice("Kualiti pilihan", listOf(0 to "Tertinggi", 1080 to "1080p", 720 to "720p", 480 to "480p"), quality) { quality = it; Prefs.preferredQuality = it }
        Choice("Langkau (anak panah / butang)", listOf(5 to "5 s", 10 to "10 s", 15 to "15 s", 30 to "30 s"), seek) { seek = it; Prefs.seekSeconds = it }
        Choice("Penimbal rangkaian", listOf(1500 to "Cepat", 3000 to "Biasa", 8000 to "Internet perlahan"), caching) { caching = it; Prefs.networkCachingMs = it }
    }
    Group("Sarikata") {
        Choice("Bahasa sarikata pilihan", listOf("ms" to "Melayu", "id" to "Indonesia", "en" to "Inggeris", "none" to "Tiada"), subs) { subs = it; Prefs.subtitleLanguage = it }
        Text("Jika bahasa pilihan tiada, bahasa lain dicuba mengikut turutan Melayu → Indonesia → Inggeris.", color = Palette.muted, fontSize = 12.sp)
    }
    Group("Papan kekunci") {
        Text("Ruang: main/jeda · ←/→: langkau · ↑/↓: kelantangan · F atau F11: skrin penuh · Esc: keluar skrin penuh · Backspace: kembali",
            color = Palette.muted, fontSize = 13.sp)
    }
}

@Composable
private fun Layout() {
    var size by remember { mutableStateOf(Prefs.posterSize) }
    var cont by remember { mutableStateOf(Prefs.showContinueWatching) }
    Group("Paparan") {
        Choice("Saiz poster", listOf(0 to "Kecil", 1 to "Sederhana", 2 to "Besar"), size) { size = it; Prefs.posterSize = it }
        Toggle("Tunjuk \"Teruskan menonton\" di Utama", null, cont) { cont = it; Prefs.showContinueWatching = it }
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
    Group("Aplikasi") {
        Text("Versi semasa: ${AppInfo.version}", color = Palette.text)
        Toggle("Semak versi baharu secara automatik", null, auto) { auto = it; Prefs.autoCheckUpdates = it }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(enabled = !checking, onClick = {
                checking = true
                scope.launch {
                    val r = Updates.check()
                    status = if (r == null) "Anda menggunakan versi terkini." else "Versi ${r.version} tersedia: lihat sepanduk di atas atau mulakan semula app."
                    checking = false
                }
            }) { Text("Semak sekarang") }
            status?.let { Text(it, color = Palette.muted, fontSize = 13.sp) }
        }
    }
    Group("Extension") {
        val s = summary
        Text(
            if (s == null) "Extension belum dimuatkan." else "${s.loaded} daripada ${s.total} extension dimuatkan · ${Plugins.providers(true).size} sumber.",
            color = Palette.text,
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(enabled = syncing == null, onClick = {
                syncing = "Menyemak…"
                scope.launch {
                    summary = Plugins.sync { text, _ -> syncing = text }
                    syncing = null
                }
            }) { Text("Kemas kini extension sekarang") }
            syncing?.let {
                CircularProgressIndicator(Modifier.width(18.dp).height(18.dp), strokeWidth = 2.dp)
                Text(it, color = Palette.muted, fontSize = 13.sp)
            }
        }
        s?.failures?.takeIf { it.isNotEmpty() }?.let { failures ->
            Spacer(Modifier.height(4.dp))
            Text("Tidak dapat dimuatkan:", color = Palette.error, fontSize = 13.sp)
            failures.forEach { Text("• ${it.name}: ${it.reason}", color = Palette.muted, fontSize = 12.sp) }
        }
    }
}

@Composable
private fun Account(account: AccountInfo) {
    var confirm by remember { mutableStateOf(false) }
    Group("Lesen") {
        Text("Pengguna: " + account.displayName.ifBlank { "-" }, color = Palette.text)
        Text("Tamat tempoh: " + (account.licenseExpiresMs?.let { SimpleDateFormat("d MMM yyyy, HH:mm").format(Date(it)) } ?: "Tiada (tanpa had)"),
            color = Palette.text)
        Text("Peranti ini: " + (System.getenv("COMPUTERNAME")?.let { "Windows PC ($it)" } ?: "Windows PC"), color = Palette.text)
        Text("Untuk menambah atau mengeluarkan peranti, hubungi admin CloudXtream.", color = Palette.muted, fontSize = 12.sp)
    }
    Group("Tukar PIN") {
        Text("Gunakan PIN lain pada PC ini (contohnya lesen baharu). Anda perlu memasukkan PIN semula.", color = Palette.muted, fontSize = 13.sp)
        Button(onClick = { confirm = true }) { Text("Log keluar PC ini") }
    }
    if (confirm) AlertDialog(
        onDismissRequest = { confirm = false },
        title = { Text("Log keluar?") },
        text = { Text("CloudXtream akan meminta PIN semula. Slot peranti dalam lesen lama hanya boleh dikeluarkan oleh admin CloudXtream.") },
        confirmButton = { TextButton(onClick = { confirm = false; account.onSignOut() }) { Text("Log keluar") } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text("Batal") } },
    )
}

@Composable
private fun About() {
    Group("CloudXtream untuk Windows") {
        Text("Versi ${AppInfo.version}", color = Palette.text)
        Text("Dibina daripada CloudStream (GPL-3.0). Video dimainkan dengan VLC (libVLC).", color = Palette.muted, fontSize = 13.sp)
        Text("Data aplikasi: ${cx.desktop.core.AppDirs.root.absolutePath}", color = Palette.muted, fontSize = 12.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { openFolder(Logs.file.parentFile) }) { Text("Buka folder log") }
        }
    }
}
