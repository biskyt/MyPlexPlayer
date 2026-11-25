package com.biskyt.myplexplayer.ui.phone

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.biskyt.myplexplayer.R
import com.biskyt.myplexplayer.core.plex.Score
import com.biskyt.myplexplayer.core.vod.CastMember
import com.biskyt.myplexplayer.core.vod.VodDetails
import com.biskyt.myplexplayer.data.db.MovieEntity
import com.biskyt.myplexplayer.data.db.WatchProgressEntity
import com.biskyt.myplexplayer.ui.LocalNavigator
import com.biskyt.myplexplayer.ui.common.AppIcons
import com.biskyt.myplexplayer.ui.common.CirclePhoto
import com.biskyt.myplexplayer.ui.common.Poster
import com.biskyt.myplexplayer.ui.common.backdropRequest
import com.biskyt.myplexplayer.ui.theme.FormFactor
import com.biskyt.myplexplayer.ui.theme.LocalAccent
import com.biskyt.myplexplayer.ui.theme.LocalFormFactor
import com.biskyt.myplexplayer.ui.theme.Palette
import com.biskyt.myplexplayer.ui.vod.BoardAgeBadge
import com.biskyt.myplexplayer.ui.vod.BoardScore
import com.biskyt.myplexplayer.ui.vod.LabelRow
import com.biskyt.myplexplayer.ui.vod.PlotPanel
import com.biskyt.myplexplayer.ui.vod.WrapRow
import com.biskyt.myplexplayer.ui.vod.filmFacts
import com.biskyt.myplexplayer.ui.vod.originalTitle
import com.biskyt.myplexplayer.ui.vod.people
import com.biskyt.myplexplayer.ui.vod.resumeLabel
import com.biskyt.myplexplayer.ui.vod.resumePoint

// The film page on a phone or an unfolded foldable (the owner, 2026-11-25), as the mock-up "Film" draws it, with
// MyPlexPlayer's own parts (the scores, the age badge, the picture and sound labels). The parts that the series page
// uses too are here (see PhoneSeries.kt). Sizes are in plain dp and sp: the device's own.

/**
 * The film page on a phone: the title's art at the top, which fades into the page, with the poster beside the title and
 * the facts; Play or Resume across the page, then Restart and More in a row; the plot, who made it, and the cast in a
 * row that scrolls sideways (a tap opens a person's page, [openPerson]). The page scrolls. Unfolded, the poster, facts
 * and buttons are on the left, and the plot and cast on the right. The arguments are FilmPage's.
 */
@Composable
@Suppress("LongParameterList") // FilmPage's, passed on as they are
fun PhoneFilmPage(
    movie: MovieEntity,
    d: VodDetails?,
    resumable: WatchProgressEntity?,
    play: (fromStart: Boolean) -> Unit,
    more: () -> Unit,
    openPerson: (CastMember) -> Unit
) {
    PhoneDetailLayout(
        left = {
            PhoneDetailHeader(d?.backdropUrl, movie.posterUrl ?: d?.posterUrl, movie.title) {
                d?.let { originalTitle(movie.title, it) }?.let {
                    PhoneSmallText(stringResource(R.string.vod_original_title_is, it), maxLines = 2)
                }
                val facts = filmFacts(movie, d)
                if (facts.isNotEmpty()) PhoneFacts(facts)
                PhoneBadges(d?.contentRating, d?.scores.orEmpty())
            }
            FilmButtons(resumable, play, more)
        },
        right = { unfolded ->
            d?.plot?.let { PhonePlot(movie.title, it, if (unfolded) UNFOLDED_PLOT_LINES else PLOT_LINES) }
            d?.let { people(it.directors, it.writers) }?.let {
                PhoneSmallText(it, Modifier.padding(horizontal = PhoneGutter), maxLines = 3)
            }
            d?.media?.badges?.takeIf { it.isNotEmpty() }?.let { LabelRow(it, Modifier.padding(horizontal = PhoneGutter), wrap = true) }
            d?.cast?.takeIf { it.isNotEmpty() }?.let { PhoneCastRow(it, openPerson) }
        }
    )
}

/** The age rating in its box, then the scores, on a line of their own that wraps. */
@Composable
internal fun PhoneBadges(rating: String?, scores: List<Score>) {
    if (rating == null && scores.isEmpty()) return
    WrapRow(8.dp) {
        rating?.let { BoardAgeBadge(it, small = true) }
        scores.forEach { BoardScore(it) }
    }
}

/**
 * A phone page in one column that scrolls, [left] over [right]. Unfolded, it is two panes side by side, and each pane
 * scrolls. [right] is told which.
 */
@Composable
internal fun PhoneDetailLayout(left: @Composable ColumnScope.() -> Unit, right: @Composable ColumnScope.(unfolded: Boolean) -> Unit) {
    val page = Modifier.fillMaxSize().background(Palette.background)
    if (LocalFormFactor.current == FormFactor.UNFOLDED) {
        Row(page) {
            Column(
                Modifier.width(UNFOLDED_LEFT).fillMaxHeight().verticalScroll(rememberScrollState()).padding(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(PANE_GAP)
            ) { left() }
            Column(
                Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(top = 24.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(PANE_GAP)
            ) { right(true) }
        }
    } else {
        Column(page.verticalScroll(rememberScrollState()).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(PANE_GAP)) {
            left()
            right(false)
        }
    }
}

/**
 * The top of a film or series page: the title's art ([backdrop]) 230 high, which fades into the page, with a back
 * button on it (see [com.biskyt.myplexplayer.ui.Navigator.back]). Then the [poster] over its bottom edge, and beside it
 * the [title] and [lines] (the facts).
 */
@Composable
internal fun PhoneDetailHeader(backdrop: String?, poster: String?, title: String, lines: @Composable ColumnScope.() -> Unit) {
    val nav = LocalNavigator.current
    Box(Modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().height(BACKDROP_HEIGHT).background(Palette.surfaceHigh)) {
            if (backdrop != null) {
                // The same request as the TV page's art (Backdrop.kt): one copy of it, kept without the server's token.
                val context = LocalContext.current
                val request = remember(backdrop) { backdropRequest(context, backdrop) }
                AsyncImage(request, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().testTag("backdrop"))
            }
            Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(0.4f to Palette.background.copy(alpha = 0f), 1f to Palette.background)
                )
            )
        }
        PhoneIconButton(
            AppIcons.Back,
            stringResource(R.string.player_back),
            { nav.back() },
            Modifier.padding(14.dp),
            fill = Color.Black.copy(alpha = 0.45f),
            iconSize = 22.dp
        )
        Row(
            Modifier.padding(start = PhoneGutter, end = PhoneGutter, top = BACKDROP_HEIGHT - POSTER_OVER),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            Poster(poster, title, POSTER_WIDTH, radius = 10.dp)
            Column(Modifier.weight(1f).padding(bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    title,
                    color = Palette.text,
                    fontSize = 24.sp,
                    lineHeight = 26.sp,
                    fontWeight = FontWeight.Black,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
                lines()
            }
        }
    }
}

/** Play or Resume (with what is left, and its progress along the bottom), then Restart and More. Holding Play opens More too. */
@Composable
private fun FilmButtons(resumable: WatchProgressEntity?, play: (Boolean) -> Unit, more: () -> Unit) {
    val started = resumable.resumePoint()
    Column(Modifier.padding(start = PhoneGutter, end = PhoneGutter, top = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        PhoneMainButton(
            resumeLabel(started),
            started?.takeIf { it.durationMs > 0 }?.fraction,
            onLongClick = more
        ) { play(false) }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            val tile = Modifier.weight(1f)
            if (started != null) PhoneTileButton(AppIcons.SkipBack, stringResource(R.string.vod_restart), tile) { play(true) }
            PhoneTileButton(AppIcons.More, moreLabel(), tile, onClick = more)
        }
    }
}

/** The page's main button, across it in the accent colour: [progress] (0 to 1) runs along its bottom. */
@Composable
internal fun PhoneMainButton(text: String, progress: Float?, onLongClick: (() -> Unit)? = null, onClick: () -> Unit) {
    val accent = LocalAccent.current
    Box(
        Modifier.fillMaxWidth().heightIn(min = 52.dp).clip(RoundedCornerShape(14.dp)).background(accent.color)
            .combinedClickable(role = Role.Button, onLongClick = onLongClick, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Image(AppIcons.Play, null, Modifier.size(20.dp), colorFilter = ColorFilter.tint(accent.onAccent))
            Text(
                text,
                color = accent.onAccent,
                fontSize = 17.sp,
                fontWeight = FontWeight.Black,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (progress != null) {
            Box(
                Modifier.align(
                    Alignment.BottomStart
                ).fillMaxWidth(progress.coerceIn(0f, 1f)).height(4.dp).background(Color.Black.copy(alpha = 0.35f))
            )
        }
    }
}

/** "More", as a tile's label: the TV's "More…" without its dots (a tile opens its panel at once, as the dots say). */
@Composable
internal fun moreLabel() = stringResource(R.string.vod_more).trimEnd('…')

/**
 * A square-ish button with [icon] over its [label], one of a row. [iconDescription] is what the icon says for a screen
 * reader, when it says more than the label (the heart: whether it is a favourite).
 */
@Composable
internal fun PhoneTileButton(
    icon: ImageVector,
    label: String,
    modifier: Modifier = Modifier,
    iconDescription: String? = null,
    onClick: () -> Unit
) {
    Column(
        modifier.heightIn(min = 58.dp).clip(RoundedCornerShape(12.dp)).background(PhoneChip)
            .clickable(role = Role.Button, onClick = onClick).padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically)
    ) {
        Image(icon, iconDescription, Modifier.size(20.dp), colorFilter = ColorFilter.tint(Palette.text))
        Text(
            label,
            color = Palette.text,
            fontSize = 12.sp,
            fontWeight = FontWeight.ExtraBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** The facts line ("15 Sept 2021 · 2 h 35 min · Drama"). */
@Composable
internal fun PhoneFacts(text: String) = Text(
    text,
    color = Palette.textMuted,
    fontSize = 13.sp,
    fontWeight = FontWeight.ExtraBold,
    maxLines = 3,
    overflow = TextOverflow.Ellipsis
)

/** A small muted line (the original title, who made it). */
@Composable
internal fun PhoneSmallText(text: String, modifier: Modifier = Modifier, maxLines: Int = 1) =
    Text(text, color = Palette.textDim, fontSize = 13.sp, maxLines = maxLines, overflow = TextOverflow.Ellipsis, modifier = modifier)

/**
 * The plot, [maxLines] at most. When it is cut short, a tap on it opens the whole of it in a sheet, with [title], as
 * Read more does on a TV.
 */
@Composable
internal fun PhonePlot(title: String, plot: String, maxLines: Int) {
    var cut by remember(plot) { mutableStateOf(false) }
    var open by remember(plot) { mutableStateOf(false) }
    Text(
        plot,
        color = Palette.textMuted,
        fontSize = 15.sp,
        lineHeight = 22.sp,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        onTextLayout = { cut = it.hasVisualOverflow },
        modifier = Modifier.fillMaxWidth().clickable(enabled = cut, role = Role.Button) { open = true }.padding(horizontal = PhoneGutter)
    )
    if (open) PlotPanel(title, plot) { open = false }
}

/** "Cast", then each one's round photo, name and part in a row that scrolls sideways. A tap opens their page. */
@Composable
private fun PhoneCastRow(cast: List<CastMember>, open: (CastMember) -> Unit) {
    Column(Modifier.padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            stringResource(R.string.vod_cast_title),
            color = Palette.text,
            fontSize = 16.sp,
            fontWeight = FontWeight.ExtraBold,
            modifier = Modifier.padding(horizontal = PhoneGutter)
        )
        LazyRow(contentPadding = PaddingValues(horizontal = PhoneGutter), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            // Keyed by place and name: a name can be in the cast twice.
            itemsIndexed(cast, key = { i, person -> "$i ${person.name}" }) { _, person ->
                Column(
                    Modifier.width(76.dp).clip(RoundedCornerShape(10.dp)).clickable(role = Role.Button) { open(person) }
                        .padding(vertical = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    CirclePhoto(person.photoUrl, person.name, 64.dp)
                    Spacer(Modifier.height(2.dp))
                    Text(
                        person.name,
                        color = Palette.text,
                        fontSize = 12.sp,
                        lineHeight = 14.sp,
                        fontWeight = FontWeight.ExtraBold,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    person.role?.let {
                        Text(
                            it,
                            color = Palette.textDim,
                            fontSize = 11.sp,
                            lineHeight = 13.sp,
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

private val PANE_GAP = 12.dp
private val UNFOLDED_LEFT = 380.dp
private val BACKDROP_HEIGHT = 230.dp
private val POSTER_WIDTH = 104.dp

/** How far the poster comes up over the backdrop. */
private val POSTER_OVER = 70.dp
private const val PLOT_LINES = 4
private const val UNFOLDED_PLOT_LINES = 10
