package cx.desktop.core

import com.lagradost.cloudstream3.app
import okhttp3.Cache
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.dnsoverhttps.DnsOverHttps
import java.io.File
import java.net.InetAddress
import java.util.concurrent.TimeUnit

/**
 * The HTTP client used by the extensions, posters, downloads and the video proxy. Like the Android
 * app (where CloudXtream defaults to Google), names are resolved with DNS over HTTPS, so ISP DNS
 * blocks do not break the sources.
 */
object Net {
    val dnsChoices = listOf(
        "google" to "Google",
        "cloudflare" to "Cloudflare",
        "adguard" to "AdGuard",
        "quad9" to "Quad9",
        "system" to "DNS sistem (ISP)",
    )
    private val servers = mapOf(
        "google" to ("https://dns.google/dns-query" to listOf("8.8.4.4", "8.8.8.8")),
        "cloudflare" to ("https://cloudflare-dns.com/dns-query" to listOf("1.1.1.1", "1.0.0.1")),
        "adguard" to ("https://dns.adguard.com/dns-query" to listOf("94.140.14.140", "94.140.14.141")),
        "quad9" to ("https://dns.quad9.net/dns-query" to listOf("9.9.9.9", "149.112.112.112")),
    )

    @Volatile var client: OkHttpClient = build()
        private set

    private fun build(): OkHttpClient {
        val base = OkHttpClient.Builder()
            .followRedirects(true)
            .followSslRedirects(true)
            .proxySelector(java.net.ProxySelector.getDefault())
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .cache(Cache(File(AppDirs.cache, "http"), 50L * 1024 * 1024))
            .build()
        val server = servers[Settings.string("dns") ?: "google"] ?: return base
        val doh = DnsOverHttps.Builder()
            .client(base)
            .url(server.first.toHttpUrl())
            .bootstrapDnsHosts(server.second.map { InetAddress.getByName(it) })
            .build()
        return base.newBuilder().dns(doh).build()
    }

    /** Called at start and when the DNS setting changes; extensions use the library's shared client. */
    fun apply() {
        client = build()
        app.baseClient = client
    }
}
