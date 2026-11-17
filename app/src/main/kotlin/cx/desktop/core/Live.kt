package cx.desktop.core

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.MessageDigest
import java.time.Duration

/** One channel of a playlist. [url] "youtube:@handle" is an official YouTube channel. */
data class LiveChannel(
    val name: String,
    val url: String,
    val logo: String?,
    val group: String,
    val userAgent: String? = null,
    val referer: String? = null,
    val tvgId: String? = null,
    /** DRM from #KODIPROP (not playable with VLC). */
    val drm: String? = null,
) {
    val isYoutube get() = url.startsWith("youtube:")
    val key get() = url

    fun toJson(): JSONObject = JSONObject().put("name", name).put("url", url).put("logo", logo).put("group", group)
        .put("ua", userAgent).put("ref", referer).put("tid", tvgId).put("drm", drm)

    companion object {
        private fun JSONObject.str(k: String) = if (isNull(k)) null else optString(k).takeIf { it.isNotEmpty() && it != "null" }
        fun fromJson(o: JSONObject) = LiveChannel(o.optString("name"), o.optString("url"), o.str("logo"), o.optString("group"),
            o.str("ua"), o.str("ref"), o.str("tid"), o.str("drm"))
    }
}

/** A playlist from the admin (via the server) or the user's own on this PC. */
data class LiveSource(
    val id: String,
    val name: String,
    val kind: String,
    val url: String? = null,
    val server: String? = null,
    val username: String? = null,
    val password: String? = null,
    val text: String? = null,
    val own: Boolean = false,
    val media: String = "tv",
) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("name", name).put("kind", kind).put("media", media).put("url", url)
        .put("server", server).put("username", username).put("password", password).put("text", text).put("own", own)

    companion object {
        private fun JSONObject.str(k: String) = if (isNull(k)) null else optString(k).takeIf { it.isNotEmpty() }
        fun fromJson(o: JSONObject, own: Boolean) = LiveSource(
            id = o.optString("id"), name = o.optString("name"), kind = o.optString("kind"),
            url = o.str("url"), server = o.str("server"), username = o.str("username"), password = o.str("password"),
            text = o.str("text"), own = own, media = if (o.optString("media") == "radio") "radio" else "tv",
        )
    }
}

class LiveProblem(message: String) : Exception(message)

/** Live TV and Radio: sources from CloudXtream Control, the user's own playlists, favourites and recent. */
object Live {
    private const val REFRESH_MS = 12 * 3600_000L
    private const val MAX_CHANNELS = 200_000
    private val dir = File(AppDirs.root, "live").apply { mkdirs() }
    private val http = HttpClient.newBuilder().proxy(java.net.ProxySelector.getDefault())
        .followRedirects(HttpClient.Redirect.NORMAL).connectTimeout(Duration.ofSeconds(15)).build()
    private val PLAYER_AGENTS = listOf(
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36",
        "OTT Navigator/1.7.1.3 (Linux;Android 12; en; 1x7m7bt)",
        "Kodi/20.2 (Linux; Android 12) Android/12.0.0 Sys_CPU/aarch64 App_Bitness/64 Version/20.2",
        "TiviMate/4.7.0 (Android 12)",
    )
    val RADIO_MALAYSIA = LiveSource("radio-browser-my", "Radio Malaysia", "radio_browser", media = "radio")

    // ---------------- server ----------------
    data class Config(val allowOwn: Boolean, val startScreen: String, val admin: List<LiveSource>)

    /** Sources from the server; the last answer when offline. */
    fun config(fresh: Boolean): Config {
        if (fresh) runCatching {
            Settings.set("live_config", Activation.signedPost("/api/device/live").toString())
            Settings.set("live_config_ms", System.currentTimeMillis())
        }.onFailure { System.err.println("live: sources not refreshed (" + it.javaClass.simpleName + ")") }
        val o = Settings.string("live_config")?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return Config(false, "movie", emptyList())
        val list = o.optJSONArray("sources") ?: JSONArray()
        return Config(o.optBoolean("allow_user_playlists", false), if (o.optString("start_screen") == "live") "live" else "movie",
            (0 until list.length()).mapNotNull { list.optJSONObject(it) }.map { LiveSource.fromJson(it, false) })
    }

    /** At start-up: the server's answer is refreshed at most every 6 hours. */
    fun refreshIfOld() {
        if (System.currentTimeMillis() - (Settings.long("live_config_ms") ?: 0L) > 6 * 3600_000L) config(true)
    }

    /** The section the app opens on: the user's choice, else the admin's ("home" or "live"). */
    fun startScreen(): String = Settings.string("start_screen")?.takeIf { it in setOf("home", "live", "radio") }
        ?: if (config(false).startScreen == "live") "live" else "home"

    // ---------------- own playlists ----------------
    fun ownSources(): List<LiveSource> {
        val a = Settings.string("live_own")?.let { runCatching { JSONArray(it) }.getOrNull() } ?: return emptyList()
        return (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map { LiveSource.fromJson(it, true) }
    }
    private fun saveOwn(list: List<LiveSource>) = Settings.set("live_own", JSONArray(list.map { it.toJson() }).toString())
    fun addOwn(source: LiveSource) = saveOwn(ownSources().filter { it.id != source.id } + source)
    fun removeOwn(id: String) = saveOwn(ownSources().filter { it.id != id })
    fun newId() = "own-" + java.util.UUID.randomUUID().toString()

    // ---------------- favourites and recent ----------------
    private fun readList(key: String): List<LiveChannel> {
        val a = Settings.string(key)?.let { runCatching { JSONArray(it) }.getOrNull() } ?: return emptyList()
        return (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map(LiveChannel::fromJson)
    }
    private fun writeList(key: String, list: List<LiveChannel>) = Settings.set(key, JSONArray(list.map { it.toJson() }).toString())
    fun favourites(scope: String) = readList("live_fav_$scope")
    fun toggleFavourite(c: LiveChannel, scope: String): Boolean {
        val list = favourites(scope)
        val now = list.none { it.key == c.key }
        writeList("live_fav_$scope", if (now) list + c else list.filter { it.key != c.key })
        return now
    }
    fun recent(scope: String) = readList("live_recent_$scope")
    fun addRecent(c: LiveChannel, scope: String) = writeList("live_recent_$scope", (listOf(c) + recent(scope).filter { it.key != c.key }).take(30))

    // ---------------- adult categories ----------------
    private val ADULT = Regex("(?i)(\\bxxx\\b|\\badults?\\b|18\\s*\\+|\\+\\s*18\\b|\\bporn|\\bsex|\\berotic|\\bnsfw\\b|\\bdewasa\\b|playboy|hustler|brazzers|redlight|\\bblue\\s*movie)")
    fun isAdult(c: LiveChannel) = ADULT.containsMatchIn(c.group) || ADULT.containsMatchIn(c.name)
    var showAdult: Boolean
        get() = Settings.bool("live_show_adult", false)
        set(v) = Settings.set("live_show_adult", v)

    // ---------------- channels ----------------
    private fun sha(s: String) = MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }.take(24)

    /** Channels of a source; downloaded playlists are kept 12 hours (and used offline). */
    fun channels(source: LiveSource, fresh: Boolean): List<LiveChannel> {
        if (source.kind == "m3u_text") return parseM3u(source.text.orEmpty())
        val stamp = listOf(source.id, source.url, source.server, source.username, source.password).joinToString("|")
        val cache = File(dir, sha(stamp) + ".json")
        if (!fresh && cache.isFile && System.currentTimeMillis() - cache.lastModified() < REFRESH_MS) {
            runCatching { return fromCache(cache.readText()) }
        }
        return try {
            val list = when (source.kind) {
                "xtream" -> xtream(source.server.orEmpty(), source.username.orEmpty(), source.password.orEmpty())
                "radio_browser" -> radioBrowser("MY")
                else -> fromLink(source.url.orEmpty())
            }
            cache.writeText(JSONArray(list.map { it.toJson() }).toString())
            list
        } catch (e: Exception) {
            if (cache.isFile) runCatching { return fromCache(cache.readText()) }
            throw e
        }
    }
    private fun fromCache(text: String): List<LiveChannel> {
        val a = JSONArray(text)
        return (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map(LiveChannel::fromJson)
    }

    private fun get(url: String, agent: String, timeoutS: Long = 90): HttpResponse<String> =
        http.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(timeoutS)).header("User-Agent", agent).GET().build(),
            HttpResponse.BodyHandlers.ofString())

    private fun enc(s: String) = URLEncoder.encode(s, Charsets.UTF_8)
    private fun query(uri: URI): Map<String, String> = uri.rawQuery.orEmpty().split('&').filter { it.contains('=') }
        .associate { java.net.URLDecoder.decode(it.substringBefore('='), Charsets.UTF_8) to java.net.URLDecoder.decode(it.substringAfter('='), Charsets.UTF_8) }

    data class XtreamLogin(val server: String, val username: String, val password: String)

    /** A full Xtream link (get.php / player_api.php with username and password), else null. */
    fun xtreamFromLink(link: String): XtreamLogin? {
        val uri = runCatching { URI(link.trim()) }.getOrNull() ?: return null
        val q = query(uri)
        val user = q["username"] ?: return null
        val pass = q["password"] ?: return null
        val path = uri.path.orEmpty()
        if (!path.endsWith("get.php") && !path.endsWith("player_api.php") && !path.endsWith("xmltv.php")) return null
        val port = if (uri.port > 0) ":" + uri.port else ""
        return XtreamLogin(uri.scheme + "://" + uri.host + port, user, pass)
    }

    /** A playlist link in any form (also shortlinks and full Xtream links); IPTV-player agents are tried in turn. */
    fun fromLink(link: String): List<LiveChannel> {
        val url = link.trim()
        if (!url.startsWith("http://") && !url.startsWith("https://")) throw LiveProblem("Link mesti bermula dengan http:// atau https://")
        xtreamFromLink(url)?.let { return xtream(it.server, it.username, it.password) }
        var lastCode = 0
        var lastError: Exception? = null
        for (agent in PLAYER_AGENTS) {
            val response = try { get(url, agent) } catch (e: Exception) { lastError = e; continue }
            val finalUrl = response.uri().toString()
            if (finalUrl != url) xtreamFromLink(finalUrl)?.let { return xtream(it.server, it.username, it.password) }
            lastCode = response.statusCode()
            if (lastCode !in 200..299) continue
            val body = response.body()
            if (!body.trimStart().startsWith("#EXTM3U") && !body.contains("#EXTINF")) continue
            val list = parseM3u(body)
            if (list.isNotEmpty()) return list
        }
        if (lastCode == 0 && lastError != null) throw LiveProblem("Playlist tidak dapat dibuka (" + lastError.javaClass.simpleName + "). Semak link dan Internet.")
        if (lastCode !in 200..299 && lastCode != 0) throw LiveProblem("Playlist tidak dapat dibuka (HTTP $lastCode).")
        throw LiveProblem("Link ini bukan playlist (tiada saluran ditemui).")
    }

    /** Live channels through the Xtream Codes API. */
    fun xtream(serverIn: String, username: String, password: String): List<LiveChannel> {
        val server = serverIn.trim().trimEnd('/')
        if (!server.startsWith("http://") && !server.startsWith("https://")) throw LiveProblem("Server Xtream mesti bermula dengan http:// atau https://")
        val base = server + "/player_api.php?username=" + enc(username) + "&password=" + enc(password)
        val agent = PLAYER_AGENTS[1]
        val info = try { JSONObject(get(base, agent, 60).body()) } catch (e: Exception) {
            throw LiveProblem("Server Xtream tidak dapat dihubungi atau jawapannya tidak sah.")
        }
        val user = info.optJSONObject("user_info") ?: throw LiveProblem("Jawapan server Xtream tidak sah.")
        if (user.optString("auth") != "1" && user.optInt("auth", 0) != 1) throw LiveProblem("Username atau password Xtream salah.")
        val status = user.optString("status")
        if (status.isNotEmpty() && !status.equals("Active", true)) throw LiveProblem("Akaun Xtream tidak aktif ($status).")
        val categories = runCatching { JSONArray(get("$base&action=get_live_categories", agent, 60).body()) }.getOrElse { JSONArray() }
        val names = (0 until categories.length()).mapNotNull { categories.optJSONObject(it) }
            .associate { it.optString("category_id") to it.optString("category_name") }
        val streams = try { JSONArray(get("$base&action=get_live_streams", agent, 120).body()) } catch (e: Exception) {
            throw LiveProblem("Senarai saluran Xtream tidak dapat dimuat turun.")
        }
        return (0 until minOf(streams.length(), MAX_CHANNELS)).mapNotNull { streams.optJSONObject(it) }.map { s ->
            LiveChannel(
                name = s.optString("name").trim().ifEmpty { "Saluran " + s.optString("stream_id") },
                url = "$server/live/" + enc(username) + "/" + enc(password) + "/" + s.optString("stream_id") + ".m3u8",
                logo = s.optString("stream_icon").takeIf { it.startsWith("http") },
                group = names[s.optString("category_id")]?.takeIf { it.isNotBlank() } ?: "Lain-lain",
                tvgId = s.optString("epg_channel_id").takeIf { it.isNotBlank() && it != "null" },
            )
        }.also { if (it.isEmpty()) throw LiveProblem("Akaun Xtream ini tiada saluran live.") }
    }

    /** Working stations of a country from Radio Browser, most voted first; grouped by state. */
    fun radioBrowser(country: String): List<LiveChannel> {
        val path = "/json/stations/search?countrycode=$country&hidebroken=true&order=votes&reverse=true&limit=400"
        val text = runCatching { get("https://de1.api.radio-browser.info$path", "CloudXtream/1.0", 60).body() }
            .getOrElse { get("https://all.api.radio-browser.info$path", "CloudXtream/1.0", 60).body() }
        val a = JSONArray(text)
        return (0 until a.length()).mapNotNull { a.optJSONObject(it) }.mapNotNull { s ->
            val url = s.optString("url_resolved").ifEmpty { s.optString("url") }.takeIf { it.startsWith("http") } ?: return@mapNotNull null
            LiveChannel(s.optString("name").trim(), url, s.optString("favicon").takeIf { it.startsWith("http") },
                s.optString("state").trim().ifEmpty { "Malaysia" })
        }.distinctBy { it.url }
    }

    private val attr = Regex("""([A-Za-z0-9_-]+)="([^"]*)"""")

    /** M3U: #EXTINF attributes, #EXTGRP, #EXTVLCOPT user agent / referrer, "url|User-Agent=..&Referer=..", #KODIPROP DRM. */
    fun parseM3u(text: String): List<LiveChannel> {
        val out = ArrayList<LiveChannel>()
        var name: String? = null; var logo: String? = null; var group: String? = null; var tid: String? = null
        var ua: String? = null; var ref: String? = null; var grp: String? = null; var drm: String? = null
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            when {
                line.startsWith("#EXTINF", true) -> {
                    val attrs = attr.findAll(line).associate { it.groupValues[1].lowercase() to it.groupValues[2] }
                    var quoted = false; var comma = -1
                    for (i in line.indices) { val ch = line[i]; if (ch == '"') quoted = !quoted else if (ch == ',' && !quoted) { comma = i; break } }
                    name = (if (comma >= 0) line.substring(comma + 1) else "").trim().ifEmpty { attrs["tvg-name"] }
                    logo = attrs["tvg-logo"]?.takeIf { it.startsWith("http") }
                    group = attrs["group-title"]?.takeIf { it.isNotBlank() }
                    tid = attrs["tvg-id"]?.takeIf { it.isNotBlank() }
                }
                line.startsWith("#EXTGRP:", true) -> grp = line.substringAfter(':').trim().takeIf { it.isNotEmpty() }
                line.startsWith("#KODIPROP:", true) -> if (line.contains("license_type", true)) drm = "drm"
                line.startsWith("#EXTVLCOPT:", true) -> {
                    val opt = line.substringAfter(':')
                    val key = opt.substringBefore('=').trim().lowercase(); val value = opt.substringAfter('=', "").trim()
                    if (key == "http-user-agent") ua = value
                    if (key == "http-referrer" || key == "http-referer") ref = value
                }
                line.startsWith("#") -> {}
                else -> {
                    var url = line
                    if (url.contains('|')) {
                        val opts = url.substringAfter('|'); url = url.substringBefore('|')
                        for (pair in opts.split('&')) {
                            val k = pair.substringBefore('=').trim().lowercase()
                            val v = runCatching { java.net.URLDecoder.decode(pair.substringAfter('=', ""), Charsets.UTF_8) }.getOrDefault("").trim()
                            if (k == "user-agent") ua = v
                            if (k == "referer" || k == "referrer") ref = v
                        }
                    }
                    if (url.startsWith("http://") || url.startsWith("https://") || url.startsWith("youtube:")) {
                        out += LiveChannel(name?.takeIf { it.isNotBlank() } ?: "Saluran ${out.size + 1}", url, logo,
                            group ?: grp ?: "Lain-lain", ua, ref, tid, drm)
                        if (out.size >= MAX_CHANNELS) break
                    }
                    name = null; logo = null; group = null; ua = null; ref = null; tid = null; drm = null
                }
            }
        }
        return out
    }

    /** Category name key: "Malaysia", "MALAYSIA " and "MALAYSIA" are one; "A;B" is in both. */
    private val spaces = Regex("[\\s\\u00A0\\u200B\\uFEFF]+")
    fun groupKey(g: String) = g.replace(spaces, " ").trim().uppercase()
    fun groupsOf(c: LiveChannel): List<Pair<String, String>> {
        val parts = c.group.split(';', '|').map { it.replace(spaces, " ").trim() }.filter { it.isNotEmpty() }
        return parts.ifEmpty { listOf(c.group.trim()) }.map { groupKey(it) to it }.distinctBy { it.first }
    }
}
