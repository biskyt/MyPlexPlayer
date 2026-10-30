package com.biskyt.myplexplayer.ui.vod

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.biskyt.myplexplayer.AppContainer
import com.biskyt.myplexplayer.R
import com.biskyt.myplexplayer.appString
import com.biskyt.myplexplayer.core.vod.EpisodeInfo
import com.biskyt.myplexplayer.core.vod.SeasonInfo
import com.biskyt.myplexplayer.core.vod.SeriesDetails
import com.biskyt.myplexplayer.data.db.CAT_SERIES
import com.biskyt.myplexplayer.data.db.SeriesEntity
import com.biskyt.myplexplayer.data.db.WatchProgressEntity
import com.biskyt.myplexplayer.ui.LocalNavigator
import com.biskyt.myplexplayer.ui.Screen
import com.biskyt.myplexplayer.ui.appViewModel
import com.biskyt.myplexplayer.ui.common.BadgeRow
import com.biskyt.myplexplayer.ui.common.CastPanel
import com.biskyt.myplexplayer.ui.common.ItemAction
import com.biskyt.myplexplayer.ui.common.ItemActionsPanel
import com.biskyt.myplexplayer.ui.common.LocalCardFocused
import com.biskyt.myplexplayer.ui.common.LocalTopBarInset
import com.biskyt.myplexplayer.ui.common.Muted
import com.biskyt.myplexplayer.ui.common.PillButton
import com.biskyt.myplexplayer.ui.common.Poster
import com.biskyt.myplexplayer.ui.common.ProgressLine
import com.biskyt.myplexplayer.ui.common.TextPanel
import com.biskyt.myplexplayer.ui.common.TvCard
import com.biskyt.myplexplayer.ui.common.formatDuration
import com.biskyt.myplexplayer.ui.common.formatRuntime
import com.biskyt.myplexplayer.ui.common.markWatched
import com.biskyt.myplexplayer.ui.common.onOptionsKey
import com.biskyt.myplexplayer.ui.common.rememberFirstFocus
import com.biskyt.myplexplayer.ui.common.restoreFocusIf
import com.biskyt.myplexplayer.ui.common.titleActions
import com.biskyt.myplexplayer.ui.player.episodeName
import com.biskyt.myplexplayer.ui.theme.LocalAccent
import com.biskyt.myplexplayer.ui.theme.Palette
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SeriesDetailsViewModel(
    private val c: AppContainer,
    private val playlistId: Long,
    private val seriesId: Long,
    season: Int?
) : ViewModel() {
    var series by mutableStateOf<SeriesEntity?>(null)
        private set
    var details by mutableStateOf<SeriesDetails?>(null)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    /** The season asked for or picked (until then, the one with the episode to carry on with). */
    var season by mutableStateOf(season)

    /** An episode whose options are open (holding OK on it). */
    var optionsFor by mutableStateOf<EpisodeInfo?>(null)

    /** An episode to give the remote to (the one to carry on with, or one asked for from the TV guide), until it has. */
    var focusEpisodePending by mutableStateOf(true)

    /** Progress per episode id. */
    val progress = c.vod.observeSeriesProgress(playlistId, seriesId).map { list -> list.associateBy { it.itemId } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())
    val favourite = c.vod.observeFavourite(playlistId, CAT_SERIES, seriesId).stateIn(viewModelScope, SharingStarted.Eagerly, false)

    init {
        load()
    }

    fun load() = viewModelScope.launch {
        error = null
        series = c.vod.series(playlistId, seriesId)
        val p = c.playlists.get(playlistId) ?: return@launch
        try {
            val d = c.vod.seriesDetails(p, seriesId)
            details = d
        } catch (e: java.io.IOException) {
            error = e.message ?: appString(R.string.vod_episodes_failed)
        }
    }

    /** Marks [e] watched or unwatched on the server, and its row follows (out of reach, nothing changes). */
    fun setWatched(e: EpisodeInfo, watched: Boolean) {
        // The list stays on this season (it otherwise follows the episode to carry on with, which this may move on),
        // and the remote stays on [e].
        if (season == null) season = e.season
        changedInList = true
        viewModelScope.launch { c.vod.setWatched(playlistId, e.id, seriesId, watched) }
    }

    /** A change made in the list (marking, deleting): the next change of the episode to carry on with keeps the remote put. */
    private var changedInList = false

    /** An episode is played: back from it, the next change of the one to carry on with gets the remote again. */
    fun played() {
        changedInList = false
    }

    /** The episode to carry on with changed (or the page opened): it gets the remote, unless the list changed it. */
    fun nextChanged() {
        if (changedInList) changedInList = false else focusEpisodePending = true
    }

    /** The show's cast is open. */
    var castOpen by mutableStateOf(false)

    /** The episode whose description is open. */
    var describing by mutableStateOf<EpisodeInfo?>(null)

    /** Whether the remote is in the episode list. */
    var listFocused by mutableStateOf(false)

    fun toggleFavourite() = viewModelScope.launch { c.vod.setFavourite(playlistId, CAT_SERIES, seriesId, !favourite.value) }

    /** Whether the server lets episodes be deleted (asked once, then known for the source). */
    suspend fun canDelete() = c.vod.canDelete(playlistId)

    /** The series' "Delete watched episodes", read when an episode's options first open. */
    var autoDelete: AutoDeleteChoice? = null
        private set

    fun openOptions(e: EpisodeInfo) {
        optionsFor = e
        if (autoDelete == null) autoDelete = AutoDeleteChoice(viewModelScope, c.vod, playlistId, seriesId)
    }

    /** Deletes [e] on the server; the list follows (refused or out of reach, a notice says so). */
    fun delete(e: EpisodeInfo) {
        if (season == null) season = e.season
        changedInList = true
        viewModelScope.launch { if (c.vod.deleteEpisode(playlistId, seriesId, e.id)) load() }
    }
}

/**
 * Where to carry on: the last episode watched if it isn't finished, otherwise the one after it; the first episode
 * when nothing has been watched yet.
 */
fun continueEpisode(episodes: List<EpisodeInfo>, progress: Map<Long, WatchProgressEntity>): EpisodeInfo? {
    val last = progress.values.maxByOrNull { it.updatedAt } ?: return episodes.firstOrNull()
    val index = episodes.indexOfFirst { it.id == last.itemId }
    if (index < 0) return episodes.firstOrNull()
    return if (last.finished) episodes.getOrNull(index + 1) ?: episodes[index] else episodes[index]
}

@Composable
fun SeriesDetailsScreen(screen: Screen.SeriesDetails) {
    val vm = appViewModel { SeriesDetailsViewModel(it, screen.playlistId, screen.seriesId, screen.season) }
    val show = vm.series ?: return
    val d = vm.details
    val progress by vm.progress.collectAsState()
    val next = remember(d, progress) { d?.let { continueEpisode(it.episodes, progress) } }
    // Opened for one episode (from the TV guide): it has the remote rather than the one to carry on with.
    val asked = remember(d) { d?.episodes?.firstOrNull { it.season == screen.season && it.number == screen.episode } }
    // The one to carry on with has the remote whenever it changes (back from playing), unless a change made in the list
    // moved it (marking one watched), which leaves the remote where it was.
    LaunchedEffect(next?.id) { vm.nextChanged() }
    val target = asked ?: next
    // A season the server doesn't have (the guide may number them differently) shows the usual one.
    val season =
        vm.season?.takeIf { s -> d?.episodes?.any { it.season == s } == true } ?: target?.season ?: d?.seasons?.firstOrNull()?.number
    val seasonInfo = d?.seasons?.firstOrNull { it.number == season }

    Box(Modifier.fillMaxSize().onOptionsKey { target?.let(vm::openOptions) }) {
        Backdrop(d?.info?.backdropUrl)
        // The art goes behind the top bar, the rest below it.
        Row(
            Modifier.padding(start = 64.dp, end = 64.dp, top = LocalTopBarInset.current + 8.dp, bottom = 32.dp),
            horizontalArrangement = Arrangement.spacedBy(40.dp)
        ) {
            Column(Modifier.width(380.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Poster(seasonInfo?.posterUrl ?: show.coverUrl ?: d?.info?.posterUrl, show.title, 180.dp)
                Text(show.title, style = MaterialTheme.typography.headlineMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                ShowLine(seasonInfo, d)
                d?.info?.plot?.let { Text(it, style = MaterialTheme.typography.bodyLarge, maxLines = 5, overflow = TextOverflow.Ellipsis) }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when {
                    vm.error != null -> Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Muted(vm.error.orEmpty())
                        PillButton(stringResource(R.string.common_retry), { vm.load() }, rememberFirstFocus())
                    }
                    d == null -> Muted(stringResource(R.string.vod_loading_episodes))
                    d.episodes.isEmpty() -> Muted(stringResource(R.string.vod_no_episodes))
                    else -> Episodes(vm, d, season, progress, target, screen)
                }
            }
        }
    }
    vm.optionsFor?.let { e -> EpisodeOptions(vm, show.title, d, e, progress[e.id], screen) }
    if (vm.castOpen) CastPanel(show.title, d?.info?.cast.orEmpty()) { vm.castOpen = false }
    vm.describing?.let { e -> e.plot?.let { TextPanel(show.title, episodeName(e), it) { vm.describing = null } } }
}

/** "Series 2 · 2023 · 3 series · [15] · Crime" and the show's scores: the season shown, its year, how many, rating and genres. */
@Composable
private fun ShowLine(season: SeasonInfo?, d: SeriesDetails?) = DetailsLine(
    listOfNotNull(
        season?.let { it.name ?: stringResource(R.string.vod_season, it.number) },
        season?.year?.toString(),
        d?.seasons?.size?.takeIf { it > 1 }?.let { pluralStringResource(R.plurals.vod_season_count, it, it) }
    ),
    d?.info?.contentRating,
    listOfNotNull(genresOf(d?.info?.genres.orEmpty())),
    d?.info?.scores.orEmpty()
)

@Composable
@Suppress("LongParameterList") // the page's state, passed down rather than read again
private fun Episodes(
    vm: SeriesDetailsViewModel,
    d: SeriesDetails,
    season: Int?,
    progress: Map<Long, WatchProgressEntity>,
    target: EpisodeInfo?,
    screen: Screen.SeriesDetails
) {
    val nav = LocalNavigator.current
    if (d.seasons.size > 1) {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(horizontal = 12.dp)) {
            items(d.seasons, key = { it.number }) { s ->
                PillButton(s.name ?: stringResource(R.string.vod_season, s.number), { vm.season = s.number }, selected = s.number == season)
            }
        }
    }
    val shown = remember(d, season) { d.episodes.filter { season == null || it.season == season } }
    val list = rememberLazyListState()
    // Scrolled into view first, so the row exists to take focus.
    LaunchedEffect(target, shown, vm.focusEpisodePending) {
        val i = shown.indexOf(target)
        if (vm.focusEpisodePending && i >= 0) list.scrollToItem(i)
    }
    LazyColumn(
        state = list,
        modifier = Modifier.onFocusChanged { vm.listFocused = it.hasFocus },
        verticalArrangement = Arrangement.spacedBy(6.dp),
        contentPadding = PaddingValues(bottom = 24.dp)
    ) {
        items(shown, key = { it.id }) { e ->
            val focus = Modifier.restoreFocusIf(vm.focusEpisodePending && e == target) { vm.focusEpisodePending = false }
            EpisodeRow(e, progress[e.id], focus, onLongClick = { vm.openOptions(e) }) {
                vm.played()
                nav.push(Screen.VodPlayer(screen.playlistId, e.id, screen.seriesId))
            }
        }
    }
}

/** An episode's options: this episode's, then the show's (shared with the player's). */
@Composable
@Suppress("LongParameterList") // the page's state, passed down rather than read again
private fun EpisodeOptions(
    vm: SeriesDetailsViewModel,
    title: String,
    d: SeriesDetails?,
    e: EpisodeInfo,
    saved: WatchProgressEntity?,
    screen: Screen.SeriesDetails
) {
    val nav = LocalNavigator.current
    val favourite by vm.favourite.collectAsState()
    val canDelete by produceState(false) { value = vm.canDelete() }
    val close = { vm.optionsFor = null }
    val set = { watched: Boolean ->
        close()
        vm.setWatched(e, watched)
    }
    val resumable = saved?.takeUnless { it.finished || it.positionMs <= 0 }
    val play = { fromStart: Boolean ->
        close()
        vm.played()
        nav.push(Screen.VodPlayer(screen.playlistId, e.id, screen.seriesId, fromStart = fromStart))
    }
    // One partly watched can be counted as watched, or started again (its progress forgotten).
    val episode = listOfNotNull(
        ItemAction.Heading(appString(R.string.vod_this_episode)),
        e.plot?.let {
            ItemAction.Run(appString(R.string.player_description)) {
                close()
                vm.describing = e
            }
        },
        resumable?.let { ItemAction.Run(appString(R.string.vod_resume_from, formatDuration(it.positionMs))) { play(false) } },
        resumable?.let { ItemAction.Run(appString(R.string.common_from_start)) { play(true) } },
        if (saved?.finished != true) markWatched(false, set) else null,
        if (saved?.started == true) markWatched(true, set) else null,
        if (canDelete) deleteAction(episodeName(e)) { vm.delete(e) } else null,
    )
    val showActions = listOf(ItemAction.Heading(appString(R.string.vod_the_show))) +
        titleActions(
            screen.playlistId,
            CAT_SERIES,
            screen.seriesId,
            d?.info,
            favourite,
            showCast = {
                close()
                vm.castOpen = true
            },
            setFavourite = { vm.toggleFavourite() }
        ) + listOfNotNull(vm.autoDelete?.action())
    ItemActionsPanel("$title · ${episodeName(e)}", episode + showActions, close)
}

@Composable
private fun EpisodeRow(
    e: EpisodeInfo,
    progress: WatchProgressEntity?,
    modifier: Modifier,
    onLongClick: (() -> Unit)?,
    onClick: () -> Unit
) {
    TvCard(onClick = onClick, onLongClick = onLongClick, color = Palette.background, modifier = modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
            horizontalArrangement = Arrangement.spacedBy(18.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "${e.number}",
                style = MaterialTheme.typography.headlineSmall,
                color = Palette.textDim,
                textAlign = TextAlign.End,
                modifier = Modifier.width(40.dp)
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(episodeName(e), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (progress?.finished == true) WatchedPill()
                }
                Muted(episodeLine(e, progress))
                if (progress != null && !progress.finished && progress.started && progress.durationMs > 0) {
                    ProgressLine(progress.fraction, Modifier.widthIn(max = 420.dp).fillMaxWidth())
                }
                if (LocalCardFocused.current) BadgeRow(e.badges)
            }
        }
    }
}

/** "54 min · 21 min left". */
@Composable
private fun episodeLine(e: EpisodeInfo, progress: WatchProgressEntity?): String = listOfNotNull(
    e.durationSec?.takeIf { it > 0 }?.let(::formatRuntime),
    progress?.takeIf { !it.finished && it.durationMs > 0 && it.started }?.let {
        stringResource(R.string.common_time_left, formatDuration(it.remainingMs))
    }
).joinToString(" · ")

@Composable
private fun WatchedPill() {
    val accent = LocalAccent.current
    Row(
        Modifier.clip(
            RoundedCornerShape(50)
        ).background(accent.color.copy(alpha = 0.16f)).padding(start = 6.dp, end = 10.dp, top = 2.dp, bottom = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Rounded.Check, null, tint = accent.color, modifier = Modifier.size(14.dp))
        Text(stringResource(R.string.vod_watched), style = MaterialTheme.typography.labelMedium, color = accent.color)
    }
}
