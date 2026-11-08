package cx.desktop.ui

import androidx.compose.foundation.layout.RowScope
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon

/** Hand cursor over everything that can be clicked. */
fun Modifier.handCursor(): Modifier = pointerHoverIcon(PointerIcon.Hand)

// The Material buttons with the hand cursor; screens use these instead of the material3 ones.

@Composable
fun Button(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: ButtonColors = ButtonDefaults.buttonColors(),
    content: @Composable RowScope.() -> Unit,
) = androidx.compose.material3.Button(onClick, modifier.handCursor(), enabled, colors = colors, content = content)

@Composable
fun TextButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) = androidx.compose.material3.TextButton(onClick, modifier.handCursor(), enabled, content = content)

@Composable
fun OutlinedButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) = androidx.compose.material3.OutlinedButton(onClick, modifier.handCursor(), enabled, content = content)
