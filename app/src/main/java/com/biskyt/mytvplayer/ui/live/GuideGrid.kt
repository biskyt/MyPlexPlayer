package com.biskyt.mytvplayer.ui.live

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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.biskyt.mytvplayer.container
import com.biskyt.mytvplayer.data.db.ChannelEntity
import com.biskyt.mytvplayer.data.db.ChannelProgramme
import com.biskyt.mytvplayer.ui.common.BatchLoader
import com.biskyt.mytvplayer.ui.common.ChannelLogo
import com.biskyt.mytvplayer.ui.common.Muted
import com.biskyt.mytvplayer.ui.common.TvCard
import com.biskyt.mytvplayer.ui.common.formatRange
import com.biskyt.mytvplayer.ui.common.formatTime
import com.biskyt.mytvplayer.ui.common.isLive
import com.biskyt.mytvplayer.ui.common.numberLabel
import com.biskyt.mytvplayer.ui.common.rememberMinuteClock
import com.biskyt.mytvplayer.ui.theme.LocalAccent
import com.biskyt.mytvplayer.ui.theme.Palette

private const val HALF_HOUR = 30 * 60_000L
private const val HOUR = 2 * HALF_HOUR
private const val WINDOW_MS = 5 * HALF_HOUR // 2.5 hours on screen

// Programmes are fetched in 6-hour chunks starting on 3-hour boundaries, so the 2.5-hour window always fits in one
// chunk and moving it by an hour usually needs no new query (and no rows blanking while they reload).
private const val CHUNK_STEP = 3 * HOUR
private const val CHUNK_MS = 6 * HOUR

/** Where focus should land: the programme on [time] in channel [streamId] (null = the first row). */
private data class GuideFocus(val streamId: Long?, val time: Long)
private val DpPerMinute = 6.dp
private val ChannelColumn = 250.dp

/**
 * The full TV guide grid. Rows come from the same paged channel list; programmes are fetched only for rows on
 * screen and only around the visible time window. Pressing left/right at the edge of the window moves it by an hour,
 * and focus follows on to the next/previous programme of the same channel.
 */
@Composable
fun GuideGrid(vm: LiveViewModel, play: (ChannelEntity) -> Unit) {
    val items = vm.channels.collectAsLazyPagingItems()
    val epg = LocalContext.current.container.epg
    val scope = rememberCoroutineScope()
    var windowStart by remember { mutableLongStateOf(System.currentTimeMillis() / HALF_HOUR * HALF_HOUR) }
    val guideVersion by epg.changes.collectAsState()
    val now by rememberMinuteClock()
    // A fresh loader per chunk: rows on screen fetch just that chunk's programmes in batches.
    val chunkStart = windowStart / CHUNK_STEP * CHUNK_STEP
    val loader = remember(chunkStart) {
        BatchLoader(scope) { ids ->
            epg.programmes(vm.playlistId.value, ids, chunkStart, chunkStart + CHUNK_MS).let { r -> ids.associateWith { r[it].orEmpty() } }
        }
    }
    // After a guide update, reload the rows already shown (keeping them on screen meanwhile).
    LaunchedEffect(guideVersion) { loader.refreshAll() }
    // Read only inside FocusedInfo, so moving focus around the grid doesn't recompose the grid itself.
    val focused = remember { mutableStateOf<Pair<ChannelEntity, ChannelProgramme?>?>(null) }
    val focusTarget = remember { mutableStateOf<GuideFocus?>(null) }
    // Arriving from the list (or the player, or after a move): focus what's on now on the channel that had focus.
    LaunchedEffect(vm.restoreFocusPending) {
        if (vm.restoreFocusPending) {
            vm.restoreFocusPending = false
            focusTarget.value = GuideFocus(vm.focusStreamId, System.currentTimeMillis())
            vm.revealFocused()
        }
    }

    /**
     * Left/right past the window edge: moves the window at least an hour, and far enough to show the time just
     * beyond [p], then carries focus there. Returns false at the limits so the key can move focus normally
     * (e.g. left to the categories).
     */
    fun edge(ch: ChannelEntity, dir: Int, p: ChannelProgramme?): Boolean {
        val target = if (dir > 0) p?.stopMs ?: (windowStart + WINDOW_MS) else (p?.startMs ?: windowStart) - 1
        val wanted = if (dir > 0) {
            maxOf(windowStart + HOUR, target / HALF_HOUR * HALF_HOUR - WINDOW_MS + HALF_HOUR)
        } else {
            minOf(windowStart - HOUR, target / HALF_HOUR * HALF_HOUR)
        }
        val now = System.currentTimeMillis() / HALF_HOUR * HALF_HOUR
        val next = wanted.coerceIn(now, now + 3 * 24 * 3_600_000L)
        if (next == windowStart) return false
        windowStart = next
        focusTarget.value = GuideFocus(ch.streamId, target.coerceIn(next, next + WINDOW_MS - 1))
        return true
    }

    Column(Modifier.fillMaxSize()) {
        FocusedInfo(focused)
        TimeHeader(windowStart)
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
                    val target by remember(ch.streamId, index) {
                        derivedStateOf { focusTarget.value?.takeIf { it.streamId == ch.streamId || (it.streamId == null && index == 0) } }
                    }
                    GuideRow(
                        ch = ch,
                        programmes = loader.entries[ch.streamId],
                        windowStart = windowStart,
                        now = now,
                        focusTime = target?.time,
                        onFocusTaken = { loaded -> if (loaded && focusTarget.value === target) focusTarget.value = null },
                        onFocus = { p ->
                            focused.value = ch to p
                            vm.focusMoved(ch.streamId, index)
                        },
                        onPlay = { play(ch) },
                        onOptions = { vm.optionsFor = ch },
                        onEdge = { dir, p -> edge(ch, dir, p) }
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

@Composable
private fun FocusedInfo(focused: State<Pair<ChannelEntity, ChannelProgramme?>?>) {
    Column(Modifier.fillMaxWidth().height(64.dp)) {
        val (ch, p) = focused.value ?: return@Column
        if (p != null) {
            Text(p.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Muted("${ch.name} · ${formatRange(p.startMs, p.stopMs)}" + (p.description?.let { " · $it" } ?: ""))
        } else {
            Text(ch.name, style = MaterialTheme.typography.titleMedium)
            Muted("No guide information")
        }
    }
}

@Composable
private fun TimeHeader(windowStart: Long) {
    Row(Modifier.fillMaxWidth().height(30.dp)) {
        Spacer(Modifier.width(ChannelColumn))
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
    programmes: List<ChannelProgramme>?,
    windowStart: Long,
    now: Long,
    focusTime: Long?,
    onFocusTaken: (loaded: Boolean) -> Unit,
    onFocus: (ChannelProgramme?) -> Unit,
    onPlay: () -> Unit,
    onOptions: () -> Unit,
    onEdge: (Int, ChannelProgramme?) -> Boolean
) {
    val windowEnd = windowStart + WINDOW_MS
    Row(Modifier.fillMaxWidth().height(60.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.width(ChannelColumn).padding(end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(ch.numberLabel, style = MaterialTheme.typography.bodyMedium, color = Palette.textDim, modifier = Modifier.width(44.dp))
            ChannelLogo(ch.logoUrl, ch.name, 56.dp, 34.dp)
            Text(
                ch.name,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 10.dp)
            )
        }
        val visible =
            remember(programmes, windowStart) { programmes.orEmpty().filter { it.stopMs > windowStart && it.startMs < windowEnd } }
        if (visible.isEmpty()) {
            // One cell for the whole window so the row stays focusable and playable. While loading it holds focus for
            // a pending target, which then moves to the real programme once it arrives.
            Cell(
                null, DpPerMinute * (WINDOW_MS / 60_000f), live = true, first = true, last = true,
                focusKey = focusTime?.let { it to programmes }, onFocusTaken = { onFocusTaken(programmes != null) },
                onFocus, onPlay, onOptions, onEdge,
                if (programmes == null) "" else "No guide information"
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
                    focusKey = if (i == focusIndex) focusTime else null, onFocusTaken = { onFocusTaken(true) },
                    onFocus, onPlay, onOptions, onEdge, p.title
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
    focusKey: Any?,
    onFocusTaken: () -> Unit,
    onFocus: (ChannelProgramme?) -> Unit,
    onPlay: () -> Unit,
    onOptions: () -> Unit,
    onEdge: (Int, ChannelProgramme?) -> Boolean,
    title: String
) {
    val requester = if (focusKey != null) remember { FocusRequester() } else null
    if (requester != null) LaunchedEffect(focusKey) { runCatching { requester.requestFocus() } }
    TvCard(
        onClick = onPlay,
        onLongClick = onOptions,
        color = if (live) Palette.surface else Palette.surfaceLow,
        modifier = Modifier.width(width).fillMaxHeight().padding(horizontal = 2.dp)
            .then(if (requester != null) Modifier.focusRequester(requester) else Modifier)
            .onFocusChanged {
                if (it.isFocused) {
                    onFocus(p)
                    if (focusKey != null) onFocusTaken() // the target is only done once focus has really arrived
                }
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
