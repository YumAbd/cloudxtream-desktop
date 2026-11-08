package cx.desktop.core

import com.fasterxml.jackson.module.kotlin.readValue
import java.io.File

/** Library lists, like the Android app: watch status, favourites and subscriptions. */
enum class WatchStatus(val label: String) {
    WATCHING("Sedang ditonton"),
    COMPLETED("Selesai"),
    ONHOLD("Ditangguhkan"),
    PLAN("Akan ditonton"),
    DROPPED("Dihentikan"),
}

data class LibraryEntry(
    val provider: String = "",
    val url: String = "",
    val title: String = "",
    val poster: String? = null,
    val type: String? = null,
    val status: WatchStatus? = null,
    val favourite: Boolean = false,
    val subscribed: Boolean = false,
    val updatedMs: Long = 0,
)

object Library {
    private val file = File(AppDirs.root, "library.json")
    private val entries: MutableList<LibraryEntry> = runCatching { json.readValue<MutableList<LibraryEntry>>(file) }
        .getOrElse { mutableListOf() }

    @Synchronized fun all(): List<LibraryEntry> = entries.sortedByDescending { it.updatedMs }
    @Synchronized fun get(provider: String, url: String): LibraryEntry? = entries.firstOrNull { it.provider == provider && it.url == url }

    @Synchronized fun update(base: LibraryEntry, change: (LibraryEntry) -> LibraryEntry): LibraryEntry {
        val current = get(base.provider, base.url) ?: base
        val next = change(current).copy(updatedMs = System.currentTimeMillis())
        entries.removeAll { it.provider == base.provider && it.url == base.url }
        if (next.status != null || next.favourite || next.subscribed) entries.add(next)
        file.writeAtomically(json.writeValueAsBytes(entries))
        return next
    }
}
