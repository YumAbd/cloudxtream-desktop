package cx.desktop.core

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import java.io.File

/** Where CloudXtream keeps its files: %APPDATA%\CloudXtream on Windows. */
object AppDirs {
    val root: File = (System.getenv("APPDATA")?.let { File(it, "CloudXtream") }
        ?: File(System.getProperty("user.home"), ".cloudxtream")).apply { mkdirs() }
    val plugins: File = File(root, "plugins").apply { mkdirs() }
    val cache: File = File(root, "cache").apply { mkdirs() }
}

val json = jacksonObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

/** Writes through a temporary file so a crash never leaves half a file behind. */
fun File.writeAtomically(bytes: ByteArray) {
    val tmp = File(parentFile, "$name.tmp")
    tmp.writeBytes(bytes)
    if (!tmp.renameTo(this)) {
        delete()
        tmp.renameTo(this)
    }
}

/** Small key-value settings kept in settings.json. */
object Settings {
    private val file = File(AppDirs.root, "settings.json")
    private val values: MutableMap<String, Any?> = runCatching { json.readValue<MutableMap<String, Any?>>(file) }
        .getOrElse { mutableMapOf() }

    @Synchronized fun string(key: String): String? = values[key] as? String
    @Synchronized fun long(key: String): Long? = (values[key] as? Number)?.toLong()
    @Synchronized fun bool(key: String, default: Boolean): Boolean = values[key] as? Boolean ?: default
    @Synchronized fun set(key: String, value: Any?) {
        if (value == null) values.remove(key) else values[key] = value
        file.writeAtomically(json.writerWithDefaultPrettyPrinter().writeValueAsBytes(values))
    }
}

/** One title the user started watching; shown in "Continue watching". */
data class WatchEntry(
    val provider: String = "",
    val url: String = "",
    val title: String = "",
    val poster: String? = null,
    val episodeData: String? = null,
    val episodeLabel: String? = null,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val updatedMs: Long = 0,
)

object History {
    private val file = File(AppDirs.root, "history.json")
    private val entries: MutableList<WatchEntry> = runCatching { json.readValue<MutableList<WatchEntry>>(file) }
        .getOrElse { mutableListOf() }

    @Synchronized fun all(): List<WatchEntry> = entries.sortedByDescending { it.updatedMs }
    @Synchronized fun find(provider: String, url: String): WatchEntry? = entries.firstOrNull { it.provider == provider && it.url == url }

    @Synchronized fun save(entry: WatchEntry) {
        entries.removeAll { it.provider == entry.provider && it.url == entry.url }
        // Finished (over 95%) titles leave "Continue watching".
        val finished = entry.durationMs > 0 && entry.positionMs > entry.durationMs * 95 / 100
        if (!finished) entries.add(0, entry.copy(updatedMs = System.currentTimeMillis()))
        while (entries.size > 100) entries.removeAt(entries.size - 1)
        file.writeAtomically(json.writeValueAsBytes(entries))
    }

    @Synchronized fun remove(provider: String, url: String) {
        entries.removeAll { it.provider == provider && it.url == url }
        file.writeAtomically(json.writeValueAsBytes(entries))
    }
}
