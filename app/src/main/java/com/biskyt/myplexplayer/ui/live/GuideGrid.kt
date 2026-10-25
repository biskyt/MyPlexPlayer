package com.biskyt.myplexplayer.ui.live

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.itemKey
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.biskyt.myplexplayer.R
import com.biskyt.myplexplayer.container
import com.biskyt.myplexplayer.data.db.ChannelEntity
import com.biskyt.myplexplayer.data.db.ChannelProgramme
import com.biskyt.myplexplayer.data.programmeTitle
import com.biskyt.myplexplayer.ui.common.BatchLoader
import com.biskyt.myplexplayer.ui.common.ChannelLogo
import com.biskyt.myplexplayer.ui.common.LocalTouch
import com.biskyt.myplexplayer.ui.common.Muted
import com.biskyt.myplexplayer.ui.common.TouchStepButtons
import com.biskyt.myplexplayer.ui.common.TvCard
import com.biskyt.myplexplayer.ui.common.collectIds
import com.biskyt.myplexplayer.ui.common.formatRange
import com.biskyt.myplexplayer.ui.common.formatTime
import com.biskyt.myplexplayer.ui.common.isLive
import com.biskyt.myplexplayer.ui.common.numberLabel
import com.biskyt.myplexplayer.ui.common.rememberDetails
import com.biskyt.myplexplayer.ui.common.rememberMinuteClock
import com.biskyt.myplexplayer.ui.theme.LocalAccent
import com.biskyt.myplexplayer.ui.theme.Palette

private const val HALF_HOUR = 30 * 60_000L
private const val HOUR = 2 * HALF_HOUR
private const val WINDOW_MS = 5 * HALF_HOUR // 2.5 hours on screen

// Programmes are fetched in 6-hour chunks starting on 3-hour boundaries, so the 2.5-hour window always fits in one
// chunk and moving it by an hour usually needs no new query (and no rows blanking while they reload).
private const val CHUNK_STEP = 3 * HOUR
private const val CHUNK_MS = 6 * HOUR
private const val DAY_MS = 24 * HOUR

/** Where focus should land: the programme on [time] in channel [streamId] (null = the first row). */
private data class GuideFocus(val streamId: Long?, val time: Long)

/** What the Live screen around the grid knows of focus: whether it has it, and how often the remote's arrows were pressed. */
class ScreenFocus {
    var hasFocus = false
    var arrowPresses = 0
}

/** Where focus goes in the grid once that row's cell is there ([value]); null when there's nowhere to go. */
private class FocusTarget(private val screen: ScreenFocus) {
    var value by mutableStateOf<GuideFocus?>(null)
    private var gridHasFocus = false

    /** Whether the grid has had focus since the target was set. */
    private var gridHadFocus = false
    private var pressesWhenSet = 0

    fun set(target: GuideFocus) {
        gridHadFocus = gridHasFocus
        pressesWhenSet = screen.arrowPresses
        value = target
    }

    fun gridFocusChanged(hasFocus: Boolean) {
        gridHasFocus = hasFocus
        if (hasFocus) gridHadFocus = true
    }

    /**
     * Whether the target's cell may still take focus: not once focus has left the grid for somewhere else on the screen
     * (up to the filter, the categories: moved there with the remote, or thrown there) while the row's programmes were
     * on their way. The target is then done, and focus stays there. Arriving at the grid, or a placeholder cell
     * replaced by the programmes (focus briefly nowhere), carries on.
     */
    fun mayTakeFocus(): Boolean {
        val movedAway = !gridHasFocus && (screen.arrowPresses != pressesWhenSet || (gridHadFocus && screen.hasFocus))
        if (movedAway) value = null
        return !movedAway
    }
}

private val DpPerMinute = 6.dp
private val ChannelColumn = 288.dp

/**
 * The full TV guide grid. Rows come from the same paged channel list; programmes are fetched only for rows on
 * screen and only around the visible time window. Pressing left/right at the edge of the window moves it by an hour,
 * and focus follows on to the next/previous programme of the same channel.
 */
@Composable
fun GuideGrid(vm: LiveViewModel, items: LazyPagingItems<ChannelEntity>, play: (ChannelEntity) -> Unit, screen: ScreenFocus) {
    val container = LocalContext.current.container
    val epg = container.epg
    val scope = rememberCoroutineScope()
    val guideVersion by epg.changes.collectAsState()
    val now by rememberMinuteClock()
    val favourites = vm.favouriteIds.collectIds()
    val playlistId by vm.playlistId.collectAsState()
    val window = remember(playlistId) { GuideWindow() } // another source's guide opens at now
    val windowStart = window.start
    // A fresh loader per chunk: rows on screen fetch just that chunk's programmes in batches.
    val chunkStart = windowStart / CHUNK_STEP * CHUNK_STEP
    val loader = remember(chunkStart) {
        BatchLoader(scope) { ids ->
            epg.programmes(vm.playlistId.value, ids, chunkStart, chunkStart + CHUNK_MS, details = false)
                .let { r -> ids.associateWith { r[it].orEmpty() } }
        }
    }
    // After a guide update, reload the rows already shown (keeping them on screen meanwhile).
    LaunchedEffect(guideVersion) { loader.refreshAll() }
    // Read only inside FocusedInfo, so moving focus around the grid doesn't recompose the grid itself.
    val focused = remember { mutableStateOf<Pair<ChannelEntity, ChannelProgramme?>?>(null) }
    val focusTarget = remember { FocusTarget(screen) }
    // Arriving from the list (or the player, or after a move): focus what's on now on the channel that had focus.
    LaunchedEffect(vm.restoreFocusPending) {
        if (vm.restoreFocusPending) {
            vm.restoreFocusPending = false
            focusTarget.set(GuideFocus(vm.focusStreamId, System.currentTimeMillis()))
            vm.revealFocused()
        }
    }

    /**
     * Left/right past the window edge: moves the window at least an hour, and far enough to show the time just
     * beyond [p], then carries focus there. Returns false at the limits so the key can move focus normally
     * (e.g. left to the categories).
     */
    fun edge(ch: ChannelEntity, dir: Int, p: ChannelProgramme?): Boolean =
        window.move(dir, p) { focusTarget.set(GuideFocus(ch.streamId, it)) }

    Column(Modifier.fillMaxSize().onFocusChanged { focusTarget.gridFocusChanged(it.hasFocus) }) {
        FocusedInfo(focused)
        TimeHeader(windowStart) { dir -> window.move(dir, null) }
        val nowLine = (now - windowStart) / 60_000f
        Box(Modifier.fillMaxSize()) {
            LazyColumn(
                state = vm.listState,
                verticalArrangement = Arrangement.spacedBy(4.dp),
                contentPadding = PaddingValues(bottom = 40.dp)
            ) {
                items(count = items.itemCount, key = items.itemKey { it.streamId }) { index ->
                    val ch = items[index] ?: return@items
                    LaunchedEffect(ch.streamId, loader) { loader.request(ch.streamId) }
                    // Derived per row, so a new target only recomposes the row it concerns.
                    val focusTime by remember(ch.streamId, index) {
                        derivedStateOf {
                            focusTarget.value?.takeIf { it.streamId == ch.streamId || (it.streamId == null && index == 0) }?.time
                        }
                    }
                    val (programmes, loaded) = rowProgrammes(loader.entries[ch.streamId])
                    GuideRow(
                        ch = ch,
                        favourite = ch.streamId in favourites,
                        programmes = programmes,
                        loaded = loaded,
                        windowStart = windowStart,
                        now = now,
                        focusTime = focusTime,
                        onFocus = { p, isTarget ->
                            focused.value = ch to p
                            vm.focusMoved(ch.streamId, index)
                            // The target is done once its cell has focus with the programmes loaded (until then the
                            // placeholder holds focus, and the real cell takes it when they arrive). Focus moved by the
                            // user ends it too: it mustn't pull focus back when a row's programmes arrive later.
                            if (!isTarget || loaded) focusTarget.value = null
                        },
                        onPlay = { play(ch) },
                        // The programme with focus on this row, so its options can offer to find it on demand.
                        onOptions = { vm.openOptions(ch, focused.value?.takeIf { it.first.streamId == ch.streamId }?.second) },
                        onEdge = { dir, p -> edge(ch, dir, p) },
                        mayTakeFocus = focusTarget::mayTakeFocus
                    )
                }
            }
            if (nowLine in 0f..(WINDOW_MS / 60_000f)) {
                Box(
                    Modifier.offset(
                        x = ChannelColumn + DpPerMinute * nowLine
                    ).width(2.dp).fillMaxHeight().background(LocalAccent.current.color)
                )
            }
        }
    }
}

/**
 * A row's programmes, and whether they're the ones asked for ([loaded], null while loading). While a row loads another
 * stretch of time it keeps showing what it had: the cell with focus stays, instead of focus being lost and landing
 * elsewhere.
 */
@Composable
private fun rowProgrammes(loaded: List<ChannelProgramme>?): Pair<List<ChannelProgramme>?, Boolean> {
    val last = remember { arrayOfNulls<List<ChannelProgramme>>(1) }
    if (loaded != null) last[0] = loaded
    return (loaded ?: last[0]) to (loaded != null)
}

@Composable
private fun FocusedInfo(focused: State<Pair<ChannelEntity, ChannelProgramme?>?>) {
    // Two lines for the channel, time and description: most descriptions can be read just by moving around.
    Column(Modifier.fillMaxWidth().height(86.dp)) {
        val (ch, p) = focused.value ?: return@Column
        if (p != null) {
            // Descriptions aren't kept for the whole grid (they're large); the focused one is read when focus rests.
            val about = rememberDetails(ch.playlistId, p, delayMs = 300).value?.description
            Text(programmeTitle(p.title), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Muted("${ch.name} · ${formatRange(p.startMs, p.stopMs)}" + (about?.let { " · $it" } ?: ""), maxLines = 2)
        } else {
            Text(ch.name, style = MaterialTheme.typography.titleMedium)
            Muted(stringResource(R.string.common_no_guide))
        }
    }
}

@Composable
private fun TimeHeader(windowStart: Long, onMove: (dir: Int) -> Unit) {
    // A touch screen has no ◀ ▶ to reach past the edge: buttons above the channels move the window instead.
    Row(Modifier.fillMaxWidth().height(if (LocalTouch.current) 48.dp else 30.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(ChannelColumn)) {
            TouchStepButtons(stringResource(R.string.live_guide_earlier), stringResource(R.string.live_guide_later), onMove)
        }
        for (i in 0 until 5) {
            Text(
                formatTime(windowStart + i * HALF_HOUR),
                style = MaterialTheme.typography.bodyMedium,
                color = Palette.textMuted,
                modifier = Modifier.width(
                    DpPerMinute * 30
                ).padding(start = 8.dp)
            )
        }
    }
}

@Composable
private fun GuideRow(
    ch: ChannelEntity,
    favourite: Boolean,
    programmes: List<ChannelProgramme>?,
    loaded: Boolean,
    windowStart: Long,
    now: Long,
    focusTime: Long?,
    onFocus: (ChannelProgramme?, isTarget: Boolean) -> Unit,
    onPlay: () -> Unit,
    onOptions: () -> Unit,
    onEdge: (Int, ChannelProgramme?) -> Boolean,
    mayTakeFocus: () -> Boolean
) {
    val windowEnd = windowStart + WINDOW_MS
    val noGuide = stringResource(R.string.common_no_guide)
    Row(Modifier.fillMaxWidth().height(60.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.width(ChannelColumn).padding(end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(ch.numberLabel, style = MaterialTheme.typography.bodyMedium, color = Palette.textDim, modifier = Modifier.width(44.dp))
            ChannelLogo(ch.logoUrl, ch.name, 56.dp, 34.dp, favourite)
            // A size down from the list's, on two lines: most channel names fit in full.
            Text(
                ch.name,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 10.dp).weight(1f, fill = false)
            )
        }
        val visible =
            remember(programmes, windowStart) { programmes.orEmpty().filter { it.stopMs > windowStart && it.startMs < windowEnd } }
        if (visible.isEmpty()) {
            // One cell for the whole window so the row stays focusable and playable. While loading it holds focus for
            // a pending target, which then moves to the real programme once it arrives.
            Cell(
                null, DpPerMinute * (WINDOW_MS / 60_000f), live = true, first = true, last = true,
                focusKey = focusTime?.let { Triple(it, programmes, loaded) }, onFocus, onPlay, onOptions, onEdge, mayTakeFocus,
                if (programmes == null) "" else noGuide
            )
        } else {
            // The cell to focus: the programme on the target time, or the nearest one after it (or the last).
            val focusIndex = focusTime?.let { t -> visible.indexOfFirst { it.stopMs > t }.takeIf { it >= 0 } ?: visible.lastIndex }
            var cursor = windowStart
            visible.forEachIndexed { i, p ->
                val start = maxOf(p.startMs, windowStart)
                if (start > cursor) Spacer(Modifier.width(DpPerMinute * ((start - cursor) / 60_000f)))
                val end = minOf(p.stopMs, windowEnd)
                val width = DpPerMinute * ((end - start) / 60_000f)
                Cell(
                    p, width, p.isLive(now), i == 0, i == visible.lastIndex,
                    // Asks again once the row's own programmes arrive (and the target is then done).
                    focusKey = if (i == focusIndex) focusTime?.let { it to loaded } else null,
                    onFocus, onPlay, onOptions, onEdge, mayTakeFocus,
                    programmeTitle(p.title)
                )
                cursor = end
            }
        }
    }
}

@Composable
private fun Cell(
    p: ChannelProgramme?,
    width: androidx.compose.ui.unit.Dp,
    live: Boolean,
    first: Boolean,
    last: Boolean,
    /** Non-null when this cell should take focus; a new value asks again. */
    focusKey: Any?,
    onFocus: (ChannelProgramme?, isTarget: Boolean) -> Unit,
    onPlay: () -> Unit,
    onOptions: () -> Unit,
    onEdge: (Int, ChannelProgramme?) -> Boolean,
    mayTakeFocus: () -> Boolean,
    title: String
) {
    val requester = remember { FocusRequester() }
    val hasFocus = remember { booleanArrayOf(false) }
    if (focusKey != null) {
        LaunchedEffect(focusKey) {
            // Already focused: requesting again wouldn't report anything, so report it directly.
            // Not when the target is done (focus was taken elsewhere meanwhile).
            when {
                hasFocus[0] -> onFocus(p, true)
                mayTakeFocus() -> runCatching { requester.requestFocus() }
            }
        }
    }
    TvCard(
        onClick = onPlay,
        onLongClick = onOptions,
        color = if (live) Palette.surface else Palette.surfaceLow,
        modifier = Modifier.width(width).fillMaxHeight().padding(horizontal = 2.dp)
            .focusRequester(requester)
            .onFocusChanged {
                hasFocus[0] = it.isFocused
                if (it.isFocused) onFocus(p, focusKey != null)
            }
            .onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when {
                    e.key == Key.DirectionLeft && first -> onEdge(-1, p)
                    e.key == Key.DirectionRight && last -> onEdge(1, p)
                    else -> false
                }
            }
    ) {
        Text(
            title,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 18.dp)
        )
    }
}

/** The guide's time window, and moving it (see [nextWindow]). It opens on the current half hour. */
internal class GuideWindow(private val now: () -> Long = System::currentTimeMillis) {
    var start by mutableLongStateOf(now() / HALF_HOUR * HALF_HOUR)
        private set

    /** Moves the window, then [then] gets the time to show beyond [p]. False at the limits. */
    fun move(dir: Int, p: ChannelProgramme?, then: (target: Long) -> Unit = {}): Boolean {
        val (next, target) = nextWindow(start, dir, p, now()) ?: return false
        start = next
        then(target)
        return true
    }
}

/**
 * The window after moving past its edge from [p] in direction [dir]: at least an hour, and far enough to show the
 * time just beyond [p]; with the time focus goes to. Null at the limits (the half hour [nowMs] is in, three days ahead).
 */
private fun nextWindow(windowStart: Long, dir: Int, p: ChannelProgramme?, nowMs: Long): Pair<Long, Long>? {
    val target = if (dir > 0) p?.stopMs ?: (windowStart + WINDOW_MS) else (p?.startMs ?: windowStart) - 1
    val wanted = if (dir > 0) {
        maxOf(windowStart + HOUR, target / HALF_HOUR * HALF_HOUR - WINDOW_MS + HALF_HOUR)
    } else {
        minOf(windowStart - HOUR, target / HALF_HOUR * HALF_HOUR)
    }
    val now = nowMs / HALF_HOUR * HALF_HOUR
    val next = wanted.coerceIn(now, now + 3 * DAY_MS)
    // Back, never later (a window left open since an earlier half hour is already as early as it goes).
    if (next == windowStart || dir < 0 && next > windowStart) return null
    return next to target.coerceIn(next, next + WINDOW_MS - 1)
}
