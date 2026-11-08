package cx.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** A poster card used in every row. */
data class CardItem(
    val title: String,
    val poster: String?,
    val headers: Map<String, String>?,
    val subtitle: String? = null,
    val progress: Float? = null,
    val onClick: () -> Unit,
)

@Composable
fun PosterCard(item: CardItem, horizontal: Boolean = false) {
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    val width = if (horizontal) 260.dp else 150.dp
    val height = if (horizontal) 146.dp else 225.dp
    Column(
        Modifier.width(width).hoverable(hover).clickable(onClick = item.onClick),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.size(width, height).scale(if (hovered) 1.04f else 1f).clip(RoundedCornerShape(10.dp))) {
            RemoteImage(item.poster, item.headers, Modifier.size(width, height))
            item.progress?.let { p ->
                LinearProgressIndicator(
                    progress = { p.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().height(4.dp).align(Alignment.BottomCenter),
                    color = Palette.primary,
                    trackColor = Palette.surfaceHigh,
                )
            }
        }
        Text(item.title, color = if (hovered) Palette.text else Palette.text.copy(alpha = 0.9f), fontSize = 13.sp,
            maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
        item.subtitle?.let { Text(it, color = Palette.muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
    }
}

@Composable
fun CardRow(title: String, items: List<CardItem>, horizontal: Boolean = false) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(title, color = Palette.text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 24.dp))
        LazyRow(contentPadding = PaddingValues(horizontal = 24.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            items(items) { PosterCard(it, horizontal) }
        }
    }
}

@Composable
fun Pill(text: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.clip(RoundedCornerShape(50)).background(if (selected) Palette.primary else Palette.surfaceHigh)
            .clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 7.dp)
    ) {
        Text(text, color = if (selected) Palette.text else Palette.muted, fontSize = 13.sp)
    }
}

fun formatTime(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}
