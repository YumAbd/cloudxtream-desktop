package cx.desktop

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import cx.desktop.core.AppDirs
import cx.desktop.core.Plugins
import cx.desktop.ui.CxTheme
import cx.desktop.ui.LoadingScreen
import cx.desktop.ui.MainScreens
import cx.desktop.ui.Navigator
import java.io.File
import java.net.URI

/**
 * Development only (test sources, never packaged): shows the screens after activation, with the
 * catalog read from GitHub, for machines that cannot reach the CloudXtream server.
 */
fun main() {
    val cache = File(AppDirs.plugins, "catalog.json")
    if (!cache.exists()) cache.writeBytes(URI("https://raw.githubusercontent.com/YumAbd/cloudstream-leque/apk-build/sumber.json").toURL().readBytes())
    application {
        val window = rememberWindowState(width = 1320.dp, height = 820.dp)
        AppWindow.state = window
        Window(onCloseRequest = ::exitApplication, title = "CloudXtream (dev)", state = window) {
            CxTheme {
                var progress by remember { mutableStateOf<Pair<String, Float>?>("Memuatkan…" to 0f) }
                LaunchedEffect(Unit) {
                    val summary = Plugins.sync { t, f -> progress = t to f }
                    println("DEV: loaded ${summary.loaded}/${summary.total}, failures=${summary.failures}")
                    progress = null
                }
                val p = progress
                if (p != null) LoadingScreen(p.first, p.second) else MainScreens(remember { Navigator() }, cx.desktop.ui.AccountInfo("Dev", null) {}, null)
            }
        }
    }
}
