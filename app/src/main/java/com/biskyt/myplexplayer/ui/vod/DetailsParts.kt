package com.biskyt.myplexplayer.ui.vod

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.Text
import com.biskyt.myplexplayer.R
import com.biskyt.myplexplayer.ui.common.BoardPanel
import com.biskyt.myplexplayer.ui.common.BoardPanelText
import com.biskyt.myplexplayer.ui.common.BoardPanelTitle
import com.biskyt.myplexplayer.ui.common.TvCard
import com.biskyt.myplexplayer.ui.common.cardColor
import com.biskyt.myplexplayer.ui.common.rememberFirstFocus
import com.biskyt.myplexplayer.ui.player.PLOT_LINES
import com.biskyt.myplexplayer.ui.theme.BOLD
import com.biskyt.myplexplayer.ui.theme.BoardStyle
import com.biskyt.myplexplayer.ui.theme.EXTRA_BOLD
import com.biskyt.myplexplayer.ui.theme.LocalAccent
import com.biskyt.myplexplayer.ui.theme.Palette
import com.biskyt.myplexplayer.ui.theme.bd

// The film and series pages, as the design boards (2026-11-05) draw them. Sizes are the boards' (see Board.kt).

/**
 * A film or series page's button: [background] (#22262D), or filled with the accent colour while it has focus, with no
 * ring. [strong] is the main one's heavier text. [progress] (0 to 1) runs along its bottom edge.
 */
@Composable
fun DetailButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    strong: Boolean = false,
    progress: Float? = null,
    horizontal: Dp = 20.bd,
    vertical: Dp = 10.bd,
    style: TextStyle = if (strong) ButtonStrong.current else ButtonText.current,
    background: Color = Palette.surfaceHigh,
    /** Below the text, when it differs from [vertical] (the film's Resume keeps room for its progress). */
    bottom: Dp = vertical,
    /** Holding OK (or a long press): the page's options. */
    onLongClick: (() -> Unit)? = null
) {
    TvCard(onClick, modifier, onLongClick = onLongClick, shape = Pill, color = background, fill = true) {
        Box(contentAlignment = Alignment.Center) {
            Row(
                Modifier.padding(start = horizontal, end = horizontal, top = vertical, bottom = bottom),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.bd)
            ) {
                // The card's content colour: the text's, or the accent's text colour while it has focus.
                if (text.isNotEmpty()) Text(text, style = style, color = LocalContentColor.current, maxLines = 1)
            }
            if (progress != null) ButtonProgress(progress)
        }
    }
}

/**
 * A season's pill: the one shown filled with the accent colour. With focus, it is filled with the accent colour as every
 * button is (the owner, 2026-11-13), and the one shown has the in-use ring inside the fill.
 */
@Composable
fun SeasonPill(name: String, current: Boolean, onClick: () -> Unit) {
    val accent = LocalAccent.current
    val fg = if (current) accent.onAccent else Palette.text
    TvCard(
        onClick,
        selected = current,
        shape = Pill,
        color = Palette.surfaceHigh,
        selectedColor = accent.color,
        fill = true,
        contentColor = fg
    ) {
        Text(
            name,
            style = if (current) PillStrong.current else PillText.current,
            color = cardColor(fg),
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 14.bd, vertical = 6.bd)
        )
    }
}

/**
 * How far into the film: a 4 px bar along the bottom of its button, in the page's background at 45 %. It takes the
 * button's size and does not set it.
 */
@Composable
private fun BoxScope.ButtonProgress(fraction: Float) {
    // matchParentSize: measured after the text, so the bar never makes the button larger.
    Box(Modifier.matchParentSize(), contentAlignment = Alignment.BottomStart) {
        Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).height(4.bd).background(Palette.background.copy(alpha = 0.45f)))
    }
}

private val Pill = RoundedCornerShape(50)

// The buttons' and pills' text (their colour comes from where they are).
private val ButtonText = BoardStyle(15, BOLD)
private val ButtonStrong = BoardStyle(15, EXTRA_BOLD)
private val PillText = BoardStyle(14, BOLD)
private val PillStrong = BoardStyle(14, EXTRA_BOLD)

/**
 * A film's or series' plot, [maxLines] at most. When it is cut short, a Read more button under it opens the whole of it
 * in a side panel, with [title] (the owner, 2026-11-06). MyTVPlayer calls the button More…: here, the film page has
 * More… for its options already.
 */
@Composable
fun PlotWithMore(title: String, plot: String, style: TextStyle, maxLines: Int, moreModifier: Modifier = Modifier) {
    var cut by remember(plot) { mutableStateOf(false) }
    var open by remember(plot) { mutableStateOf(false) }
    Text(plot, style = style, maxLines = maxLines, overflow = TextOverflow.Ellipsis, onTextLayout = { cut = it.hasVisualOverflow })
    if (cut) DetailButton(stringResource(R.string.vod_read_more), { open = true }, moreModifier, horizontal = 14.bd, vertical = 6.bd)
    if (open) {
        // Its text does not scroll (the remote has nothing in it to move to): it is cut at what fits.
        BoardPanel({ open = false }, dim = false, scroll = false) {
            // Focused, so that Back reaches the panel and closes it.
            Column(rememberFirstFocus().focusable(), verticalArrangement = Arrangement.spacedBy(8.bd)) {
                BoardPanelTitle(title)
                BoardPanelText(plot, maxLines = PLOT_LINES)
            }
        }
    }
}
