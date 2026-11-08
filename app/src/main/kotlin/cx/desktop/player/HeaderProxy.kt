package cx.desktop.player

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import cx.desktop.core.Net
import okhttp3.Request
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Every stream VLC plays goes through this local server (127.0.0.1 only). It fetches with the app's
 * HTTP client, so names are resolved with DNS over HTTPS like the extensions (ISP DNS blocks), and it
 * adds the headers VLC cannot send (Origin, Cookie, tokens). HLS playlists are rewritten so their
 * segments and keys also come through here.
 */
object HeaderProxy {
    private val sessions = ConcurrentHashMap<String, Map<String, String>>()
    private val server: HttpServer by lazy {
        HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 64).apply {
            executor = Executors.newCachedThreadPool { r -> Thread(r, "cx-proxy").apply { isDaemon = true } }
            createContext("/p") { exchange ->
                runCatching { handle(exchange) }.onFailure {
                    System.err.println("proxy: " + it)
                    runCatching { exchange.sendResponseHeaders(502, -1) }
                }
                exchange.close()
            }
            start()
        }
    }

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
        val headers = sessions[session] ?: run { exchange.sendResponseHeaders(403, -1); return }
        val target = query["u"]?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
            ?: run { exchange.sendResponseHeaders(400, -1); return }
        val request = Request.Builder().url(target).apply {
            headers.forEach { (k, v) -> runCatching { header(k, v) } }
            exchange.requestHeaders.getFirst("Range")?.let { header("Range", it) }
            if (headers.keys.none { it.equals("user-agent", true) }) header("User-Agent", cx.desktop.ui.Images.USER_AGENT)
        }.build()
        Net.client.newCall(request).execute().use { response ->
            val body = response.body
            val type = response.header("Content-Type").orEmpty()
            val finalUrl = response.request.url.toString()
            val playlist = type.contains("mpegurl", true) || response.request.url.encodedPath.endsWith(".m3u8", true)
            if (playlist) {
                val text = body.string()
                val bytes = rewrite(text, URI.create(finalUrl), session).toByteArray(Charsets.UTF_8)
                exchange.responseHeaders.add("Content-Type", "application/vnd.apple.mpegurl")
                exchange.sendResponseHeaders(response.code, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
                return
            }
            listOf("Content-Type", "Content-Range", "Accept-Ranges").forEach { name ->
                response.header(name)?.let { exchange.responseHeaders.add(name, it) }
            }
            val length = response.header("Content-Length")?.toLongOrNull() ?: 0L
            exchange.sendResponseHeaders(response.code, if (length > 0) length else 0)
            body.byteStream().use { input -> exchange.responseBody.use { runCatching { input.transferTo(it) } } }
        }
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
