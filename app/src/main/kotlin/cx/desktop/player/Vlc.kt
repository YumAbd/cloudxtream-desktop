package cx.desktop.player

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo
import uk.co.caprica.vlcj.factory.MediaPlayerFactory
import uk.co.caprica.vlcj.factory.discovery.NativeDiscovery
import uk.co.caprica.vlcj.factory.discovery.provider.DiscoveryDirectoryProvider
import uk.co.caprica.vlcj.player.base.MediaPlayer
import uk.co.caprica.vlcj.player.base.MediaPlayerEventAdapter
import uk.co.caprica.vlcj.player.embedded.EmbeddedMediaPlayer
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.BufferFormat
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.BufferFormatCallback
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.RenderCallback
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.format.RV32BufferFormat
import java.io.File
import java.nio.ByteBuffer

/** The VLC copy shipped with the app (resources/vlc), found before any installed VLC. */
class BundledVlcDirectoryProvider : DiscoveryDirectoryProvider {
    override fun priority(): Int = 10
    override fun directories(): Array<String> = listOfNotNull(
        System.getProperty("compose.application.resources.dir")?.let { File(it, "vlc").absolutePath },
        System.getProperty("cx.vlc.dir"),
    ).toTypedArray()
    override fun supported(): Boolean = true
}

object Vlc {
    private val players = mutableListOf<VideoPlayer>()
    private var factory: MediaPlayerFactory? = null

    @Synchronized
    fun factory(): MediaPlayerFactory = factory ?: MediaPlayerFactory(
        NativeDiscovery(),
        "--no-video-title-show", "--no-snapshot-preview", "--quiet", "--http-reconnect",
    ).also { factory = it }

    /** True when libVLC (bundled or installed) can be loaded. */
    val available: Boolean by lazy { runCatching { factory(); true }.getOrDefault(false) }

    @Synchronized fun register(player: VideoPlayer) { players += player }
    @Synchronized fun unregister(player: VideoPlayer) { players -= player }
    @Synchronized fun stopAll() { players.toList().forEach { it.stop() } }
    @Synchronized fun release() {
        players.toList().forEach { it.release() }
        factory?.release()
        factory = null
    }
}

/**
 * libVLC decodes into a memory buffer (RV32 = BGRA) and Compose draws it, so the player controls can
 * be drawn over the video.
 */
class VideoPlayer {
    var frame by mutableStateOf<ImageBitmap?>(null)
        private set
    var frameCount by mutableLongStateOf(0L)
        private set
    var timeMs by mutableLongStateOf(0L)
        private set
    var lengthMs by mutableLongStateOf(0L)
        private set
    var playing by mutableStateOf(false)
        private set
    var buffering by mutableStateOf(true)
        private set
    var ended by mutableStateOf(false)
        private set
    var failed by mutableStateOf(false)
        private set

    private var bitmap: Bitmap? = null
    private var info: ImageInfo? = null
    private var bytes = ByteArray(0)

    private val formatCallback = object : BufferFormatCallback {
        override fun getBufferFormat(sourceWidth: Int, sourceHeight: Int): BufferFormat {
            val i = ImageInfo(sourceWidth, sourceHeight, ColorType.BGRA_8888, ColorAlphaType.OPAQUE)
            val b = Bitmap().apply { allocPixels(i) }
            info = i
            bitmap = b
            bytes = ByteArray(sourceWidth * sourceHeight * 4)
            frame = b.asComposeImageBitmap()
            return RV32BufferFormat(sourceWidth, sourceHeight)
        }
        override fun newFormatSize(bufferWidth: Int, bufferHeight: Int, displayWidth: Int, displayHeight: Int) {}
        override fun allocatedBuffers(buffers: Array<out ByteBuffer>) {}
    }

    private val renderCallback = object : RenderCallback {
        override fun lock(mediaPlayer: MediaPlayer) {}
        override fun unlock(mediaPlayer: MediaPlayer) {}
        override fun display(mediaPlayer: MediaPlayer, nativeBuffers: Array<out ByteBuffer>, bufferFormat: BufferFormat, displayWidth: Int, displayHeight: Int) {
            val b = bitmap ?: return
            val i = info ?: return
            val buffer = nativeBuffers[0]
            buffer.rewind()
            if (buffer.remaining() < bytes.size) return
            buffer.get(bytes)
            b.installPixels(i, bytes, i.width * 4)
            frameCount++
        }
    }

    private val player: EmbeddedMediaPlayer = Vlc.factory().mediaPlayers().newEmbeddedMediaPlayer().apply {
        videoSurface().set(Vlc.factory().videoSurfaces().newVideoSurface(formatCallback, renderCallback, true))
        events().addMediaPlayerEventListener(object : MediaPlayerEventAdapter() {
            override fun playing(mediaPlayer: MediaPlayer) { playing = true; buffering = false }
            override fun paused(mediaPlayer: MediaPlayer) { playing = false }
            override fun stopped(mediaPlayer: MediaPlayer) { playing = false }
            override fun buffering(mediaPlayer: MediaPlayer, newCache: Float) { buffering = newCache < 100f }
            override fun timeChanged(mediaPlayer: MediaPlayer, newTime: Long) { timeMs = newTime; buffering = false }
            override fun lengthChanged(mediaPlayer: MediaPlayer, newLength: Long) { lengthMs = newLength }
            override fun finished(mediaPlayer: MediaPlayer) { playing = false; ended = true }
            override fun error(mediaPlayer: MediaPlayer) { playing = false; failed = true }
        })
    }

    init {
        Vlc.register(this)
    }

    /** [headers]: VLC can send Referer and User-Agent; other headers are not supported by VLC. */
    fun play(url: String, referer: String?, headers: Map<String, String>, startMs: Long) {
        ended = false
        failed = false
        buffering = true
        timeMs = startMs
        lengthMs = 0
        val lower = headers.mapKeys { it.key.lowercase() }
        val options = buildList {
            add(":network-caching=3000")
            (referer?.takeIf { it.isNotBlank() } ?: lower["referer"])?.let { add(":http-referrer=$it") }
            add(":http-user-agent=" + (lower["user-agent"] ?: cx.desktop.ui.Images.USER_AGENT))
            if (startMs > 0) add(":start-time=${startMs / 1000}")
        }
        player.submit { player.media().play(url, *options.toTypedArray()) }
    }

    fun addSubtitle(url: String) {
        player.submit { player.subpictures().setSubTitleUri(url) }
    }

    fun disableSubtitles() {
        player.submit { player.subpictures().setTrack(-1) }
    }

    fun togglePause() {
        player.submit { if (player.status().isPlaying) player.controls().setPause(true) else player.controls().play() }
    }

    fun seekTo(ms: Long) {
        val target = ms.coerceIn(0, (lengthMs - 1000).coerceAtLeast(0))
        timeMs = target
        player.submit { player.controls().setTime(target) }
    }

    fun seekBy(deltaMs: Long) = seekTo(timeMs + deltaMs)

    var volume: Int
        get() = player.audio().volume()
        set(value) { player.submit { player.audio().setVolume(value.coerceIn(0, 150)) } }

    fun stop() {
        player.submit { player.controls().stop() }
    }

    fun release() {
        Vlc.unregister(this)
        runCatching { player.controls().stop() }
        runCatching { player.release() }
    }
}
