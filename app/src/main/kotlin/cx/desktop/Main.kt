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
import cx.desktop.core.Logs
import cx.desktop.core.Net
import cx.desktop.core.Plugins
import cx.desktop.player.Vlc
import cx.desktop.ui.AccountInfo
import cx.desktop.ui.ActivationScreen
import cx.desktop.ui.BlockedScreen
import cx.desktop.ui.CxTheme
import cx.desktop.ui.LoadingScreen
import cx.desktop.ui.MainScreens
import cx.desktop.ui.Navigator
import cx.desktop.ui.tr
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

fun main(args: Array<String>) {
    // Use the proxy configured in Windows (or given with -Dhttps.proxyHost) for every connection.
    if (System.getProperty("https.proxyHost") == null) System.setProperty("java.net.useSystemProxies", "true")
    if (args.firstOrNull() == "--self-test") {
        val report = java.io.File(args.getOrElse(1) { "cloudxtream-selftest.txt" })
        report.writeText("")
        val ok = runCatching { SelfTest.run(java.io.File(report.parentFile, "selftest-frame.png")) { report.appendText(it + "\n") } }
            .onFailure { report.appendText("SMOKE crashed: " + Plugins.describe(it) + "\n") }.getOrDefault(false)
        report.appendText(if (ok) "SELFTEST OK\n" else "SELFTEST FAILED\n")
        Vlc.release()
        kotlin.system.exitProcess(if (ok) 0 else 1)
    }
    Logs.start()
    Net.apply()
    Thread.setDefaultUncaughtExceptionHandler { t, e -> System.err.println("Uncaught in ${t.name}: " + e.stackTraceToString().take(4000)) }
    System.setProperty("skiko.renderApi", System.getProperty("skiko.renderApi") ?: "DIRECT3D".takeIf { isWindows } ?: "OPENGL")
    application {
        val window = rememberWindowState(width = 1320.dp, height = 820.dp)
        AppWindow.state = window
        // An error inside a screen must not close CloudXtream: it is logged, and the window is
        // rebuilt (Home), keeping the activation and loaded extensions.
        var restarts by remember { mutableStateOf(0) }
        @OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
        androidx.compose.runtime.CompositionLocalProvider(
            androidx.compose.ui.window.LocalWindowExceptionHandlerFactory provides androidx.compose.ui.window.WindowExceptionHandlerFactory { _ ->
                androidx.compose.ui.window.WindowExceptionHandler { e ->
                    System.err.println("Screen error: " + e.stackTraceToString().take(6000))
                    Vlc.stopAll()
                    AppState.lastError = tr("Ralat berlaku dan skrin dimuat semula", "An error occurred and the screen was reloaded") + ": " + Plugins.describe(e)
                    restarts++
                }
            },
        ) {
        androidx.compose.runtime.key(restarts) {
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
    }
}

/** Survives a window rebuild after an error. */
object AppState {
    var authorized: Activation.Result.Authorized? = null
    var extensionsLoaded = false
    var loadWarning: String? = null
    var lastError: String? = null
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
    var pluginsLoaded by remember { mutableStateOf(AppState.extensionsLoaded) }
    var loadWarning by remember { mutableStateOf(AppState.lastError ?: AppState.loadWarning) }

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
            phase = Phase.Loading(tr("Memuatkan CloudXtream…", "Loading CloudXtream…") + " 0%", 0f)
            val summary = Plugins.sync { _, fraction ->
                phase = Phase.Loading(tr("Memuatkan CloudXtream…", "Loading CloudXtream…") + " ${(fraction * 100).toInt()}%", fraction)
            }
            pluginsLoaded = true
            AppState.extensionsLoaded = true
            loadWarning = when {
                summary.loaded == 0 -> tr("Tiada extension dapat dimuatkan. Lihat Tetapan → Kemas kini → Extension untuk sebabnya.", "No extension could be loaded. See Settings → Updates → Extensions for the reason.")
                summary.offline -> tr("Tiada sambungan ke server: extension yang sedia ada digunakan.", "No connection to the server: using the extensions already installed.")
                else -> null
            }
        }
        AppState.loadWarning = loadWarning
        AppState.authorized = result
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
        val known = AppState.authorized
        AppState.lastError = null
        when {
            known != null && pluginsLoaded -> phase = Phase.Ready(known.displayName, known.licenseExpiresMs, known.leaseMs)
            Activation.isActivated -> check()
            else -> phase = Phase.NeedPin(null)
        }
    }

    when (val p = phase) {
        Phase.Checking -> LoadingScreen(tr("Menyemak lesen…", "Checking the license…"), null)
        is Phase.Loading -> LoadingScreen(p.text, p.fraction)
        is Phase.NeedPin -> ActivationScreen(p.message) { result ->
            scope.launch { afterAuthorized(result) }
        }
        is Phase.Blocked -> BlockedScreen(p.message) { scope.launch { check() } }
        is Phase.Ready -> {
            MainScreens(navigator, AccountInfo(p.displayName, p.licenseExpiresMs) {
                Vlc.stopAll()
                Activation.forgetDevice()
                navigator.home()
                phase = Phase.NeedPin(null)
            }, loadWarning)
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
