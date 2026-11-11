package com.biskyt.myplexplayer.ui.vod

import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import com.biskyt.myplexplayer.AppContainer
import com.biskyt.myplexplayer.R
import com.biskyt.myplexplayer.appString
import com.biskyt.myplexplayer.core.vod.CastMember
import com.biskyt.myplexplayer.core.vod.EpisodeInfo
import com.biskyt.myplexplayer.core.vod.SeriesDetails
import com.biskyt.myplexplayer.data.VodRepository
import com.biskyt.myplexplayer.data.db.CAT_SERIES
import com.biskyt.myplexplayer.data.db.SeriesEntity
import com.biskyt.myplexplayer.data.db.WatchProgressEntity
import com.biskyt.myplexplayer.ui.LocalNavigator
import com.biskyt.myplexplayer.ui.Screen
import com.biskyt.myplexplayer.ui.appViewModel
import com.biskyt.myplexplayer.ui.common.Backdrop
import com.biskyt.myplexplayer.ui.common.BoardActionsPanel
import com.biskyt.myplexplayer.ui.common.CastPanel
import com.biskyt.myplexplayer.ui.common.ErrorRetry
import com.biskyt.myplexplayer.ui.common.FocusStroke
import com.biskyt.myplexplayer.ui.common.ItemAction
import com.biskyt.myplexplayer.ui.common.LocalTopBarInset
import com.biskyt.myplexplayer.ui.common.Muted
import com.biskyt.myplexplayer.ui.common.PillButton
import com.biskyt.myplexplayer.ui.common.Poster
import com.biskyt.myplexplayer.ui.common.ProgressLine
import com.biskyt.myplexplayer.ui.common.Tab
import com.biskyt.myplexplayer.ui.common.Thumbnail
import com.biskyt.myplexplayer.ui.common.TvCard
import com.biskyt.myplexplayer.ui.common.formatDate
import com.biskyt.myplexplayer.ui.common.formatDuration
import com.biskyt.myplexplayer.ui.common.markWatched
import com.biskyt.myplexplayer.ui.common.onOptionsKey
import com.biskyt.myplexplayer.ui.common.onOptionsKeyAfter
import com.biskyt.myplexplayer.ui.common.rememberFirstFocus
import com.biskyt.myplexplayer.ui.common.restoreFocusIf
import com.biskyt.myplexplayer.ui.common.shortEpisodeLabel
import com.biskyt.myplexplayer.ui.common.timeLeft
import com.biskyt.myplexplayer.ui.player.DescribedEpisode
import com.biskyt.myplexplayer.ui.player.EpisodeDescriptionPanel
import com.biskyt.myplexplayer.ui.player.episodeDescriptionAction
import com.biskyt.myplexplayer.ui.player.episodeName
import com.biskyt.myplexplayer.ui.theme.BOLD
import com.biskyt.myplexplayer.ui.theme.BoardStyle
import com.biskyt.myplexplayer.ui.theme.EXTRA_BOLD
import com.biskyt.myplexplayer.ui.theme.LocalAccent
import com.biskyt.myplexplayer.ui.theme.MEDIUM
import com.biskyt.myplexplayer.ui.theme.Palette
import com.biskyt.myplexplayer.ui.theme.bd
import com.biskyt.myplexplayer.ui.theme.boardLineBox
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

    /** The series was deleted here (or with its last episode): the page closes. */
    var gone by mutableStateOf(false)
        private set

    /** The season asked for or picked (until then, the one with the episode to carry on with). */
    var season by mutableStateOf(season)

    /** What is open over the page: an episode's options (its More… button, holding OK, or Menu), or a panel from them. */
    var open by mutableStateOf<SeriesPanel?>(null)

    /** Who has the remote in Cast and crew: back from their page, the panel opens on them again. */
    var castFocus = 0

    /** Cast and crew, on its first person. */
    fun showCast() {
        castFocus = 0
        open = SeriesPanel.Cast
    }

    /** An episode to give the remote to as the page opens (one asked for from the TV guide), until it has. */
    var focusEpisodePending = true

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
        try {
            details = c.vod.seriesDetails(c.source(playlistId), seriesId)
        } catch (e: java.io.IOException) {
            error = e.message ?: appString(R.string.vod_episodes_failed)
        }
    }

    /** Marks [e] watched or unwatched on the server, and its row follows (out of reach, nothing changes). */
    fun setWatched(e: EpisodeInfo, watched: Boolean) {
        // The list stays on this season (it otherwise follows the episode to carry on with, which this may move on).
        if (season == null) season = e.season
        viewModelScope.launch { c.vod.setWatched(playlistId, e.id, seriesId, watched) }
    }

    /** Whether the remote is in the episode list: changes made there (marking one watched) leave it there. */
    var listFocused by mutableStateOf(false)

    /** The episode played from the list: back from the player, it has the remote again. */
    var playedFromList: Long? = null

    fun toggleFavourite() = viewModelScope.launch { c.vod.setFavourite(playlistId, CAT_SERIES, seriesId, !favourite.value) }

    /** Whether the server lets episodes be deleted (asked once, then known for the source). */
    suspend fun canDelete() = c.vod.canDelete(playlistId)

    /** The series' "Delete watched episodes", read when an episode's options first open. */
    var autoDelete: AutoDeleteChoice? = null
        private set

    fun openOptions(e: EpisodeInfo) {
        open = SeriesPanel.Options(e)
        if (autoDelete == null) autoDelete = AutoDeleteChoice(viewModelScope, c.vod, playlistId, seriesId)
    }

    /**
     * Deletes [e] on the server; the list follows (refused or out of reach, a notice says so). The series' last episode
     * takes the series with it, and the page closes.
     */
    fun delete(e: EpisodeInfo) {
        if (season == null) season = e.season
        viewModelScope.launch {
            when (c.vod.deleteEpisode(playlistId, seriesId, e.id)) {
                VodRepository.EpisodeDeleted.EPISODE -> load()
                VodRepository.EpisodeDeleted.SERIES -> gone = true
                VodRepository.EpisodeDeleted.NOTHING -> Unit
            }
        }
    }

    /** The player for the show's trailer [id]. */
    fun trailer(id: Long) = Screen.VodPlayer(playlistId, seriesId, seriesId, trailer = id)

    /** Deletes the series on the server, every episode with it; the page closes (refused or out of reach, a notice says so). */
    fun deleteSeries() = viewModelScope.launch { if (c.vod.deleteSeries(playlistId, seriesId)) gone = true }
}

/** The panels over the series page. */
sealed interface SeriesPanel {
    /** An episode's options: this episode's, then the show's. */
    data class Options(val e: EpisodeInfo) : SeriesPanel
    data object Cast : SeriesPanel
    data object About : SeriesPanel
    data class Describe(val e: EpisodeInfo) : SeriesPanel
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
    val nav = LocalNavigator.current
    if (vm.gone) LaunchedEffect(Unit) { nav.pop() }
    val show = vm.series ?: return
    val d = vm.details
    val progress by vm.progress.collectAsState()
    val play = { e: EpisodeInfo, fromStart: Boolean ->
        nav.push(Screen.VodPlayer(screen.playlistId, e.id, screen.seriesId, fromStart = fromStart))
    }
    val next = remember(d, progress) { d?.let { continueEpisode(it.episodes, progress) } }
    // Opened for one episode (from the TV guide): it has the remote rather than Continue.
    val asked = remember(d) { d?.episodes?.firstOrNull { it.season == screen.season && it.number == screen.episode } }
    // The season shown: the one picked; else the one to carry on with (a season the server doesn't have, as the guide
    // may number them differently, shows the usual one).
    val season = remember(d, vm.season, asked, next) {
        d?.let {
            vm.season?.takeIf { s -> it.episodes.any { e -> e.season == s } } ?: (asked ?: next)?.season
                ?: it.seasons.firstOrNull()?.number
        }
    }

    // Right from the left column goes into the episode list (at its first unplayed episode), not up to the top bar
    // when the list is too short to be beside the buttons. Only while the list is shown.
    val episodes = remember { FocusRequester() }
    val toEpisodes = if (vm.error == null && d?.episodes?.isNotEmpty() == true) Modifier.focusProperties { right = episodes } else Modifier
    // The remote's Menu button opens the options of the episode it is on (its row's own handler), or else, from
    // anywhere else on the page, of the one asked for or to carry on with.
    Box(Modifier.fillMaxSize().onOptionsKeyAfter { (asked ?: next)?.let(vm::openOptions) }) {
        Backdrop(d?.info?.backdropUrl)
        Row(
            Modifier.padding(start = 48.bd, end = 40.bd, top = LocalTopBarInset.current, bottom = 16.bd),
            horizontalArrangement = Arrangement.spacedBy(30.bd)
        ) {
            SeriesColumn(show, d, season, moreModifier = toEpisodes) {
                if (next != null) {
                    val focus = if (asked == null || !vm.focusEpisodePending) playFocus(vm, next.id) else Modifier
                    ContinueButton(next, progress, { play(it, false) }, { vm.openOptions(next) }, focus.then(toEpisodes))
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.bd)) {
                when {
                    vm.error != null -> ErrorRetry(vm.error.orEmpty()) { vm.load() }
                    d == null -> Muted(stringResource(R.string.vod_loading_episodes))
                    d.episodes.isEmpty() -> NoEpisodes(vm)
                    else -> Episodes(vm, d, season, progress, asked, episodes) { play(it, false) }
                }
            }
        }
    }
    SeriesPanels(vm, show.title, progress, play) { i, person ->
        vm.castFocus = i
        val acting = i < d?.info?.cast.orEmpty().size
        personScreen(screen.playlistId, person, show.title, Tab.SERIES, acting)?.let(nav::push)
    }
}

/** What is open over the series page ([SeriesDetailsViewModel.open]): its own composable, so the page does not recompose with it. */
@Composable
private fun SeriesPanels(
    vm: SeriesDetailsViewModel,
    title: String,
    progress: Map<Long, WatchProgressEntity>,
    play: (EpisodeInfo, Boolean) -> Unit,
    openPerson: (Int, CastMember) -> Unit
) {
    val d = vm.details
    val close = { vm.open = null }
    when (val p = vm.open) {
        null -> Unit
        is SeriesPanel.Options -> EpisodeOptions(vm, title, d, p.e, progress[p.e.id], play)
        SeriesPanel.Cast -> {
            val crew = remember(d) { castAndCrew(d?.info) }
            CastPanel(title, crew, stringResource(R.string.vod_cast_crew), vm.castFocus, { openPerson(it, crew[it]) }, close)
        }
        SeriesPanel.About -> AboutPanel(film = false, d?.info, close)
        is SeriesPanel.Describe -> EpisodeDescriptionPanel(DescribedEpisode(title, p.e), close)
    }
}

/** A series with no episodes: says so, and offers to delete it where the server allows it. */
@Composable
private fun NoEpisodes(vm: SeriesDetailsViewModel) {
    val canDelete by produceState(false) { value = vm.canDelete() }
    if (!canDelete) {
        Muted(stringResource(R.string.vod_no_episodes))
        return
    }
    Muted(stringResource(R.string.vod_no_episodes_delete), maxLines = 3)
    PillButton(stringResource(R.string.vod_delete_series_button), { vm.deleteSeries() }, rememberFirstFocus())
}

/**
 * The series page's left column (board "PlexSeries"), 300 wide: the season's poster (else the show's), the title, the
 * facts ([season]'s name, its year, the number of seasons, genres, then the age rating and the scores), the plot (4
 * lines at most), and [buttons] (Continue).
 */
@Composable
private fun SeriesColumn(
    show: SeriesEntity,
    d: SeriesDetails?,
    season: Int?,
    /** For Read more under the plot: where Right goes from it. */
    moreModifier: Modifier = Modifier,
    buttons: @Composable () -> Unit
) {
    val info = d?.info
    val current = d?.seasons?.firstOrNull { it.number == season }
    // It scrolls when it is taller than the screen (large text): the button with focus is then brought into view.
    Column(Modifier.width(300.bd).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(9.bd)) {
        Poster(current?.posterUrl ?: show.coverUrl ?: info?.posterUrl, show.title, 120.bd, radius = 10.bd)
        Text(
            show.title,
            style = SeriesTitle.current,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.boardLineBox(26, 1.15f)
        )
        // One season: its name says it, so not "1 series" too.
        val count = d?.seasons?.size?.takeIf { it > 1 }?.let { pluralStringResource(R.plurals.vod_season_count, it, it) }
        val facts = listOfNotNull(
            current?.let { seasonName(it.number, it.name) },
            (current?.year ?: show.year ?: info?.year)?.toString(),
            count,
            genresOf(info?.genres.orEmpty()),
            starRating(info?.rating)
        ).joinToString(" · ")
        val rating = info?.contentRating
        val scores = info?.scores.orEmpty()
        if (facts.isNotEmpty() || rating != null || scores.isNotEmpty()) {
            WrapRow(8.bd) {
                if (facts.isNotEmpty()) Text(facts, style = SeriesFacts.current, color = Palette.textMuted, maxLines = 2)
                rating?.let { BoardAgeBadge(it, small = true) }
                scores.forEach { BoardScore(it) }
            }
        }
        // 4 lines at most, so the buttons stay in reach; Read more shows the rest.
        info?.plot?.let { PlotWithMore(show.title, it, SeriesPlot.current, maxLines = 4, moreModifier) }
        Box(Modifier.padding(top = 4.bd, bottom = FocusStroke.width)) { buttons() }
    }
}

/** A season's name: the server's, else the app's ("Series 2"). */
@Composable
private fun seasonName(number: Int, name: String?) = name ?: stringResource(R.string.vod_season, number)

/** "Continue S2 E4 · 21 min left", "Continue S2 E5" (the next one), or "Play S1 E1" when nothing is watched yet. */
@Composable
@Suppress("LongParameterList") // the page's state, passed down rather than read again
private fun ContinueButton(
    e: EpisodeInfo,
    progress: Map<Long, WatchProgressEntity>,
    play: (EpisodeInfo) -> Unit,
    options: () -> Unit,
    modifier: Modifier
) {
    val label = shortEpisodeLabel(e.season, e.number)
    val started = progress[e.id].partWatched()
    val any = remember(progress) { progress.values.any { it.started || it.finished } }
    val text = when {
        started != null -> stringResource(R.string.vod_continue_left, label, timeLeft(started))
        any -> stringResource(R.string.vod_continue_episode, label)
        else -> stringResource(R.string.vod_play_episode, label)
    }
    // Holding OK opens the options of the episode it plays, as on the film page's buttons.
    DetailButton(text, { play(e) }, modifier, strong = true, horizontal = 18.bd, onLongClick = options)
}

/** Focus for Continue as the page opens and whenever where to carry on changes, unless the remote is in the episode list. */
@Composable
private fun playFocus(vm: SeriesDetailsViewModel, key: Any): Modifier {
    val r = remember { FocusRequester() }
    LaunchedEffect(key) { if (!vm.listFocused && vm.playedFromList == null) runCatching { r.requestFocus() } }
    return Modifier.focusRequester(r)
}

@Composable
@Suppress("LongParameterList") // the page's state, passed down rather than read again
private fun Episodes(
    vm: SeriesDetailsViewModel,
    d: SeriesDetails,
    season: Int?,
    progress: Map<Long, WatchProgressEntity>,
    target: EpisodeInfo?,
    /** Focus coming into the list goes to its first unplayed episode, or its first when all are watched. */
    entry: FocusRequester,
    play: (EpisodeInfo) -> Unit
) {
    if (d.seasons.size > 1) {
        // In by the ring's width, as the episode list is, so the numbers line up with the pills' text.
        LazyRow(Modifier.padding(start = RING), horizontalArrangement = Arrangement.spacedBy(8.bd)) {
            items(d.seasons, key = { it.number }) { s ->
                SeasonPill(seasonName(s.number, s.name), s.number == season) { vm.season = s.number }
            }
        }
    }
    val shown = remember(d, season) { d.episodes.filter { season == null || it.season == season } }
    val list = rememberLazyListState()
    // Scrolled into view first, so the row exists to take focus.
    LaunchedEffect(target, shown) {
        val i = shown.indexOf(target)
        if (vm.focusEpisodePending && i >= 0) list.scrollToItem(i)
    }
    // Gone (the player opened over the page), the list has no focus: back, the page decides it again.
    DisposableEffect(Unit) { onDispose { vm.listFocused = false } }
    val firstUnplayed = remember(shown, progress) { shown.firstOrNull { progress[it.id]?.finished != true } ?: shown.firstOrNull() }
    val firstUnplayedRow = remember { FocusRequester() }
    LazyColumn(
        state = list,
        modifier = Modifier.onFocusChanged { vm.listFocused = it.hasFocus }
            .focusRequester(entry)
            // Its row if it is laid out (a long list may have it out of view): otherwise the usual first row in view.
            // Only coming in with Right (from the buttons): a row asked for by name (from the TV guide) keeps focus.
            .focusProperties {
                onEnter = {
                    if (requestedFocusDirection ==
                        FocusDirection.Right
                    ) {
                        runCatching { firstUnplayedRow.requestFocus(requestedFocusDirection) }
                    }
                }
            }
            .focusGroup(),
        verticalArrangement = Arrangement.spacedBy(8.bd),
        // Room for the open row's ring, just outside it.
        contentPadding = PaddingValues(start = RING, end = RING, top = RING, bottom = 24.bd)
    ) {
        items(shown, key = { it.id }) { e ->
            val back = vm.playedFromList == e.id
            val focus = Modifier.restoreFocusIf(back || vm.focusEpisodePending && e == target) {
                vm.focusEpisodePending = false
                if (back) vm.playedFromList = null
            }.then(if (e == firstUnplayed) Modifier.focusRequester(firstUnplayedRow) else Modifier)
            EpisodeRow(e, progress[e.id], focus, options = { vm.openOptions(e) }) {
                vm.playedFromList = e.id
                // The list stays on this season when the one to carry on with moves to the next.
                if (vm.season == null) vm.season = e.season
                play(e)
            }
        }
    }
}

/** An episode's options (board "PlexSeriesMore"): this episode's, then the show's (shared with the film page's More…). */
@Composable
@Suppress("LongParameterList") // the page's state, passed down rather than read again
private fun EpisodeOptions(
    vm: SeriesDetailsViewModel,
    title: String,
    d: SeriesDetails?,
    e: EpisodeInfo,
    saved: WatchProgressEntity?,
    play: (EpisodeInfo, Boolean) -> Unit
) {
    val favourite by vm.favourite.collectAsState()
    val canDelete by produceState(false) { value = vm.canDelete() }
    val close = { vm.open = null }
    val set = { watched: Boolean ->
        close()
        vm.setWatched(e, watched)
    }
    val resumable = saved?.takeUnless { it.finished || it.positionMs <= 0 }
    val start = { fromStart: Boolean ->
        close()
        play(e, fromStart)
    }
    // One partly watched can be counted as watched, or started again (its progress forgotten).
    val episode = listOfNotNull(
        ItemAction.Heading(appString(R.string.vod_this_episode)),
        resumable?.let { ItemAction.Run(appString(R.string.vod_resume_from, formatDuration(it.positionMs))) { start(false) } },
        resumable?.let { ItemAction.Run(appString(R.string.common_from_start)) { start(true) } },
        episodeDescriptionAction(e) { vm.open = SeriesPanel.Describe(e) },
        if (saved?.finished != true) markWatched(false, set) else null,
        if (saved?.started == true) markWatched(true, set) else null,
        if (canDelete) deleteAction(episodeName(e)) { vm.delete(e) } else null,
    )
    val showActions = listOf(ItemAction.Heading(appString(R.string.vod_the_show))) +
        titleActions(d?.info, trailer = vm::trailer, showCast = vm::showCast, showAbout = { vm.open = SeriesPanel.About }) +
        listOfNotNull(
            favouriteAction(favourite) { vm.toggleFavourite() },
            vm.autoDelete?.action(),
            if (canDelete) deleteSeriesAction(title, d?.episodes?.size) { vm.deleteSeries() } else null
        )
    val name = "${shortEpisodeLabel(e.season, e.number)} ${episodeName(e)}"
    BoardActionsPanel("$title · $name", null, episode + showActions, close, smallTitle = true)
}

/**
 * An episode (board "PlexSeries"). Without focus: one line, its number, its name, a tick when watched, its length (and
 * what is left, with a short bar, when part-watched). With focus it opens out: its picture if it has one, two lines of
 * its name, its length, what is left and when it was first shown, two lines of its plot, its picture and sound labels,
 * a bar, and a More… button on the right (Right moves to it) that opens its options, as holding OK does.
 */
@Composable
private fun EpisodeRow(e: EpisodeInfo, progress: WatchProgressEntity?, modifier: Modifier, options: () -> Unit, onClick: () -> Unit) {
    // Worked out once, not at each focus change (again when the language changes: it may be the app's "Episode 2").
    val name = remember(e, LocalConfiguration.current) { episodeName(e) }
    var open by remember { mutableStateOf(false) }
    val bring = remember { BringIntoViewRequester() }
    // Open, it is taller than when the list scrolled to it: all of it is brought into view, once it has its size.
    LaunchedEffect(open) {
        if (open) {
            withFrameNanos { }
            bring.bringIntoView()
        }
    }
    val shape = if (open) OpenShape else ClosedShape
    Row(
        Modifier.fillMaxWidth().bringIntoViewRequester(bring).onFocusChanged { open = it.hasFocus }
            .then(if (open) Modifier.drawBehind { drawRing() } else Modifier)
            .clip(shape).background(if (open) Palette.surfaceHigh else Color.Transparent),
        verticalAlignment = Alignment.CenterVertically
    ) {
        TvCard(
            onClick = onClick,
            onLongClick = options,
            color = Color.Transparent,
            focusedColor = Color.Transparent,
            ring = false,
            shape = shape,
            modifier = modifier.weight(1f)
        ) {
            if (open) OpenEpisode(e, name, progress) else ClosedEpisode(e, name, progress)
        }
        if (open) {
            DetailButton(
                stringResource(R.string.vod_more),
                options,
                Modifier.padding(end = 14.bd).onOptionsKey(options),
                horizontal = 16.bd,
                vertical = 8.bd,
                style = MoreText.current,
                background = Palette.background
            )
        }
    }
}

/** The open row's ring: 3 wide, just outside it (the board's box-shadow 0 0 0 3px). */
private fun DrawScope.drawRing() {
    val w = RING.toPx()
    drawRoundRect(
        FocusStroke.brush,
        topLeft = Offset(-w / 2, -w / 2),
        size = Size(size.width + w, size.height + w),
        cornerRadius = CornerRadius(12.bd.toPx() + w / 2),
        style = Stroke(w)
    )
}

private val RING = FocusStroke.width
private val OpenShape = RoundedCornerShape(12.bd)
private val ClosedShape = RoundedCornerShape(10.bd)
private val PictureShape = RoundedCornerShape(6.bd)

/** What is left of a part-watched episode, or null. */
private fun WatchProgressEntity?.partWatched() = this?.takeIf { it.started && !it.finished && it.durationMs > 0 }

@Composable
private fun EpisodeNumber(e: EpisodeInfo, open: Boolean) =
    Text("${e.number}", style = NumberText.current, color = if (open) Palette.text else Palette.textDim, modifier = Modifier.width(24.bd))

@Composable
private fun ClosedEpisode(e: EpisodeInfo, name: String, progress: WatchProgressEntity?) {
    Row(
        Modifier.padding(horizontal = 14.bd, vertical = 7.bd),
        horizontalArrangement = Arrangement.spacedBy(14.bd),
        verticalAlignment = Alignment.CenterVertically
    ) {
        EpisodeNumber(e, open = false)
        Column(verticalArrangement = Arrangement.spacedBy(2.bd)) {
            EpisodeTitleRow(name, progress, open = false)
            val part = progress.partWatched()
            val length = listOfNotNull(runtimeOf(e.durationSec), part?.let { timeLeftText(it) }).joinToString(" · ")
            if (length.isNotEmpty()) Text(length, style = EpisodeSmall.current, color = Palette.textDim, maxLines = 1)
            if (part != null) ProgressLine(part.fraction, Modifier.padding(top = 3.bd).width(160.bd), height = 3.bd)
        }
    }
}

@Composable
private fun OpenEpisode(e: EpisodeInfo, name: String, progress: WatchProgressEntity?) {
    Row(Modifier.padding(horizontal = 14.bd, vertical = 10.bd), horizontalArrangement = Arrangement.spacedBy(14.bd)) {
        EpisodeNumber(e, open = true)
        e.imageUrl?.let { Thumbnail(it, name, 128.bd, 72.bd, PictureShape) }
        Column(verticalArrangement = Arrangement.spacedBy(3.bd)) {
            EpisodeTitleRow(name, progress, open = true)
            val part = progress.partWatched()
            val info = listOfNotNull(
                runtimeOf(e.durationSec),
                part?.let { timeLeftText(it) },
                e.airDate?.let { stringResource(R.string.vod_first_shown, formatDate(it)) }
            ).joinToString(" · ")
            if (info.isNotEmpty()) {
                Text(info, style = EpisodeSmall.current, color = Palette.textMuted, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            e.plot?.let { Text(it, style = OpenPlot.current, color = Palette.textDim, maxLines = 2, overflow = TextOverflow.Ellipsis) }
            if (e.badges.isNotEmpty()) LabelRow(e.badges, small = true)
            if (part != null) ProgressLine(part.fraction, Modifier.padding(top = 3.bd).width(260.bd), height = 4.bd)
        }
    }
}

/** An episode's name (1 line, or 2 when [open]: the long-title rule), then a tick when it is watched. */
@Composable
private fun EpisodeTitleRow(name: String, progress: WatchProgressEntity?, open: Boolean) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.bd), verticalAlignment = Alignment.CenterVertically) {
        Text(
            name,
            style = if (open) OpenTitle.current else EpisodeTitle.current,
            maxLines = if (open) 2 else 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false)
        )
        if (progress?.finished == true) WatchedTick()
    }
}

@Composable
private fun timeLeftText(p: WatchProgressEntity) = stringResource(R.string.common_time_left, timeLeft(p))

/** "✓ Watched", in the accent colour. */
@Composable
private fun WatchedTick() {
    val accent = LocalAccent.current.color
    Row(horizontalArrangement = Arrangement.spacedBy(3.bd), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Rounded.Check, contentDescription = null, tint = accent, modifier = Modifier.size(12.bd))
        Text(stringResource(R.string.vod_watched), style = WatchedText.current, color = accent, maxLines = 1)
    }
}

// The series page's text (the board's sizes; colours are given where they differ).
private val SeriesTitle = BoardStyle(26, EXTRA_BOLD, 1.15f)
private val SeriesFacts = BoardStyle(14, BOLD)
private val SeriesPlot = BoardStyle(14, MEDIUM, 1.4f)
private val NumberText = BoardStyle(20, EXTRA_BOLD)
private val EpisodeTitle = BoardStyle(15, EXTRA_BOLD)
private val OpenTitle = BoardStyle(15, EXTRA_BOLD, 1.25f)
private val EpisodeSmall = BoardStyle(13, MEDIUM)
private val OpenPlot = BoardStyle(13, MEDIUM, 1.35f)
private val WatchedText = BoardStyle(12, EXTRA_BOLD)
private val MoreText = BoardStyle(14, BOLD)
