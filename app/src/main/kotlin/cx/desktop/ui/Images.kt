package cx.desktop.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import cx.desktop.core.AppDirs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.MessageDigest
import java.time.Duration

/** Posters: memory cache, then disk cache, then network (with the provider's headers). */
object Images {
    private val dir = File(AppDirs.cache, "img").apply { mkdirs() }
    private val http = HttpClient.newBuilder().proxy(java.net.ProxySelector.getDefault()).connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NORMAL).build()
    private val slots = Semaphore(8)
    private val memory = object : LinkedHashMap<String, ImageBitmap>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageBitmap>?) = size > 300
    }

    suspend fun load(url: String, headers: Map<String, String>?): ImageBitmap? {
        synchronized(memory) { memory[url] }?.let { return it }
        val image = withContext(Dispatchers.IO) {
            val key = MessageDigest.getInstance("SHA-1").digest(url.toByteArray()).joinToString("") { "%02x".format(it) }
            val file = File(dir, key)
            val bytes = if (file.exists()) file.readBytes() else slots.withPermit {
                runCatching {
                    val request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(20)).GET()
                        .header("User-Agent", USER_AGENT)
                        .apply { headers?.forEach { (k, v) -> runCatching { header(k, v) } } }.build()
                    val response = http.send(request, HttpResponse.BodyHandlers.ofByteArray())
                    response.body().takeIf { response.statusCode() == 200 && it.isNotEmpty() }?.also { file.writeBytes(it) }
                }.getOrNull()
            } ?: return@withContext null
            runCatching { org.jetbrains.skia.Image.makeFromEncoded(bytes).toComposeImageBitmap() }.getOrNull()
        } ?: return null
        synchronized(memory) { memory[url] = image }
        return image
    }

    const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36"
}

@Composable
fun RemoteImage(url: String?, headers: Map<String, String>?, modifier: Modifier, contentScale: ContentScale = ContentScale.Crop) {
    var image by remember(url) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(url) {
        if (!url.isNullOrBlank() && url.startsWith("http")) image = Images.load(url, headers)
    }
    val current = image
    if (current != null) {
        Image(current, contentDescription = null, modifier = modifier, contentScale = contentScale)
    } else {
        Box(modifier.background(Palette.surfaceHigh))
    }
}
