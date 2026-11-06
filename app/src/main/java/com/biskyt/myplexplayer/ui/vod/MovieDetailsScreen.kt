package com.biskyt.myplexplayer.ui.vod

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.Text
import com.biskyt.myplexplayer.AppContainer
import com.biskyt.myplexplayer.R
import com.biskyt.myplexplayer.appString
import com.biskyt.myplexplayer.core.vod.CastMember
import com.biskyt.myplexplayer.core.vod.VodDetails
import com.biskyt.myplexplayer.data.db.CAT_MOVIE
import com.biskyt.myplexplayer.data.db.MovieEntity
import com.biskyt.myplexplayer.data.db.WatchProgressEntity
import com.biskyt.myplexplayer.ui.LocalNavigator
import com.biskyt.myplexplayer.ui.Screen
import com.biskyt.myplexplayer.ui.appViewModel
import com.biskyt.myplexplayer.ui.common.AccentFillSurface
import com.biskyt.myplexplayer.ui.common.Backdrop
import com.biskyt.myplexplayer.ui.common.BoardActionsPanel
import com.biskyt.myplexplayer.ui.common.CastPanel
import com.biskyt.myplexplayer.ui.common.CirclePhoto
import com.biskyt.myplexplayer.ui.common.LocalTopBarInset
import com.biskyt.myplexplayer.ui.common.Poster
import com.biskyt.myplexplayer.ui.common.formatDuration
import com.biskyt.myplexplayer.ui.common.markWatched
import com.biskyt.myplexplayer.ui.common.onOptionsKey
import com.biskyt.myplexplayer.ui.common.rememberFirstFocus
import com.biskyt.myplexplayer.ui.common.timeLeft
import com.biskyt.myplexplayer.ui.theme.BOLD
import com.biskyt.myplexplayer.ui.theme.BoardStyle
import com.biskyt.myplexplayer.ui.theme.EXTRA_BOLD
import com.biskyt.myplexplayer.ui.theme.MEDIUM
import com.biskyt.myplexplayer.ui.theme.Palette
import com.biskyt.myplexplayer.ui.theme.bd
import com.biskyt.myplexplayer.ui.theme.boardLineBox
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class MovieDetailsViewModel(private val c: AppContainer, private val playlistId: Long, private val streamId: Long) : ViewModel() {
    var movie by mutableStateOf<MovieEntity?>(null)
        private set
    var details by mutableStateOf<VodDetails?>(null)
        private set

    val progress = c.vod.observeMovieProgress(playlistId, streamId).stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val favourite = c.vod.observeFavourite(playlistId, CAT_MOVIE, streamId).stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** Whether the server lets it be deleted (asked once, then known for the source). */
    suspend fun canDelete() = c.vod.canDelete(playlistId)

    /** The page's own first read of the details: About and Technical info wait for it rather than ask again. */
    private val firstLoad = viewModelScope.async {
        movie = c.vod.movie(playlistId, streamId)
        // The page works without details (title, poster and play come from the list).
        runCatching { c.vod.movieDetails(c.source(playlistId), streamId) }.getOrNull().also { details = it }
    }

    /**
     * The film's details for About and Technical info: the page's (waiting for its first read if it is still under
     * way), else read again, without the play state (a failure is for the panel to show, with Retry).
     */
    suspend fun loadDetails(): VodDetails = details ?: firstLoad.await()
        ?: c.vod.movieDetails(c.source(playlistId), streamId, withState = false).also { details = it }

    fun toggleFavourite() = viewModelScope.launch { c.vod.setFavourite(playlistId, CAT_MOVIE, streamId, !favourite.value) }

    fun setWatched(watched: Boolean) = viewModelScope.launch { c.vod.setWatched(playlistId, streamId, series = null, watched) }

    /** Deletes it on the server, then [gone] (refused or out of reach, a notice says so and the page stays). */
    fun delete(gone: () -> Unit) = viewModelScope.launch { if (c.vod.deleteMovie(playlistId, streamId)) gone() }
}

/** The panels over the film page. */
private enum class FilmPanel { NONE, MORE, CAST, ABOUT, TECH }

@Composable
fun MovieDetailsScreen(screen: Screen.MovieDetails) {
    val vm = appViewModel { MovieDetailsViewModel(it, screen.playlistId, screen.streamId) }
    val nav = LocalNavigator.current
    val movie = vm.movie ?: return
    val progress by vm.progress.collectAsState()
    // What is open over the page: More… (its button, holding OK, or Menu), or a panel opened from it.
    var open by remember { mutableStateOf(FilmPanel.NONE) }
    val more = { open = FilmPanel.MORE }
    FilmPage(
        movie,
        vm.details,
        progress?.takeUnless { it.finished },
        play = { fromStart -> nav.push(Screen.VodPlayer(screen.playlistId, movie.streamId, fromStart = fromStart)) },
        more = more,
        showCast = { open = FilmPanel.CAST }
    )
    FilmPanels(vm, movie, progress?.finished == true, open, { open = it }) { nav.pop() }
}

/** The panel [open] over the film page ([show] changes it): its own composable, so the page does not recompose with it. */
@Composable
@Suppress("LongParameterList") // the page's state, passed down rather than read again
private fun FilmPanels(
    vm: MovieDetailsViewModel,
    movie: MovieEntity,
    watched: Boolean,
    open: FilmPanel,
    show: (FilmPanel) -> Unit,
    gone: () -> Unit
) {
    val d = vm.details
    val close = { show(FilmPanel.NONE) }
    when (open) {
        FilmPanel.NONE -> Unit
        FilmPanel.MORE -> {
            val favourite by vm.favourite.collectAsState()
            val canDelete by produceState(false) { value = vm.canDelete() }
            val actions = listOf(markWatched(watched) { vm.setWatched(it) }, favouriteAction(favourite) { vm.toggleFavourite() }) +
                titleActions(
                    d,
                    showCast = { show(FilmPanel.CAST) },
                    showAbout = { show(FilmPanel.ABOUT) },
                    showTech = { show(FilmPanel.TECH) }
                ) + listOfNotNull(if (canDelete) deleteAction(movie.title) { vm.delete(gone) } else null)
            BoardActionsPanel(movie.title, panelLine(movie.year ?: d?.year, d?.genres.orEmpty()), actions, close)
        }
        FilmPanel.CAST -> CastPanel(movie.title, remember(d) { castAndCrew(d) }, stringResource(R.string.vod_cast_crew), close)
        FilmPanel.ABOUT -> AboutPanel(film = true, d, close, vm::loadDetails)
        FilmPanel.TECH -> TechnicalInfoPanel(d, close, vm::loadDetails)
    }
}

/**
 * The film page (board "PlexFilm"): the backdrop, the poster, then the title, the facts and age rating, the scores,
 * the plot, who directed and wrote it, the picture and sound labels and the buttons; the cast along the bottom. A line
 * with no data is left out. The remote's Menu button opens [more] from anywhere on it.
 */
@Composable
@Suppress("LongParameterList") // the page's state, passed down rather than read again
fun FilmPage(
    movie: MovieEntity,
    d: VodDetails?,
    resumable: WatchProgressEntity?,
    play: (fromStart: Boolean) -> Unit,
    more: () -> Unit,
    showCast: () -> Unit
) {
    Box(Modifier.fillMaxSize().onOptionsKey(more)) {
        Backdrop(d?.backdropUrl)
        Row(
            Modifier.padding(start = 56.bd, end = 56.bd, top = LocalTopBarInset.current),
            horizontalArrangement = Arrangement.spacedBy(32.bd)
        ) {
            Poster(movie.posterUrl ?: d?.posterUrl, movie.title, 180.bd, radius = 10.bd)
            Column(Modifier.widthIn(max = 640.bd), verticalArrangement = Arrangement.spacedBy(8.bd)) {
                Text(
                    movie.title,
                    style = FilmTitle.current,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.boardLineBox(32, 1.1f)
                )
                FilmFacts(movie, d)
                d?.scores?.let { ScoresRow(it) }
                d?.plot?.let { PlotWithMore(movie.title, it, FilmPlot.current, maxLines = 3) }
                d?.let {
                    people(it)
                }?.let { Text(it, style = SmallMuted.current, color = Palette.textDim, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                // 12 above the labels (with the column's 8) and 18 below them, as the board has them.
                d?.media?.badges?.takeIf { it.isNotEmpty() }?.let { LabelRow(it, Modifier.padding(top = 4.bd)) }
                Row(Modifier.padding(top = 10.bd), horizontalArrangement = Arrangement.spacedBy(10.bd)) {
                    FilmButtons(resumable, play, more)
                }
            }
        }
        d?.cast?.takeIf { it.isNotEmpty() }?.let { cast ->
            CastStrip(cast, showCast, Modifier.align(Alignment.BottomStart).padding(start = 56.bd, end = 40.bd, bottom = 14.bd - CARD_PAD))
        }
    }
}

/** "2021 · 1 h 52 min · Drama, Mystery", then the age rating in its box. */
@Composable
private fun FilmFacts(movie: MovieEntity, d: VodDetails?) {
    val facts = listOfNotNull(
        (movie.year ?: d?.year)?.toString(),
        runtimeOf(d?.durationSec),
        genresOf(d?.genres.orEmpty()),
        starRating(d?.rating)
    ).joinToString(" · ")
    val rating = d?.contentRating
    if (facts.isEmpty() && rating == null) return
    Row(horizontalArrangement = Arrangement.spacedBy(10.bd), verticalAlignment = Alignment.CenterVertically) {
        if (facts.isNotEmpty()) {
            Text(
                facts,
                style = FactsText.current,
                color = Palette.textMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false)
            )
        }
        rating?.let { BoardAgeBadge(it) }
    }
}

/**
 * With progress: "Resume · 38 min left" (the progress along its bottom; "Resume from 1:14:02" when the length is not
 * known), Restart, More…. Without: Play, More…. Holding
 * OK on any of them opens [more] too.
 */
@Composable
private fun FilmButtons(resumable: WatchProgressEntity?, play: (Boolean) -> Unit, more: () -> Unit) {
    val started = resumable?.takeIf { it.positionMs > 0 }
    // With its length known: the time left, and the progress along the button. Without: where it stopped.
    val timed = started?.takeIf { it.durationMs > 0 }
    // One button either way, so the focus it has stays when the progress loads after the page.
    DetailButton(
        when {
            timed != null -> stringResource(R.string.vod_resume_left, timeLeft(timed))
            started != null -> stringResource(R.string.vod_resume_from, formatDuration(started.positionMs))
            else -> stringResource(R.string.common_play)
        },
        { play(false) },
        rememberFirstFocus(),
        strong = true,
        progress = timed?.fraction,
        // The board's 12 below: room for the progress bar.
        bottom = if (timed != null) 12.bd else 10.bd,
        onLongClick = more
    )
    if (started != null) DetailButton(stringResource(R.string.vod_restart), { play(true) }, onLongClick = more)
    DetailButton(stringResource(R.string.vod_more), more, onLongClick = more)
}

/**
 * The cast along the bottom of the page: "Cast", then each one's photo (92 square), name and part, 20 apart. OK on one
 * opens Cast and crew. Focus fills the card with the accent colour, with no ring.
 */
@Composable
private fun CastStrip(cast: List<CastMember>, showCast: () -> Unit, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.bd)) {
        Text(stringResource(R.string.vod_cast_title), style = CastHeading.current, color = Palette.textMuted)
        // Each card keeps CARD_PAD round it for its focus, taken back from the gap and the row's start.
        LazyRow(
            Modifier.offset(x = -CARD_PAD),
            horizontalArrangement = Arrangement.spacedBy(20.bd - CARD_PAD * 2),
            contentPadding = PaddingValues(end = CARD_PAD)
        ) {
            items(cast) { person -> CastCard(person, showCast) }
        }
    }
}

@Composable
private fun CastCard(person: CastMember, onClick: () -> Unit) {
    AccentFillSurface(onClick, CardShape) {
        Column(Modifier.padding(CARD_PAD).width(92.bd), verticalArrangement = Arrangement.spacedBy(4.bd)) {
            CirclePhoto(person.photoUrl, person.name, 92.bd, shape = PhotoShape)
            // The surface's content colour: the text's, or the accent's while it has focus; the part is muted without.
            val content = LocalContentColor.current
            Text(person.name, style = CastName.current, color = content, maxLines = 1, overflow = TextOverflow.Ellipsis)
            person.role?.let {
                val muted = if (content == Palette.text) Palette.textDim else content
                Text(it, style = CastRole.current, color = muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

private val CardShape = RoundedCornerShape(12.bd)
private val PhotoShape = RoundedCornerShape(10.bd)
private val CARD_PAD = 4.bd

/** "Directed by Ana Ruiz · Written by Ana Ruiz, Paul Okafor", where known. */
private fun people(d: VodDetails): String? = listOfNotNull(
    d.directors.takeIf { it.isNotEmpty() }?.let { appString(R.string.vod_directed_by, it.joinToString(", ")) },
    d.writers.takeIf { it.isNotEmpty() }?.let { appString(R.string.vod_written_by, it.joinToString(", ")) }
).joinToString(" · ").ifEmpty { null }

/** Under More…'s title: "2021 · Drama, Mystery". */
private fun panelLine(year: Int?, genres: List<String>): String? =
    listOfNotNull(year?.toString(), genresOf(genres)).joinToString(" · ").ifEmpty { null }

// The film page's text (the board's sizes).
private val FilmTitle = BoardStyle(32, EXTRA_BOLD, 1.1f)
private val FactsText = BoardStyle(15, BOLD)
private val FilmPlot = BoardStyle(15, MEDIUM, 1.4f)

/** Directed and written by: 14, muted. */
private val SmallMuted = BoardStyle(14, MEDIUM)
private val CastHeading = BoardStyle(14, EXTRA_BOLD)
private val CastName = BoardStyle(13, EXTRA_BOLD, 1.2f)
private val CastRole = BoardStyle(12, MEDIUM, 1.2f)
