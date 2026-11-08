package cx.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import cx.desktop.core.Activation
import cx.desktop.core.Plugins
import cx.desktop.player.Vlc
import cx.desktop.ui.ActivationScreen
import cx.desktop.ui.BlockedScreen
import cx.desktop.ui.CxTheme
import cx.desktop.ui.LoadingScreen
import cx.desktop.ui.MainScreens
import cx.desktop.ui.Navigator
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

fun main() {
    // Use the proxy configured in Windows (or given with -Dhttps.proxyHost) for every connection.
    if (System.getProperty("https.proxyHost") == null) System.setProperty("java.net.useSystemProxies", "true")
    System.setProperty("skiko.renderApi", System.getProperty("skiko.renderApi") ?: "DIRECT3D".takeIf { isWindows } ?: "OPENGL")
    application {
        val window = rememberWindowState(width = 1320.dp, height = 820.dp)
        AppWindow.state = window
        Window(
            onCloseRequest = {
                Vlc.release()
                exitApplication()
            },
            title = "CloudXtream",
            state = window,
            icon = painterResource("icon.png"),
            onPreviewKeyEvent = { event ->
                // F11 toggles full screen everywhere; Esc leaves it.
                val fullscreen = window.placement == WindowPlacement.Fullscreen
                val toggle = event.type == KeyEventType.KeyDown &&
                    (event.key == Key.F11 || (event.key == Key.Escape && fullscreen))
                if (toggle) AppWindow.toggleFullscreen()
                toggle
            },
        ) {
            CxTheme { App() }
        }
    }
}

val isWindows = System.getProperty("os.name").orEmpty().startsWith("Windows")

object AppWindow {
    lateinit var state: WindowState
    private var before = WindowPlacement.Floating
    fun toggleFullscreen() {
        if (state.placement == WindowPlacement.Fullscreen) {
            state.placement = before
        } else {
            before = state.placement
            state.placement = WindowPlacement.Fullscreen
        }
    }
}

private sealed interface Phase {
    data object Checking : Phase
    data class NeedPin(val message: String?) : Phase
    data class Blocked(val message: String) : Phase
    data class Loading(val text: String, val fraction: Float) : Phase
    data class Ready(val displayName: String, val licenseExpiresMs: Long?, val leaseMs: Long) : Phase
}

@Composable
private fun App() {
    var phase by remember { mutableStateOf<Phase>(Phase.Checking) }
    val scope = rememberCoroutineScope()
    val navigator = remember { Navigator() }
    var pluginsLoaded by remember { mutableStateOf(false) }
    var loadWarning by remember { mutableStateOf<String?>(null) }

    fun onRefused(result: Activation.Result.Refused) {
        phase = if (!Activation.isActivated || Activation.needsNewPin(result.code)) {
            if (Activation.needsNewPin(result.code)) Activation.forgetDevice()
            Phase.NeedPin(result.message.takeIf { result.code != "not_activated" })
        } else {
            Phase.Blocked(result.message)
        }
    }

    suspend fun afterAuthorized(result: Activation.Result.Authorized) {
        if (!pluginsLoaded) {
            phase = Phase.Loading("Menyediakan extension…", 0f)
            val summary = Plugins.sync { text, fraction -> phase = Phase.Loading(text, fraction) }
            pluginsLoaded = true
            loadWarning = when {
                summary.loaded == 0 -> "Tiada extension dapat dimuatkan. Semak sambungan Internet dan mulakan semula app."
                summary.offline -> "Tiada sambungan ke server: extension yang sedia ada digunakan."
                else -> null
            }
        }
        phase = Phase.Ready(result.displayName, result.licenseExpiresMs, result.leaseMs)
    }

    suspend fun check() {
        phase = Phase.Checking
        when (val result = Activation.verify()) {
            is Activation.Result.Authorized -> afterAuthorized(result)
            is Activation.Result.Refused -> onRefused(result)
        }
    }

    LaunchedEffect(Unit) {
        if (Activation.isActivated) check() else phase = Phase.NeedPin(null)
    }

    when (val p = phase) {
        Phase.Checking -> LoadingScreen("Menyemak lesen…", null)
        is Phase.Loading -> LoadingScreen(p.text, p.fraction)
        is Phase.NeedPin -> ActivationScreen(p.message) { result ->
            scope.launch { afterAuthorized(result) }
        }
        is Phase.Blocked -> BlockedScreen(p.message) { scope.launch { check() } }
        is Phase.Ready -> {
            MainScreens(navigator, p.displayName, p.licenseExpiresMs, loadWarning)
            // The license is checked again before each lease ends, like the Android app.
            LaunchedEffect(p) {
                delay(p.leaseMs)
                var failures = 0
                while (true) {
                    when (val result = Activation.verify()) {
                        is Activation.Result.Authorized -> {
                            phase = Phase.Ready(result.displayName, result.licenseExpiresMs, result.leaseMs)
                            return@LaunchedEffect
                        }
                        is Activation.Result.Refused -> {
                            // A short network outage should not stop a film; a refusal from the server does.
                            val network = result.code in setOf("DNS_UNREACHABLE", "NETWORK_TIMEOUT", "CONNECTION_FAILED", "IOException")
                            if (!network || ++failures >= 4) {
                                Vlc.stopAll()
                                onRefused(result)
                                return@LaunchedEffect
                            }
                            delay(30_000)
                        }
                    }
                }
            }
        }
    }
}
