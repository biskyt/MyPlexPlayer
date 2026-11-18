package com.biskyt.myplexplayer.ui.vod

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.tv.material3.Text
import com.biskyt.myplexplayer.AppContainer
import com.biskyt.myplexplayer.R
import com.biskyt.myplexplayer.appString
import com.biskyt.myplexplayer.core.tmdb.TmdbEpisode
import com.biskyt.myplexplayer.core.tmdb.TmdbSeason
import com.biskyt.myplexplayer.core.tmdb.TmdbTitle
import com.biskyt.myplexplayer.core.vod.VodTitles
import com.biskyt.myplexplayer.ui.LocalNavigator
import com.biskyt.myplexplayer.ui.Screen
import com.biskyt.myplexplayer.ui.appViewModel
import com.biskyt.myplexplayer.ui.common.ErrorRetry
import com.biskyt.myplexplayer.ui.common.Muted
import com.biskyt.myplexplayer.ui.common.Poster
import com.biskyt.myplexplayer.ui.common.TrailersPanel
import com.biskyt.myplexplayer.ui.common.formatDate
import com.biskyt.myplexplayer.ui.common.quietFocus
import com.biskyt.myplexplayer.ui.common.rememberFirstFocus
import com.biskyt.myplexplayer.ui.common.rememberTrailerOpener
import com.biskyt.myplexplayer.ui.theme.BoardStyle
import com.biskyt.myplexplayer.ui.theme.MEDIUM
import com.biskyt.myplexplayer.ui.theme.Palette
import com.biskyt.myplexplayer.ui.theme.SEMI_BOLD
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * The title's TMDB data, read once for the screen (and kept while it is on the back stack). MyTVPlayer's page shows it
 * through its TitleInfo, which merges a provider's details: here the page reads TMDB's [TmdbTitle] itself.
 */
class TmdbTitleViewModel(private val c: AppContainer, private val screen: Screen.TmdbTitle) : ViewModel() {
    var info by mutableStateOf<TmdbTitle?>(null)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    /** A series' season whose episodes show, and those episodes (null while they load). */
    var season by mutableStateOf<Int?>(null)
        private set
    var episodes by mutableStateOf<List<TmdbEpisode>?>(null)
        private set

    /** TMDB could not give [season]'s episodes: the page offers Try again. */
    var seasonFailed by mutableStateOf(false)
        private set

    init {
        load()
    }

    fun load() = viewModelScope.launch {
        error = null
        val title = c.tmdb.title(screen.series, screen.tmdbId)
        if (title == null) {
            error = appString(R.string.vod_tmdb_title_failed)
            return@launch
        }
        info = title
        // The season of the next episode, else the newest.
        (title.nextEpisode?.season ?: title.seasons.lastOrNull()?.number)?.let(::showSeason)
    }

    /** The request for the episodes of [season]: a season chosen next stops it, so that its episodes cannot show. */
    private var seasonRequest: Job? = null

    /** Shows [number]'s episodes; the season shown is read again only when it failed (Try again). */
    fun showSeason(number: Int) {
        if (number == season && !seasonFailed) return
        season = number
        episodes = null
        seasonFailed = false
        seasonRequest?.cancel()
        seasonRequest = viewModelScope.launch {
            val read = c.tmdb.season(screen.tmdbId, number)
            if (read == null) seasonFailed = true else episodes = read
        }
    }
}

/**
 * A title from TMDB that the server may not have (from "More like this" or a person's page): the poster, the title,
 * "2023 · Drama, Crime · TMDB 78%", a series' status and next episode, the overview and who made it. A series' seasons
 * and episodes come under it. "Find in your library" (it has the remote) searches the server for the title. Then
 * "Play trailer", where TMDB has one, "Other trailers", where it has more than one, and "More like this". The buttons
 * work while the page loads, and when TMDB cannot be reached.
 */
@Composable
fun TmdbTitleScreen(screen: Screen.TmdbTitle) {
    val vm = appViewModel { TmdbTitleViewModel(it, screen) }
    val nav = LocalNavigator.current
    val info = vm.info
    val playTrailer = rememberTrailerOpener()
    var trailers by rememberSaveable { mutableStateOf(false) }
    val shownSeason = remember { FocusRequester() }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 40.dp)) {
        item {
            Row(Modifier.padding(horizontal = SIDE), horizontalArrangement = Arrangement.spacedBy(40.dp)) {
                Poster(info?.posterUrl ?: screen.posterUrl, screen.title, POSTER_WIDTH)
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(screen.title, style = NameText.current, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    info?.let { aboutLine(it) }?.let { Text(it, style = LineText.current, color = Palette.textDim, maxLines = 1) }
                    val status = seriesStatusOf(null, info)
                    seriesStatusLine(status)?.let { Text(it, style = LineText.current, color = Palette.textDim, maxLines = 1) }
                    nextEpisodeLine(status)?.let { Text(it, style = LineText.current, color = Palette.textDim, maxLines = 1) }
                    info?.overview?.let {
                        Text(
                            it,
                            style = PlotText.current,
                            color = Palette.textMuted,
                            maxLines = PLOT_LINES,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.widthIn(max = 860.dp)
                        )
                    }
                    info?.let {
                        people(it.directors, it.writers)
                    }?.let { Text(it, style = LineText.current, color = Palette.textDim, maxLines = 2) }
                    Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        DetailButton(
                            stringResource(R.string.vod_find_in_library),
                            { nav.push(Screen.Search(screen.title)) },
                            rememberFirstFocus(),
                            strong = true,
                        )
                        // TMDB's title is in the app's language; the server may use the original ("Intouchables").
                        originalTitle(info, screen.title)?.let { original ->
                            DetailButton(stringResource(R.string.vod_find_original, original), { nav.push(Screen.Search(original)) })
                        }
                        info?.trailer?.let { v -> DetailButton(stringResource(R.string.vod_play_trailer), { playTrailer(v.url) }) }
                        if (TitleTrailers(tmdb = info?.trailers.orEmpty()).more) {
                            DetailButton(stringResource(R.string.vod_other_trailers), {
                                trailers =
                                    true
                            })
                        }
                        DetailButton(stringResource(R.string.vod_similar), {
                            nav.push(Screen.MoreLike(screen.playlistId, screen.tmdbId, screen.series, screen.title, screen.tab))
                        })
                    }
                }
            }
        }
        info?.seasons?.takeIf { it.isNotEmpty() }?.let { seasons -> seasons(seasons, vm, shownSeason) }
        vm.error?.let { error -> item { Box(Modifier.padding(start = SIDE, top = 32.dp)) { ErrorRetry(error) { vm.load() } } } }
        if (info == null && vm.error == null) {
            item { Muted(stringResource(R.string.common_loading), Modifier.padding(start = SIDE, top = 32.dp)) }
        }
    }
    if (trailers) info?.let { TrailersPanel(screen.title, it.trailers) { trailers = false } }
}

/**
 * A series' seasons as a row of buttons (the one shown is selected), then that season's episodes: number, name and the
 * date each was first shown. Each episode takes the remote, so that Down scrolls the list.
 */
private fun LazyListScope.seasons(seasons: List<TmdbSeason>, vm: TmdbTitleViewModel, shownSeason: FocusRequester) {
    item {
        LazyRow(
            Modifier.padding(top = 32.dp),
            contentPadding = PaddingValues(horizontal = SIDE),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // As the series page's seasons.
            items(seasons, key = { it.number }) { s ->
                val current = s.number == vm.season
                val modifier = if (current) Modifier.focusRequester(shownSeason) else Modifier
                SeasonPill(seasonName(s.number, s.name), current, modifier) { vm.showSeason(s.number) }
            }
        }
    }
    val episodes = vm.episodes
    val season = vm.season
    when {
        season == null || episodes != null -> Unit
        vm.seasonFailed -> item {
            Box(Modifier.padding(start = SIDE, top = 16.dp)) {
                // The button goes while the season loads: the remote goes to the season's own button.
                ErrorRetry(stringResource(R.string.vod_tmdb_season_failed), shownSeason) { vm.showSeason(season) }
            }
        }
        else -> item { Muted(stringResource(R.string.common_loading), Modifier.padding(start = SIDE, top = 16.dp)) }
    }
    items(episodes.orEmpty(), key = { "${it.season}:${it.number}" }) { EpisodeLine(it) }
}

/** "4  The Return  ·  4 Dec 2026", shown quietly (a dark fill behind it) while it has the remote. */
@Composable
private fun EpisodeLine(e: TmdbEpisode) {
    Row(
        Modifier.padding(horizontal = SIDE).quietFocus().padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        Text(e.number.toString(), style = LineText.current, color = Palette.textDim, modifier = Modifier.width(40.dp))
        Text(
            e.name ?: stringResource(R.string.vod_episode, e.number),
            style = LineText.current,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        e.airDate?.let { Text(formatDate(it), style = LineText.current, color = Palette.textDim) }
    }
}

/** "2023 · Drama, Crime · TMDB 78%": the parts that are known; null when none is. */
@Composable
private fun aboutLine(info: TmdbTitle): String? = listOfNotNull(
    info.released?.year?.toString(),
    genresOf(info.genres),
    info.score?.let { stringResource(R.string.vod_score_tmdb_value, percentOf(it)) },
).joinToString(" · ").ifEmpty { null }

/** TMDB's original title, when a search for it can find more than one for [title]; else null. */
private fun originalTitle(info: TmdbTitle?, title: String): String? =
    info?.originalTitle?.takeIf { VodTitles.key(it) != VodTitles.key(title) }

private const val PLOT_LINES = 6
private val POSTER_WIDTH = 200.dp

private val NameText = BoardStyle(40, SEMI_BOLD, 1.1f)
private val LineText = BoardStyle(15, MEDIUM)
private val PlotText = BoardStyle(16, MEDIUM, 1.5f)
