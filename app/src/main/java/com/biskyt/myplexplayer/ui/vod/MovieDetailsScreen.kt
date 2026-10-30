package com.biskyt.myplexplayer.ui.vod

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.allowRgb565
import com.biskyt.myplexplayer.AppContainer
import com.biskyt.myplexplayer.AppText
import com.biskyt.myplexplayer.R
import com.biskyt.myplexplayer.core.vod.VodDetails
import com.biskyt.myplexplayer.data.db.CAT_MOVIE
import com.biskyt.myplexplayer.data.db.MovieEntity
import com.biskyt.myplexplayer.data.db.WatchProgressEntity
import com.biskyt.myplexplayer.ui.LocalNavigator
import com.biskyt.myplexplayer.ui.Screen
import com.biskyt.myplexplayer.ui.appViewModel
import com.biskyt.myplexplayer.ui.common.BadgeRow
import com.biskyt.myplexplayer.ui.common.CastPanel
import com.biskyt.myplexplayer.ui.common.CastRow
import com.biskyt.myplexplayer.ui.common.ItemActionsPanel
import com.biskyt.myplexplayer.ui.common.Muted
import com.biskyt.myplexplayer.ui.common.PillButton
import com.biskyt.myplexplayer.ui.common.Poster
import com.biskyt.myplexplayer.ui.common.ProgressLine
import com.biskyt.myplexplayer.ui.common.formatDuration
import com.biskyt.myplexplayer.ui.common.formatRuntime
import com.biskyt.myplexplayer.ui.common.markWatched
import com.biskyt.myplexplayer.ui.common.onOptionsKey
import com.biskyt.myplexplayer.ui.common.rememberFirstFocus
import com.biskyt.myplexplayer.ui.common.titleActions
import com.biskyt.myplexplayer.ui.theme.Palette
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

    /** Its options are open (holding OK, or Menu), or its cast. */
    var optionsOpen by mutableStateOf(false)
    var castOpen by mutableStateOf(false)

    /** Whether the server lets it be deleted (asked once, then known for the source). */
    suspend fun canDelete() = c.vod.canDelete(playlistId)

    init {
        viewModelScope.launch {
            movie = c.vod.movie(playlistId, streamId)
            val p = c.playlists.get(playlistId) ?: return@launch
            // The page works without details (title, poster and play come from the list).
            details = runCatching { c.vod.movieDetails(p, streamId) }.getOrNull()
        }
    }

    fun openOptions() {
        optionsOpen = true
    }

    fun toggleFavourite() = viewModelScope.launch { c.vod.setFavourite(playlistId, CAT_MOVIE, streamId, !favourite.value) }

    fun setWatched(watched: Boolean) = viewModelScope.launch { c.vod.setWatched(playlistId, streamId, series = null, watched) }

    /** Deletes it on the server, then [gone] (refused or out of reach, a notice says so and the page stays). */
    fun delete(gone: () -> Unit) = viewModelScope.launch { if (c.vod.deleteMovie(playlistId, streamId)) gone() }
}

@Composable
fun MovieDetailsScreen(screen: Screen.MovieDetails) {
    val vm = appViewModel { MovieDetailsViewModel(it, screen.playlistId, screen.streamId) }
    val nav = LocalNavigator.current
    val movie = vm.movie ?: return
    val d = vm.details
    val progress by vm.progress.collectAsState()
    DetailsLayout(
        backdropUrl = d?.backdropUrl,
        posterUrl = movie.posterUrl ?: d?.posterUrl,
        title = movie.title,
        onOptions = vm::openOptions
    ) {
        Text(movie.title, style = MaterialTheme.typography.displaySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        DetailsLine(
            yearAndRuntime(movie.year ?: d?.year, d?.durationSec),
            d?.contentRating,
            listOfNotNull(genresOf(d?.genres.orEmpty()), starRating(d?.rating)),
            d?.scores.orEmpty()
        )
        BadgeRow(d?.media?.badges.orEmpty())
        d?.plot?.let { Text(it, style = MaterialTheme.typography.bodyLarge, maxLines = 4, overflow = TextOverflow.Ellipsis) }
        People(d)
        ResumeBar(progress)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 8.dp)) {
            val resumable = progress?.takeUnless { it.finished }
            PillButton(
                resumable?.let { stringResource(R.string.vod_resume_from, formatDuration(it.positionMs)) }
                    ?: stringResource(R.string.common_play),
                { nav.push(Screen.VodPlayer(screen.playlistId, movie.streamId)) },
                rememberFirstFocus(),
                primary = true,
                onLongClick = vm::openOptions
            )
            if (resumable != null) {
                PillButton(stringResource(R.string.common_from_start), {
                    nav.push(Screen.VodPlayer(screen.playlistId, movie.streamId, fromStart = true))
                }, onLongClick = vm::openOptions)
            }
            PillButton(
                stringResource(R.string.vod_more_info),
                { nav.push(Screen.MoreInfo(screen.playlistId, CAT_MOVIE, movie.streamId)) },
                onLongClick = vm::openOptions
            )
        }
        Muted(stringResource(R.string.vod_options_hint))
        Spacer(Modifier.weight(1f))
        CastRow(d?.cast.orEmpty())
    }
    if (vm.optionsOpen) {
        val favourite by vm.favourite.collectAsState()
        val canDelete by produceState(false) { value = vm.canDelete() }
        val close = { vm.optionsOpen = false }
        val actions = titleActions(
            screen.playlistId,
            CAT_MOVIE,
            movie.streamId,
            d,
            favourite,
            showCast = {
                close()
                vm.castOpen = true
            },
            setFavourite = { vm.toggleFavourite() }
        ).let { shared ->
            listOf(markWatched(progress?.finished == true) { vm.setWatched(it) }) + shared +
                listOfNotNull(if (canDelete) deleteAction(movie.title) { vm.delete { nav.pop() } } else null)
        }
        ItemActionsPanel(movie.title, actions, close)
    }
    if (vm.castOpen) CastPanel(movie.title, d?.cast.orEmpty()) { vm.castOpen = false }
}

/** "Directed by …" and "Written by …", where known. */
@Composable
internal fun People(d: VodDetails?) {
    d?.directors?.takeIf { it.isNotEmpty() }?.let { Muted(stringResource(R.string.vod_directed_by, it.joinToString(", "))) }
    d?.writers?.takeIf { it.isNotEmpty() }?.let { Muted(stringResource(R.string.vod_written_by, it.joinToString(", "))) }
}

/** "2021", "1 h 52 min": whichever is known. */
fun yearAndRuntime(year: Int?, durationSec: Int?): List<String> =
    listOfNotNull(year?.toString(), durationSec?.takeIf { it > 0 }?.let(::formatRuntime))

/** Two genres at most: "Drama, Mystery". */
fun genresOf(genres: List<String>): String? = genres.take(2).takeIf { it.isNotEmpty() }?.joinToString(", ")

/** "★ 8.1". */
fun starRating(rating: Double?): String? = rating?.let { "★ %.1f".format(AppText.locale, it) }

@Composable
internal fun ResumeBar(progress: WatchProgressEntity?) {
    if (progress == null || progress.finished || progress.durationMs <= 0) return
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.widthIn(max = 520.dp)) {
        ProgressLine(progress.fraction, Modifier.weight(1f).padding(top = 8.dp))
        Muted(stringResource(R.string.common_time_left, formatDuration(progress.remainingMs)))
    }
}

/**
 * Backdrop faded behind (once the details are in: the page shows first), poster on the left, [content] on the right.
 * The remote's options button opens [onOptions] from anywhere on it.
 */
@Composable
internal fun DetailsLayout(
    backdropUrl: String?,
    posterUrl: String?,
    title: String,
    onOptions: () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    Box(Modifier.fillMaxSize().background(Palette.background).onOptionsKey(onOptions)) {
        Backdrop(backdropUrl)
        // Under the top bar (TabScaffold), which leaves its own gap above the page.
        Row(Modifier.padding(start = 72.dp, end = 72.dp, top = 8.dp, bottom = 40.dp), horizontalArrangement = Arrangement.spacedBy(40.dp)) {
            Poster(posterUrl, title, 240.dp)
            Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.widthIn(max = 760.dp).fillMaxHeight()) {
                content()
            }
        }
    }
}

/** The title's art, faded behind a page. */
@Composable
internal fun Backdrop(backdropUrl: String?) {
    if (backdropUrl != null) {
        // Faded behind a gradient, so a third of the screen's resolution is plenty (and a ninth of the memory).
        val context = LocalContext.current
        val request = remember(backdropUrl) {
            ImageRequest.Builder(context).data(backdropUrl).size(BACKDROP_WIDTH, BACKDROP_WIDTH * 9 / 16).allowRgb565(true).build()
        }
        AsyncImage(request, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize(), alpha = 0.35f)
        Box(
            Modifier.fillMaxSize()
                .background(Brush.horizontalGradient(listOf(Palette.background, Palette.background.copy(alpha = 0.6f))))
        )
    }
}

private const val BACKDROP_WIDTH = 640
