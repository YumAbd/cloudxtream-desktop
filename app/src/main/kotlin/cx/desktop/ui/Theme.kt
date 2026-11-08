package cx.desktop.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

object Palette {
    val background = Color(0xFF0E1116)
    val surface = Color(0xFF161B22)
    val surfaceHigh = Color(0xFF222934)
    val primary = Color(0xFF3D8BFF)
    val text = Color(0xFFE8EDF3)
    val muted = Color(0xFF9AA6B2)
    val error = Color(0xFFFF6B6B)
    val success = Color(0xFF3CCB7F)
}

@Composable
fun CxTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Palette.primary,
            onPrimary = Color.White,
            background = Palette.background,
            onBackground = Palette.text,
            surface = Palette.surface,
            onSurface = Palette.text,
            surfaceVariant = Palette.surfaceHigh,
            onSurfaceVariant = Palette.muted,
            error = Palette.error,
        ),
        content = content,
    )
}
