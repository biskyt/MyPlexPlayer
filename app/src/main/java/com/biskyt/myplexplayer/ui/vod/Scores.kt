package com.biskyt.myplexplayer.ui.vod

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.biskyt.myplexplayer.AppText
import com.biskyt.myplexplayer.R
import com.biskyt.myplexplayer.core.plex.Score
import com.biskyt.myplexplayer.core.plex.ScoreSource
import com.biskyt.myplexplayer.ui.theme.BOLD
import com.biskyt.myplexplayer.ui.theme.BoardStyle
import com.biskyt.myplexplayer.ui.theme.MEDIUM
import com.biskyt.myplexplayer.ui.theme.Palette
import com.biskyt.myplexplayer.ui.theme.bd
import java.text.NumberFormat
import kotlin.math.roundToInt

/** How tall the sources' logos are drawn: about a line of the details text, the largest place they show. */
private val LOGO_HEIGHT = 20.dp

/**
 * Side by side, [spacing] apart, centred on each line, carried on to the next line when the width runs out: what a
 * FlowRow does, without what that brings into the app.
 */
@Composable
fun WrapRow(spacing: Dp, content: @Composable () -> Unit) = Layout(content) { measurables, constraints ->
    val gap = spacing.roundToPx()
    val placeables = measurables.map { it.measure(constraints.copy(minWidth = 0, minHeight = 0)) }
    val lines = mutableListOf<MutableList<Placeable>>()
    var x = 0
    placeables.forEach { p ->
        if (lines.isEmpty() || x + gap + p.width > constraints.maxWidth) {
            lines += mutableListOf(p)
            x = p.width
        } else {
            lines.last() += p
            x += gap + p.width
        }
    }
    val heights = lines.map { line -> line.maxOf { it.height } }
    val width = lines.maxOfOrNull { line -> line.sumOf { it.width } + gap * (line.size - 1) } ?: 0
    layout(width.coerceIn(constraints.minWidth, constraints.maxWidth), heights.sum() + gap / 2 * (lines.size - 1).coerceAtLeast(0)) {
        var y = 0
        lines.forEachIndexed { i, line ->
            var lineX = 0
            line.forEach { p ->
                p.placeRelative(lineX, y + (heights[i] - p.height) / 2)
                lineX += p.width + gap
            }
            y += heights[i] + gap / 2
        }
    }
}

private const val PERCENT = 100.0

/** The review scores side by side, as the film page's board draws them: 12 apart. */
@Composable
fun ScoresRow(scores: List<Score>) {
    if (scores.isEmpty()) return
    WrapRow(12.bd) { scores.forEach { BoardScore(it) } }
}

/**
 * One review score: its source's logo, 12 high (40% smaller than the boards' 20: the owner, 2026-11-14), then the score
 * as that source writes it ("7.6", "94%"), 14 bold. Also for a line of facts that has its own layout (the series page's).
 */
@Composable
fun BoardScore(score: Score) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.bd), verticalAlignment = Alignment.CenterVertically) {
        SourceLogo(logoOf(score), stringResource(nameOf(score.source)), height = SCORE_LOGO)
        Text(valueOf(score), style = ScoreText.current, color = Palette.textMuted, maxLines = 1)
    }
}

private val ScoreText = BoardStyle(14, BOLD)

/** A score's logo height. */
internal val SCORE_LOGO = 12.bd

/** An age rating as the boards draw it: in a box with a 1.5 px muted border, 13 on a film's page, [small] (12) on a series'. */
@Composable
fun BoardAgeBadge(rating: String, small: Boolean = false) {
    Text(
        rating,
        style = if (small) AgeTextSmall.current else AgeText.current,
        color = Palette.textMuted,
        maxLines = 1,
        modifier = Modifier.border(1.5f.bd, Palette.textDim, RoundedCornerShape(4.bd)).padding(horizontal = 5.bd)
    )
}

private val AgeText = BoardStyle(13, MEDIUM)
private val AgeTextSmall = BoardStyle(12, MEDIUM)

/**
 * A source's logo, [height] high (as tall as it's drawn everywhere, unless given), named [name] for screen readers.
 * Drawn by Android's own vector drawables: Compose's reader of them would add more to the app than the logos themselves.
 */
@Composable
fun SourceLogo(@DrawableRes logo: Int, name: String, modifier: Modifier = Modifier, height: Dp = LOGO_HEIGHT) {
    val context = LocalContext.current
    val drawable = remember(logo) { checkNotNull(context.getDrawable(logo)) }
    val width = height * drawable.intrinsicWidth.toFloat() / drawable.intrinsicHeight
    Box(
        modifier.size(width, height).semantics { contentDescription = name }.drawBehind {
            drawable.setBounds(0, 0, size.width.roundToInt(), size.height.roundToInt())
            drawIntoCanvas { drawable.draw(it.nativeCanvas) }
        }
    )
}

/** IMDb's way, one decimal; the others' out of 100. */
private fun valueOf(score: Score): String = when (score.source) {
    ScoreSource.IMDB -> "%.1f".format(AppText.locale, score.value)
    else -> NumberFormat.getPercentInstance(AppText.locale).format(score.percent / PERCENT)
}

@DrawableRes
private fun logoOf(score: Score): Int = when (score.source) {
    ScoreSource.IMDB -> R.drawable.logo_imdb
    ScoreSource.ROTTEN_TOMATOES -> if (score.fresh) R.drawable.logo_rt_fresh else R.drawable.logo_rt_rotten
    ScoreSource.ROTTEN_TOMATOES_AUDIENCE -> if (score.fresh) R.drawable.logo_rt_upright else R.drawable.logo_rt_spilled
    ScoreSource.TMDB -> R.drawable.logo_tmdb
}

@StringRes
private fun nameOf(source: ScoreSource): Int = when (source) {
    ScoreSource.IMDB -> R.string.vod_score_imdb
    ScoreSource.ROTTEN_TOMATOES -> R.string.vod_score_rotten_tomatoes
    ScoreSource.ROTTEN_TOMATOES_AUDIENCE -> R.string.vod_score_rotten_tomatoes_audience
    ScoreSource.TMDB -> R.string.vod_score_tmdb
}

/** A link's site's logo, by its name ("IMDb", "TMDB", "TVDB"); null for one without. */
@DrawableRes
fun linkLogo(name: String): Int? = when (name) {
    "IMDb" -> R.drawable.logo_imdb
    "TMDB" -> R.drawable.logo_tmdb
    "TVDB" -> R.drawable.logo_tvdb
    else -> null
}
