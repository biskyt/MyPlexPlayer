package com.biskyt.myplexplayer.ui.phone

import androidx.compose.foundation.Image
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.biskyt.myplexplayer.R
import com.biskyt.myplexplayer.core.vod.EpisodeInfo
import com.biskyt.myplexplayer.core.vod.SeriesDetails
import com.biskyt.myplexplayer.data.db.SeriesEntity
import com.biskyt.myplexplayer.data.db.WatchProgressEntity
import com.biskyt.myplexplayer.ui.common.AppIcons
import com.biskyt.myplexplayer.ui.common.ProgressLine
import com.biskyt.myplexplayer.ui.common.Thumbnail
import com.biskyt.myplexplayer.ui.common.favouriteIcon
import com.biskyt.myplexplayer.ui.common.favouriteLabel
import com.biskyt.myplexplayer.ui.common.rememberTrailerOpener
import com.biskyt.myplexplayer.ui.common.shortEpisodeLabel
import com.biskyt.myplexplayer.ui.player.episodeName
import com.biskyt.myplexplayer.ui.theme.LocalAccent
import com.biskyt.myplexplayer.ui.theme.Palette
import com.biskyt.myplexplayer.ui.vod.DeleteSeriesQuestion
import com.biskyt.myplexplayer.ui.vod.SeriesDetailsViewModel
import com.biskyt.myplexplayer.ui.vod.continueText
import com.biskyt.myplexplayer.ui.vod.episodeLength
import com.biskyt.myplexplayer.ui.vod.nextEpisodeLine
import com.biskyt.myplexplayer.ui.vod.partWatched
import com.biskyt.myplexplayer.ui.vod.seasonName
import com.biskyt.myplexplayer.ui.vod.seriesFacts
import com.biskyt.myplexplayer.ui.vod.seriesStatusLine
import com.biskyt.myplexplayer.ui.vod.trailerButton

// The series page on a phone or an unfolded foldable (the owner, 2026-11-25): the film page's header (PhoneFilm.kt),
// then the seasons and their episodes. Its panels (an episode's options, Cast and crew, About this show) stay
// SeriesDetailsScreen's, as sheets.

/**
 * The series page on a phone: the header (the season's facts, the age badge and scores, and the show's status and
 * next episode from Discover), Continue (or Play) across the page, then Favourite, Trailer and More (the options of
 * [next], the episode to carry on with); the plot; the seasons as chips that scroll sideways, and [season]'s episodes. A tap
 * on an episode plays it ([play]); a long press opens its options, as a held OK does on a TV. Unfolded, the seasons
 * and episodes are on the right. [d] is null while it loads. [asked] is the episode the page was opened at (the
 * player's Episodes button, the TV guide): the page scrolls to it.
 */
@Composable
@Suppress("LongParameterList") // the page's state, passed down rather than read again
fun PhoneSeriesPage(
    vm: SeriesDetailsViewModel,
    show: SeriesEntity,
    d: SeriesDetails?,
    season: Int?,
    next: EpisodeInfo?,
    asked: EpisodeInfo?,
    progress: Map<Long, WatchProgressEntity>,
    play: (EpisodeInfo) -> Unit
) {
    val info = d?.info
    val current = d?.seasons?.firstOrNull { it.number == season }
    PhoneDetailLayout(
        left = {
            PhoneDetailHeader(info?.backdropUrl, current?.posterUrl ?: show.coverUrl ?: info?.posterUrl, show.title) {
                val facts = seriesFacts(show, d, vm.status, season)
                if (facts.isNotEmpty()) PhoneFacts(facts)
                PhoneBadges(info?.contentRating, info?.scores.orEmpty())
                // Discover's (or TMDB's) word on the whole show: whether it goes on, and when the next episode is shown.
                seriesStatusLine(vm.status, seasons = false)?.let { PhoneSmallText(it, maxLines = 2) }
                nextEpisodeLine(vm.status)?.let { PhoneSmallText(it, maxLines = 2) }
            }
            SeriesButtons(vm, next, progress, play)
            info?.plot?.let { PhonePlot(show.title, it, PLOT_LINES) }
        },
        right = { _ -> EpisodesPane(vm, d, season, asked, progress, play) }
    )
}

/**
 * Continue (or Play) across the page, then Favourite, Trailer (the show's, only when it has one) and More (the options
 * of [next], the episode to carry on with).
 */
@Composable
private fun SeriesButtons(
    vm: SeriesDetailsViewModel,
    next: EpisodeInfo?,
    progress: Map<Long, WatchProgressEntity>,
    play: (EpisodeInfo) -> Unit
) {
    val favourite by vm.favourite.collectAsState()
    // The show's trailer, as "Play trailer" under "The show" plays it (the owner, 2026-11-25: as in MyTVPlayer).
    val trailer = trailerButton(vm.details?.info?.trailer, vm::trailer, vm.trailers.tmdbTrailer, rememberTrailerOpener())
    Column(Modifier.padding(start = PhoneGutter, end = PhoneGutter, top = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        val options = next?.let { e -> { vm.openOptions(e) } }
        if (next != null) {
            PhoneMainButton(continueText(next, progress), progress[next.id].partWatched()?.fraction, options) { play(next) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            val tile = Modifier.weight(1f)
            PhoneTileButton(
                favouriteIcon(favourite),
                stringResource(R.string.common_favourite_mark),
                tile,
                iconDescription = favouriteLabel(favourite)
            ) { vm.toggleFavourite() }
            trailer?.let { PhoneTileButton(AppIcons.Trailer, stringResource(R.string.vod_trailer), tile, onClick = it) }
            options?.let { PhoneTileButton(AppIcons.More, moreLabel(), tile, onClick = it) }
        }
    }
}

/**
 * The episodes, or why there are none: the seasons as chips (when there is more than one), then [season]'s episodes.
 * The page scrolls to [asked] once, as it opens.
 */
@Composable
@Suppress("LongParameterList") // the page's state, passed down rather than read again
private fun EpisodesPane(
    vm: SeriesDetailsViewModel,
    d: SeriesDetails?,
    season: Int?,
    asked: EpisodeInfo?,
    progress: Map<Long, WatchProgressEntity>,
    play: (EpisodeInfo) -> Unit
) {
    val error = vm.error
    when {
        error != null -> Column(Modifier.padding(horizontal = PhoneGutter), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            PhoneSmallText(error, maxLines = 3)
            PhonePill(stringResource(R.string.common_retry)) { vm.load() }
        }
        d == null -> PhoneSmallText(stringResource(R.string.vod_loading_episodes), Modifier.padding(horizontal = PhoneGutter))
        d.episodes.isEmpty() -> NoEpisodes(vm)
        else -> {
            if (d.seasons.size > 1) {
                PhoneChipRow(d.seasons, { it.number == season }, { it.number }) { s, chosen ->
                    PhoneChoiceChip(seasonName(s.number, s.name), chosen) { vm.season = s.number }
                }
            }
            val shown = remember(d, season) { d.episodes.filter { season == null || it.season == season } }
            // A column, not a lazy list: the page scrolls as a whole, and a season has tens of episodes at most.
            Column {
                shown.forEach { e ->
                    val scrollTo = if (e == asked && vm.focusEpisodePending) ({ vm.focusEpisodePending = false }) else null
                    PhoneEpisodeRow(e, progress[e.id], scrollTo, { vm.openOptions(e) }) {
                        // The list stays on this season when the one to carry on with moves to the next.
                        if (vm.season == null) vm.season = e.season
                        play(e)
                    }
                }
            }
        }
    }
}

/** A series with no episodes: says so, and offers to delete it where the server allows it (asked first, as on a TV). */
@Composable
private fun NoEpisodes(vm: SeriesDetailsViewModel) {
    val canDelete by produceState(false) { value = vm.canDelete() }
    var asking by remember { mutableStateOf(false) }
    Column(Modifier.padding(horizontal = PhoneGutter), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        PhoneSmallText(stringResource(if (canDelete) R.string.vod_no_episodes_delete else R.string.vod_no_episodes), maxLines = 3)
        if (canDelete) PhonePill(stringResource(R.string.vod_delete_series_button)) { asking = true }
    }
    if (asking) DeleteSeriesQuestion(vm) { asking = false }
}

/**
 * An episode: its picture (with its number until, or unless, it loads), its code and name, its running time and what
 * is left, a bar when part-watched, and a tick when watched. A tap plays it ([onClick]); a long press opens [options].
 * With [scrolledTo], the page scrolls to it once it is laid out, then calls that.
 */
@Composable
private fun PhoneEpisodeRow(
    e: EpisodeInfo,
    progress: WatchProgressEntity?,
    scrolledTo: (() -> Unit)?,
    options: () -> Unit,
    onClick: () -> Unit
) {
    // Again when the language changes: it may be the app's "Episode 2".
    val name = remember(e, LocalConfiguration.current) { episodeName(e) }
    val accent = LocalAccent.current.color
    val bring = remember { BringIntoViewRequester() }
    if (scrolledTo != null) {
        LaunchedEffect(Unit) {
            withFrameNanos { } // once it has its place on the page
            bring.bringIntoView()
            scrolledTo()
        }
    }
    Row(
        Modifier.fillMaxWidth().bringIntoViewRequester(bring).heightIn(min = 72.dp)
            .combinedClickable(role = Role.Button, onLongClick = options, onClick = onClick)
            .padding(horizontal = PhoneGutter, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Thumbnail(e.imageUrl, "${e.number}", STILL_WIDTH, STILL_WIDTH * 9 / 16, RoundedCornerShape(8.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                shortEpisodeLabel(e.season, e.number),
                color = Palette.textDim,
                fontSize = 12.sp,
                fontWeight = FontWeight.ExtraBold,
                maxLines = 1
            )
            Text(
                name,
                color = Palette.text,
                fontSize = 15.sp,
                lineHeight = 18.sp,
                fontWeight = FontWeight.ExtraBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            val part = progress.partWatched()
            val length = episodeLength(e, part)
            if (length.isNotEmpty()) Text(length, color = Palette.textDim, fontSize = 13.sp, maxLines = 1)
            if (part != null) ProgressLine(part.fraction, Modifier.padding(top = 3.dp).fillMaxWidth(), height = 3.dp)
        }
        if (progress?.finished == true) {
            Image(Icons.Rounded.Check, stringResource(R.string.vod_watched), Modifier.size(20.dp), colorFilter = ColorFilter.tint(accent))
        }
    }
}

private val STILL_WIDTH = 120.dp
private const val PLOT_LINES = 4
