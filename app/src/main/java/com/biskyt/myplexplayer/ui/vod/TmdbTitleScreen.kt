package com.biskyt.myplexplayer.ui.vod

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.tv.material3.Text
import com.biskyt.myplexplayer.AppContainer
import com.biskyt.myplexplayer.R
import com.biskyt.myplexplayer.appString
import com.biskyt.myplexplayer.core.tmdb.TmdbTitle
import com.biskyt.myplexplayer.ui.LocalNavigator
import com.biskyt.myplexplayer.ui.Screen
import com.biskyt.myplexplayer.ui.appViewModel
import com.biskyt.myplexplayer.ui.common.ErrorRetry
import com.biskyt.myplexplayer.ui.common.Muted
import com.biskyt.myplexplayer.ui.common.Poster
import com.biskyt.myplexplayer.ui.common.rememberFirstFocus
import com.biskyt.myplexplayer.ui.common.rememberTrailerOpener
import com.biskyt.myplexplayer.ui.theme.BoardStyle
import com.biskyt.myplexplayer.ui.theme.MEDIUM
import com.biskyt.myplexplayer.ui.theme.Palette
import com.biskyt.myplexplayer.ui.theme.SEMI_BOLD
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

    init {
        load()
    }

    fun load() = viewModelScope.launch {
        error = null
        val title = c.tmdb.title(screen.series, screen.tmdbId)
        if (title == null) error = appString(R.string.vod_tmdb_title_failed) else info = title
    }
}

/**
 * A title from TMDB that the server may not have (from "More like this" or a person's page): the poster, the title,
 * "2023 · Drama, Crime · TMDB 78%", the overview and who made it. "Find in your library" (it has the remote) searches
 * the server for the title. Then "Play trailer", where TMDB has one, and "More like this". The buttons work while the
 * page loads, and when TMDB cannot be reached.
 */
@Composable
fun TmdbTitleScreen(screen: Screen.TmdbTitle) {
    val vm = appViewModel { TmdbTitleViewModel(it, screen) }
    val nav = LocalNavigator.current
    val info = vm.info
    val playTrailer = rememberTrailerOpener()
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 40.dp)) {
        item {
            Row(Modifier.padding(horizontal = SIDE), horizontalArrangement = Arrangement.spacedBy(40.dp)) {
                Poster(info?.posterUrl ?: screen.posterUrl, screen.title, POSTER_WIDTH)
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(screen.title, style = NameText.current, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    info?.let { aboutLine(it) }?.let { Text(it, style = LineText.current, color = Palette.textDim, maxLines = 1) }
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
                        info?.trailer?.let { id -> DetailButton(stringResource(R.string.vod_play_trailer), { playTrailer(id) }) }
                        DetailButton(stringResource(R.string.vod_similar), {
                            nav.push(Screen.MoreLike(screen.playlistId, screen.tmdbId, screen.series, screen.title, screen.tab))
                        })
                    }
                }
            }
        }
        vm.error?.let { error -> item { Box(Modifier.padding(start = SIDE, top = 32.dp)) { ErrorRetry(error) { vm.load() } } } }
        if (info == null && vm.error == null) {
            item { Muted(stringResource(R.string.common_loading), Modifier.padding(start = SIDE, top = 32.dp)) }
        }
    }
}

/** "2023 · Drama, Crime · TMDB 78%": the parts that are known; null when none is. */
@Composable
private fun aboutLine(info: TmdbTitle): String? = listOfNotNull(
    info.released?.year?.toString(),
    genresOf(info.genres),
    info.score?.let { stringResource(R.string.vod_score_tmdb_value, percentOf(it)) },
).joinToString(" · ").ifEmpty { null }

private const val PLOT_LINES = 6
private val POSTER_WIDTH = 200.dp

private val NameText = BoardStyle(40, SEMI_BOLD, 1.1f)
private val LineText = BoardStyle(15, MEDIUM)
private val PlotText = BoardStyle(16, MEDIUM, 1.5f)
