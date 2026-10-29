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
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.biskyt.myplexplayer.AppText
import com.biskyt.myplexplayer.R
import com.biskyt.myplexplayer.core.plex.Score
import com.biskyt.myplexplayer.core.plex.ScoreSource
import com.biskyt.myplexplayer.ui.common.Muted
import com.biskyt.myplexplayer.ui.theme.Palette
import java.text.NumberFormat
import kotlin.math.roundToInt

/** How tall the sources' logos are drawn: about a line of the details text, the largest place they show. */
private val LOGO_HEIGHT = 20.dp

/**
 * A details page's line: [before] and [after] around the [ageRating] badge ("2021 · 1 h 52 min · [15] · Drama"), then
 * each review score with its source's logo, on as many lines as the width needs. Whatever is unknown is left out.
 */
@Composable
fun DetailsLine(before: List<String>, ageRating: String?, after: List<String> = emptyList(), scores: List<Score> = emptyList()) {
    WrapRow {
        if (before.isNotEmpty()) Muted(before.joinToString(" · "))
        ageRating?.let { AgeBadge(it) }
        if (after.isNotEmpty()) Muted(after.joinToString(" · "))
        scores.forEach { ScoreItem(it) }
    }
}

/**
 * Side by side, 12 dp apart, centred on each line, carried on to the next line when the width runs out: what a
 * FlowRow does, without what that brings into the app.
 */
@Composable
private fun WrapRow(content: @Composable () -> Unit) = Layout(content) { measurables, constraints ->
    val gap = WRAP_GAP.roundToPx()
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

private val WRAP_GAP = 12.dp

private const val PERCENT = 100.0

/** An age rating in an outlined box ("15"), as a details page shows it. */
@Composable
fun AgeBadge(rating: String, modifier: Modifier = Modifier) {
    Text(
        rating,
        style = MaterialTheme.typography.labelMedium,
        color = Palette.textMuted,
        maxLines = 1,
        modifier = modifier.border(1.dp, Palette.textMuted, RoundedCornerShape(4.dp)).padding(horizontal = 6.dp)
    )
}

/** One review score: its source's logo, then the score as that source writes it ("7.6", "94%"). */
@Composable
private fun ScoreItem(score: Score, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        SourceLogo(logoOf(score), stringResource(nameOf(score.source)))
        Muted(valueOf(score))
    }
}

/**
 * A source's logo, as tall as it's drawn everywhere, named [name] for screen readers. Drawn by Android's own vector
 * drawables: Compose's reader of them would add more to the app than the logos themselves.
 */
@Composable
fun SourceLogo(@DrawableRes logo: Int, name: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val drawable = remember(logo) { checkNotNull(context.getDrawable(logo)) }
    val width = LOGO_HEIGHT * drawable.intrinsicWidth.toFloat() / drawable.intrinsicHeight
    Box(
        modifier.size(width, LOGO_HEIGHT).semantics { contentDescription = name }.drawBehind {
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
