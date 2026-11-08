package cx.desktop.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/** Public app configuration from the CloudXtream server (no secrets). */
object Remote {
    private val http = HttpClient.newBuilder().proxy(java.net.ProxySelector.getDefault()).connectTimeout(Duration.ofSeconds(10)).build()

    /** The Home provider chosen in the admin panel (extension internal name), cached for offline starts. */
    suspend fun defaultProvider(): String? = withContext(Dispatchers.IO) {
        runCatching {
            val request = HttpRequest.newBuilder(URI.create("${Activation.ORIGIN}/api/app/config"))
                .timeout(Duration.ofSeconds(10)).header("Accept", "application/json").GET().build()
            val body = JSONObject(http.send(request, HttpResponse.BodyHandlers.ofString()).body())
            val name = body.optJSONObject("default_provider")?.optString("internal_name")?.takeIf { it.isNotBlank() }
            Settings.set("server_default_provider", name)
            name
        }.getOrElse { Settings.string("server_default_provider") }
    }
}
