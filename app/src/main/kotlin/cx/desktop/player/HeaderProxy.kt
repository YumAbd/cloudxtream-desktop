package cx.desktop.player

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * VLC can only send Referer and User-Agent. Some sources need more (Origin, Cookie, tokens), so their
 * requests go through this local server (127.0.0.1 only), which adds the headers. HLS playlists are
 * rewritten so their segments and keys also come through here.
 */
object HeaderProxy {
    private val sessions = ConcurrentHashMap<String, Map<String, String>>()
    private val http = HttpClient.newBuilder().proxy(java.net.ProxySelector.getDefault())
        .connectTimeout(Duration.ofSeconds(15)).followRedirects(HttpClient.Redirect.NORMAL).build()
    private val server: HttpServer by lazy {
        HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 64).apply {
            executor = Executors.newCachedThreadPool { r -> Thread(r, "cx-proxy").apply { isDaemon = true } }
            createContext("/p") { exchange -> runCatching { handle(exchange) }.onFailure { runCatching { exchange.sendResponseHeaders(502, -1) }; exchange.close() } }
            start()
        }
    }

    /** Headers VLC sends itself; anything else needs the proxy. */
    fun needed(headers: Map<String, String>): Boolean =
        headers.keys.any { it.lowercase() !in setOf("referer", "user-agent") }

    /** A local URL that fetches [url] with [headers]. */
    fun wrap(url: String, headers: Map<String, String>): String {
        val session = UUID.randomUUID().toString().take(12)
        sessions[session] = headers
        return local(url, session)
    }

    private fun local(url: String, session: String) =
        "http://127.0.0.1:${server.address.port}/p?s=$session&u=" + URLEncoder.encode(url, Charsets.UTF_8)

    private fun handle(exchange: HttpExchange) {
        val query = exchange.requestURI.rawQuery.orEmpty().split('&').associate {
            it.substringBefore('=') to URLDecoder.decode(it.substringAfter('=', ""), Charsets.UTF_8)
        }
        val session = query["s"].orEmpty()
        val headers = sessions[session] ?: run { exchange.sendResponseHeaders(403, -1); exchange.close(); return }
        val target = query["u"]?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
            ?: run { exchange.sendResponseHeaders(400, -1); exchange.close(); return }
        val request = HttpRequest.newBuilder(URI.create(target)).timeout(Duration.ofSeconds(30)).GET().apply {
            headers.forEach { (k, v) -> runCatching { header(k, v) } }
            exchange.requestHeaders.getFirst("Range")?.let { header("Range", it) }
            if (headers.keys.none { it.equals("user-agent", true) }) header("User-Agent", cx.desktop.ui.Images.USER_AGENT)
        }.build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofInputStream())
        val type = response.headers().firstValue("Content-Type").orElse("")
        val playlist = type.contains("mpegurl", true) || response.uri().path.endsWith(".m3u8", true)
        if (playlist) {
            val text = response.body().use { String(it.readBytes(), Charsets.UTF_8) }
            val bytes = rewrite(text, response.uri(), session).toByteArray(Charsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "application/vnd.apple.mpegurl")
            exchange.sendResponseHeaders(response.statusCode(), bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
            return
        }
        listOf("Content-Type", "Content-Range", "Accept-Ranges").forEach { name ->
            response.headers().firstValue(name).ifPresent { exchange.responseHeaders.add(name, it) }
        }
        val length = response.headers().firstValueAsLong("Content-Length").orElse(0L)
        exchange.sendResponseHeaders(response.statusCode(), if (length > 0) length else 0)
        response.body().use { input -> exchange.responseBody.use { input.transferTo(it) } }
    }

    private val uriAttribute = Regex("URI=\"([^\"]+)\"")

    /** Every segment, key and variant URL of the playlist goes through the proxy too. */
    private fun rewrite(text: String, base: URI, session: String): String = text.lines().joinToString("\n") { line ->
        when {
            line.isBlank() -> line
            line.startsWith("#") -> uriAttribute.replace(line) { m -> "URI=\"" + local(base.resolve(m.groupValues[1].trim()).toString(), session) + "\"" }
            else -> local(base.resolve(line.trim()).toString(), session)
        }
    }
}
