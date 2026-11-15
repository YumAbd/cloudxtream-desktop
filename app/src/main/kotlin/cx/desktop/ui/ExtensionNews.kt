package cx.desktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cx.desktop.core.CatalogEntry
import cx.desktop.core.Plugins
import cx.desktop.core.Settings
import cx.desktop.core.json
import com.fasterxml.jackson.module.kotlin.readValue

/**
 * Extensions the admin added or removed since this PC last showed them, listed once with what each
 * contains (e.g. "Oppadrama — Kdrama & Kmovies"). NSFW extensions are never listed. On a fresh
 * install the current catalog is only recorded.
 */
object ExtensionNews {
    private const val KEY = "extension_news_seen"
    // Added with 1.1.3: a PC activated before this version is told about them once.
    private val FIRST_NEWS = setOf("OppaDrama", "OneTouchTV", "NontonDrakor", "Kissasian")
    data class News(val added: List<Pair<String, String>>, val removed: List<String>, val current: Map<String, String>)

    private val typeWords = linkedMapOf(
        "AsianDrama" to "Drama Asia", "Movie" to "Movies", "TvSeries" to "TV Series", "Anime" to "Anime",
        "AnimeMovie" to "Anime Movies", "Cartoon" to "Cartoon", "Documentary" to "Documentary", "Live" to "Live TV",
    )
    private fun about(entry: CatalogEntry): String = entry.summary?.trim()?.take(80)?.takeIf { it.isNotEmpty() }
        ?: entry.tvTypes.orEmpty().mapNotNull { typeWords[it] }.distinct().take(3).joinToString(" & ")

    /** Null when there is nothing to show. */
    fun pending(catalog: List<CatalogEntry> = Plugins.catalog): News? {
        val visible = catalog.filter { (it.status ?: 1) == 1 && it.tvTypes?.contains("NSFW") != true }
        if (visible.isEmpty()) return null
        val current = visible.associate { it.internalName to (it.name?.takeIf { n -> n.isNotBlank() } ?: it.internalName) }
        val saved = Settings.string(KEY)?.let { runCatching { json.readValue<Map<String, String>>(it) }.getOrNull() }
            ?: run {
                // A new install (activated with this version) has nothing "new"; a PC updated from an
                // earlier version is shown the extensions added together with this version.
                val baseline = if (Settings.long("activated_ms") != null) current else current - FIRST_NEWS
                Settings.set(KEY, json.writeValueAsString(baseline))
                baseline
            }
        val added = visible.filter { it.internalName !in saved }.map { (current.getValue(it.internalName)) to about(it) }
        val removed = saved.filterKeys { it !in current }.values.toList()
        return if (added.isEmpty() && removed.isEmpty()) null else News(added, removed, current)
    }

    fun markSeen(news: News) = Settings.set(KEY, json.writeValueAsString(news.current))
}

@Composable
fun ExtensionNewsDialog() {
    var news by remember { mutableStateOf(ExtensionNews.pending()) }
    val current = news ?: return
    val close = { ExtensionNews.markSeen(current); news = null }
    AlertDialog(
        onDismissRequest = close,
        title = { Text(tr("Kemas kini extension", "Extension updates")) },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (current.added.isNotEmpty()) {
                    Text(tr("Extension baharu:", "New extensions:"), color = Palette.text, fontWeight = FontWeight.SemiBold)
                    current.added.forEach { (name, about) ->
                        Text("• " + name + (if (about.isNotBlank()) " — $about" else ""), color = Palette.text, fontSize = 14.sp)
                    }
                }
                if (current.removed.isNotEmpty()) {
                    if (current.added.isNotEmpty()) Text("", fontSize = 6.sp)
                    Text(tr("Extension dibuang:", "Removed extensions:"), color = Palette.text, fontWeight = FontWeight.SemiBold)
                    current.removed.forEach { Text("• $it", color = Palette.muted, fontSize = 14.sp) }
                }
            }
        },
        confirmButton = { TextButton(onClick = close) { Text("OK") } },
    )
}
