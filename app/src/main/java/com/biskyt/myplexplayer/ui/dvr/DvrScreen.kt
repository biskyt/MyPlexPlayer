package com.biskyt.myplexplayer.ui.dvr

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.biskyt.myplexplayer.AppContainer
import com.biskyt.myplexplayer.R
import com.biskyt.myplexplayer.appString
import com.biskyt.myplexplayer.core.plex.PlexRecordingRule
import com.biskyt.myplexplayer.core.plex.PlexScheduledRecording
import com.biskyt.myplexplayer.data.ChannelFilter
import com.biskyt.myplexplayer.data.Notice
import com.biskyt.myplexplayer.data.RecordingMark
import com.biskyt.myplexplayer.data.RecordingState
import com.biskyt.myplexplayer.data.Recordings
import com.biskyt.myplexplayer.data.db.ChannelEntity
import com.biskyt.myplexplayer.data.plexChannelId
import com.biskyt.myplexplayer.ui.LocalNavigator
import com.biskyt.myplexplayer.ui.Navigator
import com.biskyt.myplexplayer.ui.Screen
import com.biskyt.myplexplayer.ui.appViewModel
import com.biskyt.myplexplayer.ui.common.ItemAction
import com.biskyt.myplexplayer.ui.common.ItemActionsPanel
import com.biskyt.myplexplayer.ui.common.Muted
import com.biskyt.myplexplayer.ui.common.RecordingDot
import com.biskyt.myplexplayer.ui.common.SectionTitle
import com.biskyt.myplexplayer.ui.common.Tab
import com.biskyt.myplexplayer.ui.common.TabScaffold
import com.biskyt.myplexplayer.ui.common.TvCard
import com.biskyt.myplexplayer.ui.common.formatDayTime
import com.biskyt.myplexplayer.ui.common.formatTime
import com.biskyt.myplexplayer.ui.common.rememberFirstFocus
import com.biskyt.myplexplayer.ui.theme.Palette
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** A series recording ([rule]): its next airing to record, and how many are to come. */
data class SeriesRow(val rule: PlexRecordingRule, val next: PlexScheduledRecording?, val toCome: Int)

/**
 * What the DVR tab lists, from a server's [RecordingState]: airings recording [now], those [recent]ly recorded (newest
 * first, only those still in a library) and [comingUp] (soonest first), the [series] recordings, airings that weren't
 * recorded ([problems]), and the [others]: rules for one programme with no airing of it in the guide now (Plex's
 * "other subscriptions").
 */
class DvrLists(state: RecordingState) {
    private val byStart = state.scheduled.sortedBy { it.airing?.beginsAt ?: Long.MAX_VALUE }
    val now = byStart.filter { it.isRecording }
    val comingUp = byStart.filter { it.isScheduled }
    val problems = byStart.filter { it.needsAttention }
    val recent = state.scheduled.filter { it.recordedRatingKey != null }.sortedByDescending { it.airing?.beginsAt ?: 0 }.take(RECENT_MAX)
    private val byRule = comingUp.groupBy { it.mediaSubscriptionID }
    val series = state.rules.filter { it.isSeries }.map { rule ->
        val toCome = byRule[rule.key].orEmpty()
        SeriesRow(rule, toCome.firstOrNull(), toCome.size)
    }.sortedWith(compareBy({ it.next?.airing?.beginsAt ?: Long.MAX_VALUE }, { it.rule.subjectTitle }))

    /** The rules of the airings listed above (one done or cancelled isn't): a rule with none is listed in [others]. */
    private val listed = (now + comingUp + problems).mapNotNullTo(HashSet()) { it.mediaSubscriptionID }
    val others = state.rules.filter { !it.isSeries && it.key !in listed }.sortedBy { it.subjectTitle }
    val isEmpty get() = now.isEmpty() &&
        recent.isEmpty() &&
        comingUp.isEmpty() &&
        problems.isEmpty() &&
        series.isEmpty() &&
        others.isEmpty()

    private companion object {
        const val RECENT_MAX = 20
    }
}

/** A rule's row key. */
private fun ruleKey(rule: PlexRecordingRule) = "r" + rule.key

/** The DVR tab's data: the active source's recordings, read from the server on arrival, and their channels. */
class DvrViewModel(private val c: AppContainer) : ViewModel() {
    val recordings: Recordings = c.recordings
    val playlistId: StateFlow<Long> = c.settings.activePlaylistId.stateIn(viewModelScope, SharingStarted.Eagerly, 0L)

    @OptIn(ExperimentalCoroutinesApi::class)
    val state: StateFlow<RecordingState> = playlistId.flatMapLatest { recordings.observe(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_MS), RecordingState.NONE)

    /** The channels the airings are on, by their key (`providerEpgId`): a channel's id comes from its key. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val channels: StateFlow<Map<String, ChannelEntity>> =
        combine(playlistId, state) { pid, s -> pid to s.scheduled.mapNotNull { it.airing?.channelIdentifier }.toSet() }
            .distinctUntilChanged()
            .mapLatest { (pid, keys) ->
                val byId = keys.associateBy(::plexChannelId)
                c.channels.getAll(pid, byId.keys.toList()).associateBy { byId.getValue(it.streamId) }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_MS), emptyMap())

    /**
     * Opens what [airing] recorded, once its library item is read: with [page] its film's or series' page, else the
     * player. Only if the user is still where they were ([nav]'s screen) by then.
     */
    fun openRecorded(airing: PlexScheduledRecording, page: Boolean, nav: Navigator) {
        val pid = playlistId.value
        val from = nav.current
        viewModelScope.launch {
            val item = recordings.recordedItem(pid, airing) ?: return@launch
            val id = item.ratingKey.toLongOrNull() ?: return@launch
            val series = item.grandparentRatingKey?.toLongOrNull()?.takeIf { item.type == "episode" }
            // Recorded since the last sync: its film or series isn't here yet, and its screens need it.
            if (!synced(pid, id, series)) {
                // One sync for every press while it runs.
                (vodSync?.takeIf { it.isActive } ?: c.sync.refreshVod(pid).also { vodSync = it }).join()
                if (!synced(pid, id, series)) return@launch c.notices.post(Notice(appString(R.string.dvr_play_failed), null))
            }
            // A series' kept page can be from before this episode was recorded.
            series?.let { c.vod.episodesAdded(pid, it) }
            val screen = when {
                !page -> Screen.VodPlayer(pid, id, series)
                series != null -> Screen.SeriesDetails(pid, series, item.parentIndex, item.index)
                else -> Screen.MovieDetails(pid, id)
            }
            if (nav.current === from) nav.push(screen)
        }
    }

    /** The sync [openRecorded] started, while it runs. */
    private var vodSync: Job? = null

    /** Whether film [id], or (an episode's) [series], is in the app's own copy of the library. */
    private suspend fun synced(pid: Long, id: Long, series: Long?) =
        if (series != null) c.vod.series(pid, series) != null else c.vod.movie(pid, id) != null

    private companion object {
        const val STOP_MS = 5_000L
    }
}

/** The row whose options panel is open: an airing, a recorded one, or a rule (a series, or one of the others). */
private sealed interface Picked {
    data class Airing(val airing: PlexScheduledRecording) : Picked
    data class Recorded(val airing: PlexScheduledRecording) : Picked
    data class Rule(val rule: PlexRecordingRule) : Picked
}

/**
 * The DVR tab (MyPlexPlayer's own, last in the menu): what's recording now, what was recorded lately, what's coming
 * up, the series recordings, what wasn't recorded and the other recordings. OK (or holding it) on a row opens its
 * options: watch the channel (the player offers to watch a recording from its start), cancel, and for a series, which
 * episodes it records and stopping it. OK on a recorded one plays it; holding OK opens its options.
 */
@Composable
fun DvrScreen() {
    val vm = appViewModel { DvrViewModel(it) }
    val nav = LocalNavigator.current
    val pid by vm.playlistId.collectAsState()
    val state by vm.state.collectAsState()
    val channels by vm.channels.collectAsState()
    val lists = remember(state) { DvrLists(state) }
    var picked by remember { mutableStateOf<Picked?>(null) }
    // The focus starts on the first row; with none, on the tab.
    val firstRow = (lists.now + lists.recent + lists.comingUp).firstOrNull()?.let(::rowKey)
        ?: lists.series.firstOrNull()?.let { ruleKey(it.rule) }
        ?: lists.problems.firstOrNull()?.let(::rowKey)
        ?: lists.others.firstOrNull()?.let { ruleKey(it) }
    val first = rememberFirstFocus(firstRow != null)
    val tabFocus = remember { FocusRequester() }
    if (firstRow == null) LaunchedEffect(Unit) { runCatching { tabFocus.requestFocus() } }
    fun focus(key: String) = if (key == firstRow) first else Modifier
    TabScaffold(Tab.DVR, tabFocus = tabFocus) {
        LazyColumn(
            contentPadding = PaddingValues(start = 56.dp, end = 56.dp, bottom = 40.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            if (lists.isEmpty) item { Muted(stringResource(R.string.dvr_nothing), maxLines = 3) }
            airings(R.string.dvr_now_title, lists.now, state, channels, ::focus) { picked = Picked.Airing(it) }
            recorded(lists.recent, channels, ::focus, { picked = Picked.Recorded(it) }) { vm.openRecorded(it, page = false, nav) }
            airings(R.string.dvr_coming_up, lists.comingUp, state, channels, ::focus) { picked = Picked.Airing(it) }
            if (lists.series.isNotEmpty()) {
                item { SectionTitle(stringResource(R.string.dvr_series_title), Modifier.padding(top = SECTION_GAP)) }
                items(lists.series, key = { ruleKey(it.rule) }) { row ->
                    SeriesCard(row, channels, focus(ruleKey(row.rule))) { picked = Picked.Rule(row.rule) }
                }
            }
            airings(R.string.dvr_problems, lists.problems, state, channels, ::focus) { picked = Picked.Airing(it) }
            if (lists.others.isNotEmpty()) {
                item { SectionTitle(stringResource(R.string.dvr_others_title), Modifier.padding(top = SECTION_GAP)) }
                items(lists.others, key = { ruleKey(it) }) { rule ->
                    OtherCard(rule, focus(ruleKey(rule))) { picked = Picked.Rule(rule) }
                }
            }
        }
    }
    picked?.let { DvrOptions(it, pid, state, channels, vm) { picked = null } }
}

/** An airing's row key: its own key, or (an airing Plex gave none) the object itself. */
private fun rowKey(a: PlexScheduledRecording) = "a" + a.key.ifBlank { System.identityHashCode(a).toString() }

@Suppress("LongParameterList") // a composable list section: detekt exempts only @Composable functions
private fun LazyListScope.airings(
    @StringRes title: Int,
    airings: List<PlexScheduledRecording>,
    state: RecordingState,
    channels: Map<String, ChannelEntity>,
    focus: (String) -> Modifier,
    open: (PlexScheduledRecording) -> Unit
) {
    if (airings.isEmpty()) return
    item { SectionTitle(stringResource(title), Modifier.padding(top = SECTION_GAP)) }
    items(airings, key = ::rowKey) { a -> AiringCard(a, state, channels, focus(rowKey(a))) { open(a) } }
}

/** The recently recorded section: OK plays one ([play]), holding OK opens its options ([options]). */
private fun LazyListScope.recorded(
    airings: List<PlexScheduledRecording>,
    channels: Map<String, ChannelEntity>,
    focus: (String) -> Modifier,
    options: (PlexScheduledRecording) -> Unit,
    play: (PlexScheduledRecording) -> Unit
) {
    if (airings.isEmpty()) return
    item { SectionTitle(stringResource(R.string.dvr_recent_title), Modifier.padding(top = SECTION_GAP)) }
    items(airings, key = ::rowKey) { a ->
        Row2(null, a.title, listOfNotNull(episodeLine(a), whenAndWhere(a, channels)), focus(rowKey(a)), onClick = {
            play(a)
        }, onLongClick = { options(a) })
    }
}

/** An episode's code and title ("S1 E2 · Low Tide"), or null for a film. */
@Composable
private fun episodeLine(a: PlexScheduledRecording): String? {
    val e = a.programme?.takeIf { it.grandparentTitle != null }
    val code = e?.parentIndex?.let { s -> e.index?.let { stringResource(R.string.home_episode_code, s, it) } }
    return listOfNotNull(code, e?.title?.ifEmpty { null }).joinToString(" · ").ifEmpty { null }
}

@Composable
private fun AiringCard(
    a: PlexScheduledRecording,
    state: RecordingState,
    channels: Map<String, ChannelEntity>,
    modifier: Modifier,
    onClick: () -> Unit
) {
    val episode = episodeLine(a)
    // Not recorded: no mark (it isn't set to record any more), and what Plex says instead.
    val problem = !a.isScheduled && !a.isRecording
    val status = if (problem) stringResource(R.string.dvr_status, a.status) else null
    Row2(if (problem) null else state.mark(a), a.title, listOfNotNull(episode, whenAndWhere(a, channels), status), modifier, onClick)
}

@Composable
private fun SeriesCard(row: SeriesRow, channels: Map<String, ChannelEntity>, modifier: Modifier, onClick: () -> Unit) {
    val next = row.next?.let { stringResource(R.string.dvr_series_next, whenAndWhere(it, channels)) }
        ?: stringResource(R.string.dvr_series_none)
    val count = if (row.toCome > 0) pluralStringResource(R.plurals.dvr_to_come, row.toCome, row.toCome) else null
    val episodes = stringResource(R.string.dvr_series_episodes, stringResource(episodesLabel(row.rule)))
    Row2(RecordingMark.SERIES, row.rule.subjectTitle, listOfNotNull(next, count, episodes), modifier, onClick)
}

/** Which episodes series [rule] records. */
@StringRes
private fun episodesLabel(rule: PlexRecordingRule) = if (rule.isNewOnly) R.string.dvr_new_only else R.string.dvr_new_and_repeats

/** A rule for one programme with no airing of it in the guide now: its episode's title, if it's one, and why. */
@Composable
private fun OtherCard(rule: PlexRecordingRule, modifier: Modifier, onClick: () -> Unit) {
    val episode = rule.subject?.takeIf { it.grandparentTitle != null }?.title?.ifEmpty { null }
    Row2(RecordingMark.EPISODE, rule.subjectTitle, listOfNotNull(episode, stringResource(R.string.dvr_other_waiting)), modifier, onClick)
}

/** A row: its [mark] (if any), [title] and [lines] under it. Holding OK does as OK, unless it has [onLongClick]. */
@Composable
private fun Row2(
    mark: RecordingMark?,
    title: String,
    lines: List<String>,
    modifier: Modifier,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null
) {
    TvCard(onClick = onClick, onLongClick = onLongClick ?: onClick, modifier = modifier.fillMaxWidth(), color = Palette.surfaceLow) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(DOT_SPACE)) { mark?.let { RecordingDot(it) } }
            Column {
                Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1)
                lines.forEach { Muted(it) }
            }
        }
    }
}

/** "Today at 21:00 – 22:00 · One". */
private fun whenAndWhere(a: PlexScheduledRecording, channels: Map<String, ChannelEntity>): String = listOfNotNull(
    a.airing?.beginsAt?.let { begins ->
        formatDayTime(begins * MS) + (a.airing?.endsAt?.let { " – " + formatTime(it * MS) } ?: "")
    },
    a.airing?.channelIdentifier?.let(channels::get)?.name
).joinToString(" · ")

/**
 * A row's options: watch its channel, cancel it, and for a series, its episodes setting and stopping it. A recorded
 * one: play it, or open its film's or series' page.
 */
@Composable
private fun DvrOptions(
    picked: Picked,
    pid: Long,
    state: RecordingState,
    channels: Map<String, ChannelEntity>,
    vm: DvrViewModel,
    close: () -> Unit
) {
    val recordings = vm.recordings
    val stopLabel = stringResource(R.string.dvr_stop_series)
    fun stop(rule: PlexRecordingRule, label: String = stopLabel) = ItemAction.Run(label) {
        close()
        recordings.remove(pid, rule)
    }
    val (title, actions) = when (picked) {
        is Picked.Airing -> {
            val a = picked.airing
            val rule = state.ruleOf(a)
            val channel = a.airing?.channelIdentifier?.let(channels::get)
            val watch = stringResource(R.string.common_watch)
            a.title to listOfNotNull(
                channel?.let { ItemAction.Open(watch, Screen.Player(pid, it.streamId, ChannelFilter.All)) },
                ItemAction.Run(stringResource(R.string.dvr_cancel)) {
                    close()
                    recordings.cancel(pid, a, rule)
                },
                rule?.takeIf { it.isSeries }?.let(::stop)
            )
        }
        is Picked.Recorded -> picked.airing.title to recordedActions(picked.airing, vm, close)
        is Picked.Rule -> {
            // As the server has it now (the panel follows each change).
            val rule = state.rules.firstOrNull { it.key == picked.rule.key } ?: picked.rule
            rule.subjectTitle to if (rule.isSeries) {
                listOf(
                    ItemAction.Cycle(stringResource(R.string.dvr_episodes), stringResource(episodesLabel(rule))) {
                        recordings.setNewOnly(pid, rule, !rule.isNewOnly)
                    },
                    stop(rule)
                )
            } else {
                listOf(stop(rule, stringResource(R.string.dvr_cancel)))
            }
        }
    }
    ItemActionsPanel(title, actions, close)
}

/** A recorded airing's options: play it, or open its film's or series' page (once its library item is read). */
@Composable
private fun recordedActions(a: PlexScheduledRecording, vm: DvrViewModel, close: () -> Unit): List<ItemAction> {
    val nav = LocalNavigator.current
    fun open(page: Boolean) {
        close()
        vm.openRecorded(a, page, nav)
    }
    return listOf(
        ItemAction.Run(stringResource(R.string.common_play)) { open(page = false) },
        ItemAction.Run(stringResource(R.string.vod_more_info)) { open(page = true) }
    )
}

private val SECTION_GAP = 18.dp
private val DOT_SPACE = 28.dp
private const val MS = 1000L
