package com.biskyt.mytvplayer.ui.common

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import com.biskyt.mytvplayer.ui.theme.LocalAccent
import com.biskyt.mytvplayer.ui.theme.Palette

private val FocusRing = Border(BorderStroke(3.dp, Palette.text))

/**
 * The app's one clickable surface: neutral background, white ring when focused, optional selected state in accent.
 * No focus scale animation by default — re-laying out rows on every focus change is costly on slow boxes.
 */
@Composable
fun TvCard(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    selected: Boolean = false,
    shape: Shape = RoundedCornerShape(14.dp),
    color: Color = Palette.surface,
    focusedColor: Color = Palette.surfaceHigh,
    content: @Composable () -> Unit
) {
    val accent = LocalAccent.current
    Surface(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = modifier,
        shape = ClickableSurfaceDefaults.shape(shape),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (selected) accent.tint else color,
            focusedContainerColor = if (selected) accent.tint else focusedColor,
            pressedContainerColor = focusedColor,
            contentColor = Palette.text,
            focusedContentColor = Palette.text
        ),
        border = ClickableSurfaceDefaults.border(focusedBorder = FocusRing)
    ) { content() }
}

/** Pill-shaped button. [primary] fills it with the accent colour. */
@Composable
fun PillButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    selected: Boolean = false,
    leading: (@Composable () -> Unit)? = null
) {
    val accent = LocalAccent.current
    val bg = when {
        primary -> accent.color
        selected -> Palette.text
        else -> Palette.surfaceHigh
    }
    val fg = when {
        primary -> accent.onAccent
        selected -> Palette.background
        else -> Palette.text
    }
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(50)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = bg,
            focusedContainerColor = bg,
            contentColor = fg,
            focusedContentColor = fg
        ),
        border = ClickableSurfaceDefaults.border(focusedBorder = FocusRing)
    ) {
        Row(Modifier.padding(horizontal = 20.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            if (leading != null) {
                leading()
                Box(Modifier.width(8.dp))
            }
            Text(text, style = MaterialTheme.typography.labelLarge, color = fg, maxLines = 1)
        }
    }
}

/** Thin progress bar drawn in one pass (no nested layouts). */
@Composable
fun ProgressLine(fraction: Float, modifier: Modifier = Modifier, height: Dp = 5.dp) {
    val accent = LocalAccent.current.color
    Box(
        modifier.height(height).fillMaxWidth().drawBehind {
            val r = CornerRadius(size.height / 2)
            drawRoundRect(Palette.track, cornerRadius = r)
            val f = fraction.coerceIn(0f, 1f)
            if (f > 0f) drawRoundRect(accent, size = Size(size.width * f, size.height), cornerRadius = r)
        }
    )
}

/**
 * Channel logo with an initials placeholder underneath, so rows look complete before (or without) the image.
 * Images are decoded at the displayed size to keep memory low.
 */
@Composable
fun ChannelLogo(url: String?, name: String, width: Dp = 72.dp, height: Dp = 40.dp) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val request = remember(url) {
        url?.let {
            with(density) { ImageRequest.Builder(context).data(it).size(width.roundToPx(), height.roundToPx()).build() }
        }
    }
    Box(
        Modifier.size(width, height).clip(RoundedCornerShape(9.dp)).background(Palette.surfaceHigh),
        contentAlignment = Alignment.Center
    ) {
        Text(remember(name) { initials(name) }, style = MaterialTheme.typography.labelMedium, color = Palette.textDim, maxLines = 1)
        if (request != null) {
            AsyncImage(
                model = request,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.size(width, height).padding(4.dp)
            )
        }
    }
}

private val countryPrefix = Regex("^\\W*[A-Za-z]{2,3}\\s*[|:\\-]\\s*")

fun initials(name: String): String {
    val words = name.replace(countryPrefix, "").split(' ', '-', '_').filter { it.isNotBlank() }
    return when {
        words.isEmpty() -> "TV"
        words.size == 1 -> words[0].take(3).uppercase()
        else -> words.take(2).joinToString("") { it.take(1) }.uppercase()
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.headlineSmall, modifier = modifier.padding(bottom = 12.dp))
}

@Composable
fun Muted(text: String, modifier: Modifier = Modifier, maxLines: Int = 1) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = Palette.textMuted,
        modifier = modifier,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis
    )
}

/** On/off switch drawn without animation. */
@Composable
fun Toggle(on: Boolean) {
    val accent = LocalAccent.current.color
    Box(Modifier.size(52.dp, 30.dp).clip(RoundedCornerShape(15.dp)).background(if (on) accent else Color(0xFF3A404C))) {
        Box(
            Modifier.padding(
                3.dp
            ).size(24.dp).align(if (on) Alignment.CenterEnd else Alignment.CenterStart).clip(CircleShape).background(Palette.text)
        )
    }
}

/** A settings row: label + help text on the left, [trailing] control on the right. */
@Composable
fun SettingRow(
    label: String,
    help: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    trailing: @Composable RowScope.() -> Unit = {}
) {
    TvCard(onClick = onClick, modifier = modifier.fillMaxWidth(), color = Palette.surfaceLow) {
        Row(
            Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            Column(Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.titleSmall)
                if (help != null) Muted(help, maxLines = 2)
            }
            trailing()
        }
    }
}

/** "◀ value ▶" chip for values changed with left/right (or OK to cycle). */
@Composable
fun ValueChip(value: String) {
    Text(
        "◀ $value ▶",
        style = MaterialTheme.typography.labelLarge,
        modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(Palette.surfaceHigh).padding(horizontal = 12.dp, vertical = 6.dp)
    )
}
