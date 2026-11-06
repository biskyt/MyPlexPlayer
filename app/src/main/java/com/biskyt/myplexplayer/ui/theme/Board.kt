package com.biskyt.myplexplayer.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Sizes copied from the design boards (the film and series pages, the top bar). A board is 960 x 540: a 1080p TV in
 * dp. The app lays out on a canvas 1280 wide (see MyPlexTheme), so each board size is 4/3 of its value here. The boards'
 * sizes are used as they are, so that the screens match them exactly, except text (see [BoardStyle]).
 */
private const val BOARD = 1280f / 960f

/** [this] board pixels. */
val Int.bd: Dp get() = (this * BOARD).dp

/** [this] board pixels, for a board's fractional sizes (a 1.5 px border). */
val Float.bd: Dp get() = (this * BOARD).dp

/**
 * Text as a board writes it: [size] px, [weight], and an optional CSS line height (a multiple of the size), at
 * [factor] sp a pixel.
 */
private fun boardText(size: Int, weight: Int, lineHeight: Float?, factor: Float, letterSpacing: TextUnit) = TextStyle(
    fontFamily = Nunito,
    fontSize = (size * factor).sp,
    fontWeight = FontWeight(weight),
    color = Palette.text,
    lineHeight = lineHeight?.let { (size * it * factor).sp } ?: TextUnit.Unspecified,
    // As CSS lays a line out: the line height's extra (or shortfall) split evenly above and below the text.
    lineHeightStyle = lineHeight?.let { LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None) },
    letterSpacing = letterSpacing
)

/**
 * A board's text style, in the text colour (give another colour on the Text). Its size is the board's number as the
 * rest of the app sizes text (the theme's scale, not the boards' 4/3), and a step larger with Settings › large text:
 * the boards' text, scaled like their layout, was too large on a TV (the owner, 2026-11-05 and 2026-11-06). Both styles
 * are made once. [letterSpacing] is in em, as the boards give it.
 */
class BoardStyle(size: Int, weight: Int, lineHeight: Float? = null, letterSpacing: TextUnit = TextUnit.Unspecified) {
    private val normal = boardText(size, weight, lineHeight, textFactor(large = false), letterSpacing)
    private val large = boardText(size, weight, lineHeight, textFactor(large = true), letterSpacing)

    val current: TextStyle @Composable get() = if (LocalLargeText.current) large else normal
}

/**
 * About the step that large text adds on the other screens (2 points on sizes near 16). Applied to the sizes, not
 * through the density's font scale: Android scales large text non-linearly, so the boards' titles would barely change.
 */
private const val LARGE_TEXT = 1.12f

/** The sp a board text pixel is drawn at: the theme's scale, a step larger with large text. */
private fun textFactor(large: Boolean) = if (large) TEXT_SCALE * LARGE_TEXT else TEXT_SCALE

/** Nunito's own line height, as a multiple of its size (its ascent and descent): CSS's "normal". */
private const val NUNITO_LINE = 1.364f

/**
 * Text set at a board's [lineHeight] below Nunito's own (a title's 1.1): CSS makes the line that much shorter, with the
 * glyphs centred, and Compose keeps the font's height. This takes the difference off its first and last lines, so the
 * text sits where the board has it. [size] is the board's font size, drawn as [BoardStyle] draws it (a step larger
 * with large text).
 */
@Composable
fun Modifier.boardLineBox(size: Int, lineHeight: Float): Modifier {
    val drawn = size * textFactor(LocalLargeText.current)
    return layout { measurable, constraints ->
        val trim = ((NUNITO_LINE - lineHeight) / 2 * drawn).dp.roundToPx().coerceAtLeast(0)
        val p = measurable.measure(constraints)
        layout(p.width, (p.height - 2 * trim).coerceAtLeast(0)) { p.place(0, -trim) }
    }
}
