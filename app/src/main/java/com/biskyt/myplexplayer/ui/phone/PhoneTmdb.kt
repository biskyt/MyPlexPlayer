package com.biskyt.myplexplayer.ui.phone

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridItemSpanScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.biskyt.myplexplayer.R
import com.biskyt.myplexplayer.core.tmdb.TmdbEpisode
import com.biskyt.myplexplayer.core.tmdb.TmdbTitle
import com.biskyt.myplexplayer.ui.LocalNavigator
import com.biskyt.myplexplayer.ui.Screen
import com.biskyt.myplexplayer.ui.common.AppIcons
import com.biskyt.myplexplayer.ui.common.ErrorRetry
import com.biskyt.myplexplayer.ui.common.Muted
import com.biskyt.myplexplayer.ui.common.TrailersPanel
import com.biskyt.myplexplayer.ui.common.formatDate
import com.biskyt.myplexplayer.ui.common.rememberTrailerOpener
import com.biskyt.myplexplayer.ui.theme.FormFactor
import com.biskyt.myplexplayer.ui.theme.LocalFormFactor
import com.biskyt.myplexplayer.ui.theme.Palette
import com.biskyt.myplexplayer.ui.vod.MoreLikeViewModel
import com.biskyt.myplexplayer.ui.vod.TitleTrailers
import com.biskyt.myplexplayer.ui.vod.TmdbTitleViewModel
import com.biskyt.myplexplayer.ui.vod.aboutLine
import com.biskyt.myplexplayer.ui.vod.nextEpisodeLine
import com.biskyt.myplexplayer.ui.vod.originalTitle
import com.biskyt.myplexplayer.ui.vod.people
import com.biskyt.myplexplayer.ui.vod.seasonName
import com.biskyt.myplexplayer.ui.vod.seriesStatusLine
import com.biskyt.myplexplayer.ui.vod.seriesStatusOf
import com.biskyt.myplexplayer.ui.vod.similarLine
import com.biskyt.myplexplayer.ui.vod.similarScreen

// The pages fed from TMDB on a phone or an unfolded foldable (the owner, 2026-11-26): "More like this" and a title that
// the server may not have. They come under the tab they were opened from, with a back button. A person's page is in
// PhonePerson.kt.

/** A page's top: the back button, then [title] in large type, two lines at most. */
@Composable
internal fun PageTop(title: String) {
    val nav = LocalNavigator.current
    Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = PhoneGutter, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        PhoneBackButton({ nav.back() }, Modifier.padding(end = 4.dp))
        Text(
            title,
            color = Palette.text,
            fontSize = 22.sp,
            lineHeight = 26.sp,
            fontWeight = FontWeight.Black,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** The error with Try again, or Loading until [loaded]; nothing once it is. */
@Composable
internal fun PhoneLoadState(error: String?, loaded: Boolean, modifier: Modifier = Modifier, retry: () -> Unit) {
    when {
        error != null -> Box(modifier) { ErrorRetry(error, onRetry = retry) }
        !loaded -> Muted(stringResource(R.string.common_loading), modifier)
    }
}

/**
 * "More like Dune", then the suggestions in a grid, as the Movies and TV Shows tabs show posters. A tap on one on the
 * server opens its page there; on another, its TMDB page.
 */
@Composable
fun PhoneMoreLike(screen: Screen.MoreLike, vm: MoreLikeViewModel) {
    val wide = LocalFormFactor.current == FormFactor.UNFOLDED
    PhoneTabFrame(screen.tab) {
        Column(Modifier.fillMaxSize().background(Palette.background)) {
            PageTop(stringResource(R.string.vod_similar_title, screen.title))
            SimilarGrid(screen, vm, wide)
        }
    }
}

/** The suggestions, as many across as [gridColumns] gives; or loading, Try again, or a line that says there are none. */
@Composable
private fun SimilarGrid(screen: Screen.MoreLike, vm: MoreLikeViewModel, wide: Boolean) {
    val nav = LocalNavigator.current
    val page = vm.page
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val (columns, width) = gridColumns(maxWidth, wide)
        LazyVerticalGrid(
            columns = GridCells.Fixed(columns),
            contentPadding = PaddingValues(start = PhoneGutter, end = PhoneGutter, top = 8.dp, bottom = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(GRID_GAP),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            val whole: LazyGridItemSpanScope.() -> GridItemSpan = { GridItemSpan(maxLineSpan) }
            when {
                vm.error != null || page == null -> item(span = whole) { PhoneLoadState(vm.error, loaded = false) { vm.load() } }
                page.titles.isEmpty() -> item(span = whole) { Muted(stringResource(R.string.vod_similar_none), maxLines = 3) }
                else -> items(page.titles, key = { it.suggestion.id }) { t ->
                    val s = t.suggestion
                    val card = t.card
                    val open = { nav.push(similarScreen(screen, t)) }
                    PhonePoster(
                        card?.imageUrl ?: s.posterUrl,
                        card?.title ?: s.title,
                        similarLine(card?.year ?: s.year, s.score, card != null).orEmpty(),
                        width = width,
                        onLongClick = open,
                        onClick = open
                    )
                }
            }
        }
    }
}

/**
 * A title from TMDB that the server may not have, as a film's page shows one: the backdrop, the poster beside the title
 * and its facts, then "Find in your library" across the page, and Trailer, Other trailers and More like this. Then the
 * overview, who made it, and a series' seasons and episodes.
 */
@Composable
fun PhoneTmdbTitle(screen: Screen.TmdbTitle, vm: TmdbTitleViewModel) {
    val info = vm.info
    var trailers by rememberSaveable { mutableStateOf(false) }
    PhoneDetailLayout(
        screen.tab,
        left = {
            // TMDB's answer is the whole page: once it is in (or failed), no backdrop is still to come (see PhoneDetailHeader).
            PhoneDetailHeader(
                PhoneArt(listOfNotNull(info?.backdropUrl), settled = info != null || vm.error != null),
                info?.posterUrl ?: screen.posterUrl,
                screen.title
            ) {
                info?.let { aboutLine(it) }?.let { PhoneFacts(it) }
                val status = seriesStatusOf(null, info)
                seriesStatusLine(status)?.let { PhoneSmallText(it, maxLines = 2) }
                nextEpisodeLine(status)?.let { PhoneSmallText(it, maxLines = 2) }
            }
            TitleButtons(screen, info) { trailers = true }
        },
        right = { unfolded ->
            info?.overview?.let { PhonePlot(screen.title, it, if (unfolded) UNFOLDED_PLOT_LINES else PLOT_LINES) }
            info?.let { people(it.directors, it.writers) }?.let {
                PhoneSmallText(it, Modifier.padding(horizontal = PhoneGutter), maxLines = 3)
            }
            info?.seasons?.takeIf { it.isNotEmpty() }?.let { seasons ->
                PhoneChipRow(seasons, { it.number == vm.season }, { it.number }) { s, chosen ->
                    PhoneChoiceChip(seasonName(s.number, s.name), chosen) { vm.showSeason(s.number) }
                }
                Episodes(vm)
            }
            PhoneLoadState(vm.error, info != null, Modifier.padding(horizontal = PhoneGutter)) { vm.load() }
        }
    )
    if (trailers) info?.let { TrailersPanel(screen.title, it.trailers) { trailers = false } }
}

/** "Find in your library" (and by the original title, when it differs), then Trailer, Other trailers and More like this. */
@Composable
private fun TitleButtons(screen: Screen.TmdbTitle, info: TmdbTitle?, otherTrailers: () -> Unit) {
    val nav = LocalNavigator.current
    val playTrailer = rememberTrailerOpener()
    Column(Modifier.padding(start = PhoneGutter, end = PhoneGutter, top = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        PhoneMainButton(stringResource(R.string.vod_find_in_library), null, icon = AppIcons.Search) {
            nav.push(Screen.Search(screen.title))
        }
        // TMDB's title is in the app's language; the server may use the original ("Intouchables").
        originalTitle(info, screen.title)?.let { original ->
            PhonePill(stringResource(R.string.vod_find_original, original), Modifier.fillMaxWidth()) {
                nav.push(Screen.Search(original))
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            val tile = Modifier.weight(1f)
            info?.trailer?.let { v -> PhoneTileButton(AppIcons.Trailer, stringResource(R.string.vod_trailer), tile) { playTrailer(v.url) } }
            if (TitleTrailers(tmdb = info?.trailers.orEmpty()).more) {
                PhoneTileButton(AppIcons.Trailer, stringResource(R.string.vod_other_trailers), tile, onClick = otherTrailers)
            }
            PhoneTileButton(AppIcons.Film, stringResource(R.string.vod_similar), tile) {
                nav.push(Screen.MoreLike(screen.playlistId, screen.tmdbId, screen.series, screen.title, screen.tab))
            }
        }
    }
}

/** The season's episodes: number, name and the date each was first shown; or loading, or Try again. */
@Composable
private fun Episodes(vm: TmdbTitleViewModel) {
    val season = vm.season ?: return
    val episodes = vm.episodes
    val below = Modifier.padding(horizontal = PhoneGutter)
    when {
        episodes != null -> episodes.forEach { EpisodeLine(it) }
        vm.seasonFailed -> Box(below) { ErrorRetry(stringResource(R.string.vod_tmdb_season_failed)) { vm.showSeason(season) } }
        else -> Muted(stringResource(R.string.common_loading), below)
    }
}

@Composable
private fun EpisodeLine(e: TmdbEpisode) {
    Row(Modifier.padding(horizontal = PhoneGutter), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(e.number.toString(), color = Palette.textDim, fontSize = 14.sp, modifier = Modifier.width(28.dp))
        Text(
            e.name ?: stringResource(R.string.vod_episode, e.number),
            color = Palette.text,
            fontSize = 15.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        e.airDate?.let { Text(formatDate(it), color = Palette.textDim, fontSize = 13.sp) }
    }
}
