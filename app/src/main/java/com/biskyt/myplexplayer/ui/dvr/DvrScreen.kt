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
import com.biskyt.myplexplayer.core.plex.PlexRecordingRule
import com.biskyt.myplexplayer.core.plex.PlexScheduledRecording
import com.biskyt.myplexplayer.data.ChannelFilter
import com.biskyt.myplexplayer.data.RecordingMark
import com.biskyt.myplexplayer.data.RecordingState
import com.biskyt.myplexplayer.data.Recordings
import com.biskyt.myplexplayer.data.db.ChannelEntity
import com.biskyt.myplexplayer.data.plexChannelId
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
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn

/** A series recording ([rule]): its next airing to record, and how many are to come. */
data class SeriesRow(val rule: PlexRecordingRule, val next: PlexScheduledRecording?, val toCome: Int)

/**
 * What the DVR tab lists, from a server's [RecordingState]: airings recording [now] and [comingUp] (soonest first),
 * the [series] recordings, and airings that weren't recorded ([problems]). Finished recordings are in the library.
 */
class DvrLists(state: RecordingState) {
    private val byStart = state.scheduled.sortedBy { it.airing?.beginsAt ?: Long.MAX_VALUE }
    val now = byStart.filter { it.isRecording }
    val comingUp = byStart.filter { it.isScheduled }
    val problems = byStart.filter { it.needsAttention }
    private val byRule = comingUp.groupBy { it.mediaSubscriptionID }
    val series = state.rules.filter { it.isSeries }.map { rule ->
        val toCome = byRule[rule.key].orEmpty()
        SeriesRow(rule, toCome.firstOrNull(), toCome.size)
    }.sortedWith(compareBy({ it.next?.airing?.beginsAt ?: Long.MAX_VALUE }, { it.rule.subject?.title.orEmpty() }))
    val isEmpty get() = now.isEmpty() && comingUp.isEmpty() && problems.isEmpty() && series.isEmpty()
}

/** The DVR tab's data: the active source's recordings, read from the server on arrival, and their channels. */
class DvrViewModel(c: AppContainer) : ViewModel() {
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

    private companion object {
        const val STOP_MS = 5_000L
    }
}

/** The row whose options panel is open. */
private sealed interface Picked {
    data class Airing(val airing: PlexScheduledRecording) : Picked
    data class Series(val rule: PlexRecordingRule) : Picked
}

/**
 * The DVR tab (MyPlexPlayer's own, last in the menu): what's recording now, what's coming up, the series recordings
 * and what wasn't recorded. OK (or holding it) on a row opens its options: watch the channel, cancel, and for a
 * series, which episodes it records and stopping it.
 */
@Composable
fun DvrScreen() {
    val vm = appViewModel { DvrViewModel(it) }
    val pid by vm.playlistId.collectAsState()
    val state by vm.state.collectAsState()
    val channels by vm.channels.collectAsState()
    val lists = remember(state) { DvrLists(state) }
    var picked by remember { mutableStateOf<Picked?>(null) }
    // The focus starts on the first row; with none, on the tab.
    val firstRow = (lists.now + lists.comingUp).firstOrNull()?.let(::rowKey)
        ?: lists.series.firstOrNull()?.let { "s" + it.rule.key }
        ?: lists.problems.firstOrNull()?.let(::rowKey)
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
            airings(R.string.dvr_coming_up, lists.comingUp, state, channels, ::focus) { picked = Picked.Airing(it) }
            if (lists.series.isNotEmpty()) {
                item { SectionTitle(stringResource(R.string.dvr_series_title), Modifier.padding(top = SECTION_GAP)) }
                items(lists.series, key = { "s" + it.rule.key }) { row ->
                    SeriesCard(row, channels, focus("s" + row.rule.key)) { picked = Picked.Series(row.rule) }
                }
            }
            airings(R.string.dvr_problems, lists.problems, state, channels, ::focus) { picked = Picked.Airing(it) }
        }
    }
    picked?.let { DvrOptions(it, pid, state, channels, vm.recordings) { picked = null } }
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

@Composable
private fun AiringCard(
    a: PlexScheduledRecording,
    state: RecordingState,
    channels: Map<String, ChannelEntity>,
    modifier: Modifier,
    onClick: () -> Unit
) {
    val e = a.programme?.takeIf { it.grandparentTitle != null }
    val code = e?.parentIndex?.let { s -> e.index?.let { stringResource(R.string.home_episode_code, s, it) } }
    val episode = listOfNotNull(code, e?.title?.ifEmpty { null }).joinToString(" · ").ifEmpty { null }
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
    Row2(RecordingMark.SERIES, row.rule.subject?.title.orEmpty(), listOfNotNull(next, count), modifier, onClick)
}

/** A row: its [mark] (if any), [title] and [lines] under it. */
@Composable
private fun Row2(mark: RecordingMark?, title: String, lines: List<String>, modifier: Modifier, onClick: () -> Unit) {
    TvCard(onClick = onClick, onLongClick = onClick, modifier = modifier.fillMaxWidth(), color = Palette.surfaceLow) {
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

/** A row's options: watch its channel, cancel it, and for a series, its episodes setting and stopping it. */
@Composable
private fun DvrOptions(
    picked: Picked,
    pid: Long,
    state: RecordingState,
    channels: Map<String, ChannelEntity>,
    recordings: Recordings,
    close: () -> Unit
) {
    val stopLabel = stringResource(R.string.dvr_stop_series)
    fun stop(rule: PlexRecordingRule) = ItemAction.Run(stopLabel) {
        close()
        recordings.stopSeries(pid, rule)
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
        is Picked.Series -> {
            // As the server has it now (the panel follows each change).
            val rule = state.rules.firstOrNull { it.key == picked.rule.key } ?: picked.rule
            val newOnly = recordings.isNewOnly(rule)
            rule.subject?.title.orEmpty() to listOf(
                ItemAction.Cycle(
                    stringResource(R.string.dvr_episodes),
                    stringResource(if (newOnly) R.string.dvr_new_only else R.string.dvr_new_and_repeats)
                ) { recordings.setNewOnly(pid, rule, !newOnly) },
                stop(rule)
            )
        }
    }
    ItemActionsPanel(title, actions, close)
}

private val SECTION_GAP = 18.dp
private val DOT_SPACE = 28.dp
private const val MS = 1000L
