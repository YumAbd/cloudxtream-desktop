package cx.desktop.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import cx.desktop.core.Settings

/** App language (Settings > General > Language). Screens recompose when it changes. */
object Lang {
    val choices = listOf("ms" to "Bahasa Melayu", "en" to "English")
    var code by mutableStateOf(Settings.string("language") ?: "ms")
        private set

    fun set(value: String) {
        code = value
        Settings.set("language", value)
    }
}

/** Malay text, or English when the app language is English. */
fun tr(ms: String, en: String): String = if (Lang.code == "en") en else ms
