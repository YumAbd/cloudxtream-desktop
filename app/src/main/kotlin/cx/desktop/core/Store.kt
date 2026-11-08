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

    @Synchronized fun clear() {
        entries.clear()
        file.writeAtomically(json.writeValueAsBytes(entries))
    }

    @Synchronized fun remove(provider: String, url: String) {
        entries.removeAll { it.provider == provider && it.url == url }
        file.writeAtomically(json.writeValueAsBytes(entries))
    }
}

/** User preferences (Settings screen), with the same defaults as the Android app. */
object Prefs {
    /** Media types shown (General > Preferred media). NSFW is off unless chosen. */
    val allTypes = listOf(
        "Movie" to "Filem", "TvSeries" to "Siri TV", "Anime" to "Anime", "AsianDrama" to "Drama Asia",
        "Cartoon" to "Kartun", "Documentary" to "Dokumentari", "Live" to "Siaran langsung", "NSFW" to "Dewasa (18+)",
    )
    var preferredTypes: Set<String>
        get() = Settings.string("preferred_types")?.split(',')?.filter { it.isNotBlank() }?.toSet()
            ?: allTypes.map { it.first }.filter { it != "NSFW" }.toSet()
        set(value) = Settings.set("preferred_types", value.joinToString(","))

    var downloadDir: java.io.File
        get() = Settings.string("download_dir")?.let(::File)
            ?: File(System.getProperty("user.home"), "Videos${File.separator}CloudXtream")
        set(value) = Settings.set("download_dir", value.absolutePath)

    var autoNext: Boolean
        get() = Settings.bool("auto_next", true)
        set(value) = Settings.set("auto_next", value)

    /** First choice for subtitles: ms, id, en or none. */
    var subtitleLanguage: String
        get() = Settings.string("subtitle_language") ?: "ms"
        set(value) = Settings.set("subtitle_language", value)

    var seekSeconds: Int
        get() = Settings.long("seek_seconds")?.toInt() ?: 10
        set(value) = Settings.set("seek_seconds", value)

    /** Preferred quality: 0 = highest, else the max height (1080, 720, 480). */
    var preferredQuality: Int
        get() = Settings.long("preferred_quality")?.toInt() ?: 0
        set(value) = Settings.set("preferred_quality", value)

    var networkCachingMs: Int
        get() = Settings.long("network_caching")?.toInt() ?: 3000
        set(value) = Settings.set("network_caching", value)

    /** Poster size on Home and lists: 0 small, 1 medium, 2 large. */
    var posterSize: Int
        get() = Settings.long("poster_size")?.toInt() ?: 1
        set(value) = Settings.set("poster_size", value)

    var showContinueWatching: Boolean
        get() = Settings.bool("show_continue", true)
        set(value) = Settings.set("show_continue", value)

    var autoCheckUpdates: Boolean
        get() = Settings.bool("auto_updates", true)
        set(value) = Settings.set("auto_updates", value)
}
