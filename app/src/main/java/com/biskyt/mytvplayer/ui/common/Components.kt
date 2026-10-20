package com.biskyt.mytvplayer.ui.common

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.allowRgb565
import com.biskyt.mytvplayer.data.db.ChannelEntity
import com.biskyt.mytvplayer.data.db.VodCard
import com.biskyt.mytvplayer.ui.theme.LocalAccent
import com.biskyt.mytvplayer.ui.theme.Palette

private val FocusRing = Border(BorderStroke(3.dp, Palette.text))

/**
 * The app's one clickable surface: neutral background, white ring when focused, optional selected state in accent.
 * No focus scale animation by default — re-laying out rows on every focus change is costly on slow boxes.
 * [onLongClick] (holding OK) is also opened by the remote's Info/Options/Menu button.
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
    var focused by remember { mutableStateOf(false) }
    Surface(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = (if (onLongClick != null) modifier.onOptionsKey(onLongClick) else modifier).onFocusChanged { focused = it.hasFocus },
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
    ) { CompositionLocalProvider(LocalCardFocused provides focused) { content() } }
}

/** Whether the [TvCard] around this content has focus (e.g. for [OneLine]); only readers redraw when it changes. */
val LocalCardFocused = compositionLocalOf { false }

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

/** Thin progress bar drawn in one pass (no nested layouts), with a round [marker] (e.g. while scrubbing) when given. */
@Composable
fun ProgressLine(fraction: Float, modifier: Modifier = Modifier, height: Dp = 5.dp, marker: Float? = null) {
    val accent = LocalAccent.current.color
    Box(
        modifier.height(height).fillMaxWidth().drawBehind {
            val r = CornerRadius(size.height / 2)
            drawRoundRect(Palette.track, cornerRadius = r)
            val f = fraction.coerceIn(0f, 1f)
            if (f > 0f) drawRoundRect(accent, size = Size(size.width * f, size.height), cornerRadius = r)
            if (marker != null) {
                val center = Offset(size.width * marker.coerceIn(0f, 1f), size.height / 2)
                drawCircle(accent, radius = 11.dp.toPx(), center = center)
                drawCircle(Color.White, radius = 6.dp.toPx(), center = center)
            }
        }
    )
}

/**
 * Channel logo with an initials placeholder underneath, so rows look complete before (or without) the image.
 * Images are decoded at the displayed size to keep memory low.
 */
@Composable
fun ChannelLogo(url: String?, name: String, width: Dp = 72.dp, height: Dp = 40.dp) =
    ImageWithInitials(url, name, width, height, RoundedCornerShape(9.dp), ContentScale.Fit, 4.dp, MaterialTheme.typography.labelMedium)

/** A movie or series poster (2:3). Posters are opaque, so they're decoded at half the memory of logos. */
@Composable
fun Poster(url: String?, title: String, width: Dp, modifier: Modifier = Modifier) = ImageWithInitials(
    url, title, width, width * 3 / 2, RoundedCornerShape(10.dp), ContentScale.Crop, 0.dp, MaterialTheme.typography.titleMedium, modifier,
    opaque = true
)

/** Poster width in grids and rows of movies and series. */
val POSTER_WIDTH = 124.dp

/** A movie or series poster with its title and year, as in grids and search. */
@Composable
fun PosterCard(card: VodCard, modifier: Modifier = Modifier, onLongClick: (() -> Unit)? = null, onClick: () -> Unit) {
    TvCard(onClick = onClick, onLongClick = onLongClick, color = Palette.background, modifier = modifier) {
        Column(Modifier.padding(6.dp).width(POSTER_WIDTH), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Poster(card.imageUrl, card.title, POSTER_WIDTH)
            OneLine(card.title, MaterialTheme.typography.labelLarge)
            Muted(card.year?.toString() ?: " ")
        }
    }
}

/**
 * One line of text: cut short with "…" normally, scrolling a few times to show the rest while its [TvCard] has focus
 * (only the focused item animates, and not for ever).
 */
@Composable
fun OneLine(
    text: String,
    style: TextStyle,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    focused: Boolean = LocalCardFocused.current
) {
    if (focused) {
        Text(
            text,
            style = style,
            color = color,
            maxLines = 1,
            softWrap = false,
            modifier = modifier.basicMarquee()
        )
    } else {
        Text(text, style = style, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = modifier)
    }
}

/** A row in an options panel ([SidePanel]): a label and, under it, what it does. */
@Composable
fun MenuOption(label: String, help: String? = null, modifier: Modifier = Modifier, onClick: () -> Unit) {
    TvCard(onClick = onClick, modifier = modifier.fillMaxWidth(), color = Palette.surface) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(label, style = MaterialTheme.typography.titleMedium)
            if (help != null) Muted(help)
        }
    }
}

/** An image downsampled to its size on screen, with the name's initials until (or unless) it loads. */
@Composable
private fun ImageWithInitials(
    url: String?,
    name: String,
    width: Dp,
    height: Dp,
    shape: Shape,
    contentScale: ContentScale,
    padding: Dp,
    textStyle: TextStyle,
    modifier: Modifier = Modifier,
    opaque: Boolean = false
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val request = remember(url) {
        url?.let {
            with(density) {
                ImageRequest.Builder(context).data(it).size(width.roundToPx(), height.roundToPx()).apply {
                    if (opaque) allowRgb565(true)
                }.build()
            }
        }
    }
    var loaded by remember(url) { mutableStateOf(false) }
    Box(modifier.size(width, height).clip(shape).background(Palette.surfaceHigh), contentAlignment = Alignment.Center) {
        if (!loaded) Text(remember(name) { initials(name) }, style = textStyle, color = Palette.textDim, maxLines = 1)
        if (request != null) {
            AsyncImage(
                model = request,
                contentDescription = null,
                contentScale = contentScale,
                onSuccess = { loaded = true },
                modifier = Modifier.size(width, height).padding(padding)
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

/** Marks a channel with catch-up (nothing for others): small and dim, there when looked for without crowding. */
@Composable
fun CatchupMark(ch: ChannelEntity, modifier: Modifier = Modifier) {
    if (ch.archiveDays > 0) Icon(AppIcons.Catchup, "Catch-up", tint = Palette.textDim, modifier = modifier.size(16.dp))
}
