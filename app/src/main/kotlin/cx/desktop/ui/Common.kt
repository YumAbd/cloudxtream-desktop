package cx.desktop.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ContextMenuArea
import androidx.compose.foundation.ContextMenuItem
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

/** A poster card used in every row and grid. */
data class CardItem(
    val title: String,
    val poster: String?,
    val headers: Map<String, String>?,
    val subtitle: String? = null,
    val progress: Float? = null,
    val badge: String? = null,
    /** Shown as an ✕ on hover and in the right-click menu (e.g. remove from Continue watching). */
    val onRemove: (() -> Unit)? = null,
    val onClick: () -> Unit,
)

@Composable
fun posterScale() = when (cx.desktop.core.Prefs.posterSize) { 0 -> 0.82f; 2 -> 1.2f; else -> 1f }

@Composable
fun PosterCard(item: CardItem, horizontal: Boolean = false) {
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    val zoom by animateFloatAsState(if (hovered) 1.06f else 1f)
    val width = (if (horizontal) 270.dp else 152.dp) * posterScale()
    val height = (if (horizontal) 152.dp else 228.dp) * posterScale()
    val menu = if (item.onRemove != null) listOf(ContextMenuItem(tr("Buang", "Remove")) { item.onRemove.invoke() }) else emptyList()
    ContextMenuArea(items = { menu }) {
        Column(
            Modifier.width(width).hoverable(hover).handCursor().clickable(onClick = item.onClick),
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Box(
                Modifier.size(width, height).scale(zoom).clip(RoundedCornerShape(12.dp))
                    .border(if (hovered) 2.dp else 0.dp, if (hovered) Palette.primary else Color.Transparent, RoundedCornerShape(12.dp))
            ) {
                RemoteImage(item.poster, item.headers, Modifier.size(width, height))
                if (hovered) {
                    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)), contentAlignment = Alignment.Center) {
                        Box(Modifier.size(46.dp).clip(CircleShape).background(Palette.primary), contentAlignment = Alignment.Center) {
                            Icon(Icons.Filled.PlayArrow, null, tint = Color.White, modifier = Modifier.size(30.dp))
                        }
                    }
                }
                item.badge?.let {
                    Text(it, color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(7.dp).clip(RoundedCornerShape(5.dp)).background(Palette.primary).padding(horizontal = 6.dp, vertical = 2.dp))
                }
                if (hovered && item.onRemove != null) {
                    Box(
                        Modifier.align(Alignment.TopEnd).padding(6.dp).size(26.dp).clip(CircleShape)
                            .background(Color.Black.copy(alpha = 0.7f)).handCursor().clickable { item.onRemove.invoke() },
                        contentAlignment = Alignment.Center,
                    ) { Icon(Icons.Filled.Close, tr("Buang", "Remove"), tint = Color.White, modifier = Modifier.size(16.dp)) }
                }
                item.progress?.let { p ->
                    LinearProgressIndicator(
                        progress = { p.coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth().height(4.dp).align(Alignment.BottomCenter),
                        color = Palette.primary,
                        trackColor = Color.Black.copy(alpha = 0.6f),
                    )
                }
            }
            Text(item.title, color = Palette.text, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
            item.subtitle?.let { Text(it, color = Palette.muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
    }
}

/** A titled row of cards with arrows to scroll it (mouse wheel + Shift also scrolls). */
@Composable
fun CardRow(title: String, items: List<CardItem>, horizontal: Boolean = false) {
    val state = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    Column(Modifier.hoverable(hover), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(title, color = Palette.text, fontSize = 19.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 32.dp))
        Box {
            LazyRow(state = state, contentPadding = PaddingValues(horizontal = 32.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                items(items) { PosterCard(it, horizontal) }
            }
            if (hovered && state.canScrollBackward) RowArrow(Icons.Filled.ChevronLeft, Modifier.align(Alignment.CenterStart)) {
                scope.launch { state.animateScrollBy(-900f) }
            }
            if (hovered && state.canScrollForward) RowArrow(Icons.Filled.ChevronRight, Modifier.align(Alignment.CenterEnd)) {
                scope.launch { state.animateScrollBy(900f) }
            }
        }
    }
}

@Composable
private fun RowArrow(icon: androidx.compose.ui.graphics.vector.ImageVector, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier.width(48.dp).fillMaxHeight().background(
            Brush.horizontalGradient(listOf(Palette.background.copy(alpha = 0.9f), Color.Transparent).let {
                if (icon == Icons.Filled.ChevronRight) it.reversed() else it
            })
        ).handCursor().clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, null, tint = Color.White, modifier = Modifier.size(36.dp)) }
}

@Composable
fun Pill(text: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.clip(RoundedCornerShape(50)).background(if (selected) Palette.primary else Palette.surfaceHigh)
            .handCursor().clickable(onClick = onClick).padding(horizontal = 15.dp, vertical = 8.dp)
    ) {
        Text(text, color = if (selected) Color.White else Palette.text.copy(alpha = 0.8f), fontSize = 13.sp)
    }
}

fun formatTime(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}
