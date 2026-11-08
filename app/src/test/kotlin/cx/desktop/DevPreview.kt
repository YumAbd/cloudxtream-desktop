package cx.desktop

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.lagradost.cloudstream3.MainPageRequest
import cx.desktop.core.AppDirs
import cx.desktop.core.Net
import cx.desktop.core.Plugins
import cx.desktop.ui.AccountInfo
import cx.desktop.ui.CxTheme
import cx.desktop.ui.LoadingScreen
import cx.desktop.ui.MainScreens
import cx.desktop.ui.Navigator
import cx.desktop.ui.Screen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URI
import javax.imageio.ImageIO

/**
 * Development only (test sources, never packaged): the screens after activation, without the
 * CloudXtream server. With "--shots <dir>" it also saves screenshots of Home, a details page and
 * Settings, then exits (used by CI).
 */
fun main(args: Array<String>) {
    val shots = args.indexOf("--shots").takeIf { it >= 0 }?.let { File(args[it + 1]).apply { mkdirs() } }
    val cache = File(AppDirs.plugins, "catalog.json")
    if (!cache.exists()) cache.writeBytes(URI("https://raw.githubusercontent.com/YumAbd/cloudstream-leque/apk-build/sumber.json").toURL().readBytes())
    Net.apply()
    application {
        val window = rememberWindowState(width = 1280.dp, height = 760.dp, placement = if (shots != null) WindowPlacement.Maximized else WindowPlacement.Floating)
        AppWindow.state = window
        Window(onCloseRequest = ::exitApplication, title = "CloudXtream (dev)", state = window) {
            CxTheme {
                val navigator = remember { Navigator() }
                var progress by remember { mutableStateOf<Pair<String, Float>?>("…" to 0f) }
                LaunchedEffect(Unit) {
                    val summary = Plugins.sync { t, f -> progress = t to f }
                    println("DEV: loaded ${summary.loaded}/${summary.total}, failures=${summary.failures}")
                    progress = null
                    if (shots == null) return@LaunchedEffect
                    fun capture(name: String) {
                        val r = java.awt.Robot()
                        val bounds = this@Window.window.bounds
                        ImageIO.write(r.createScreenCapture(bounds), "png", File(shots, "$name.png"))
                        println("DEV: screenshot $name")
                    }
                    delay(45_000)
                    capture("home")
                    val api = Plugins.providers(false).firstOrNull { it.name == "Pencurimovie" } ?: Plugins.providers(false).first()
                    val item = runCatching {
                        withContext(Dispatchers.IO) {
                            val page = api.mainPage.first()
                            api.getMainPage(1, MainPageRequest(page.name, page.data, page.horizontalImages))?.items?.flatMap { it.list }?.firstOrNull()
                        }
                    }.getOrNull()
                    if (item != null) {
                        navigator.open(Screen.Details(api, item.url, item.name, item.posterUrl))
                        delay(25_000)
                        capture("details")
                    }
                    navigator.root(Screen.Settings())
                    delay(3_000)
                    capture("settings")
                    exitApplication()
                }
                val p = progress
                if (p != null) LoadingScreen(p.first, p.second) else MainScreens(navigator, AccountInfo("Dev", null) {}, null)
            }
        }
    }
}
