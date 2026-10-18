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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
private const val WINDOW_MS = 5 * HALF_HOUR // 2.5 hours on screen
private val DpPerMinute = 6.dp
private val ChannelColumn = 250.dp

/**
 * The full TV guide grid. Rows come from the same paged channel list; programmes are fetched only for rows on
 * screen and only for the visible time window. Pressing left/right at the edge of the window moves it by an hour.
 */
@Composable
fun GuideGrid(vm: LiveViewModel, play: (ChannelEntity) -> Unit) {
    val items = vm.channels.collectAsLazyPagingItems()
    val epg = LocalContext.current.container.epg
    val scope = rememberCoroutineScope()
    var windowStart by remember { mutableLongStateOf(System.currentTimeMillis() / HALF_HOUR * HALF_HOUR) }
    val guideVersion by epg.changes.collectAsState()
    val now by rememberMinuteClock()
    // A fresh loader per time window: rows on screen fetch just that window's programmes in batches.
    val loader = remember(windowStart) {
        val start = windowStart
        BatchLoader(scope) { ids ->
            epg.programmes(vm.playlistId.value, ids, start, start + WINDOW_MS).let { r -> ids.associateWith { r[it].orEmpty() } }
        }
    }
    // After a guide update, reload the rows already shown (keeping them on screen meanwhile).
    LaunchedEffect(guideVersion) { loader.refreshAll() }
    // Read only inside FocusedInfo, so moving focus around the grid doesn't recompose the grid itself.
    val focused = remember { mutableStateOf<Pair<ChannelEntity, ChannelProgramme?>?>(null) }

    /** Moves the window; returns false at the limits so the key can move focus normally (e.g. to the categories). */
    fun shift(by: Long): Boolean {
        val now = System.currentTimeMillis() / HALF_HOUR * HALF_HOUR
        val next = (windowStart + by).coerceIn(now, now + 3 * 24 * 3_600_000L)
        if (next == windowStart) return false
        windowStart = next
        return true
    }

    Column(Modifier.fillMaxSize()) {
        FocusedInfo(focused)
        TimeHeader(windowStart)
        val nowLine = (now - windowStart) / 60_000f
        Box(Modifier.fillMaxSize()) {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp), contentPadding = PaddingValues(bottom = 40.dp)) {
                items(count = items.itemCount, key = items.itemKey { it.streamId }) { index ->
                    val ch = items[index] ?: return@items
                    LaunchedEffect(ch.streamId, loader) { loader.request(ch.streamId) }
                    GuideRow(
                        ch = ch,
                        programmes = loader.entries[ch.streamId],
                        windowStart = windowStart,
                        now = now,
                        onFocus = { p -> focused.value = ch to p },
                        onPlay = { play(ch) },
                        onOptions = { vm.optionsFor = ch },
                        onEdge = { dir -> shift(dir * 2 * HALF_HOUR) }
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
    onFocus: (ChannelProgramme?) -> Unit,
    onPlay: () -> Unit,
    onOptions: () -> Unit,
    onEdge: (Int) -> Boolean
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
            // One cell for the whole window so the row stays focusable and playable.
            Cell(
                null, DpPerMinute * (WINDOW_MS / 60_000f), live = true, first = true, last = true, onFocus, onPlay, onOptions, onEdge,
                if (programmes ==
                    null
                ) {
                    ""
                } else {
                    "No guide information"
                }
            )
        } else {
            var cursor = windowStart
            visible.forEachIndexed { i, p ->
                val start = maxOf(p.startMs, windowStart)
                if (start > cursor) Spacer(Modifier.width(DpPerMinute * ((start - cursor) / 60_000f)))
                val end = minOf(p.stopMs, windowEnd)
                val width = DpPerMinute * ((end - start) / 60_000f)
                Cell(p, width, p.isLive(now), i == 0, i == visible.lastIndex, onFocus, onPlay, onOptions, onEdge, p.title)
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
    onFocus: (ChannelProgramme?) -> Unit,
    onPlay: () -> Unit,
    onOptions: () -> Unit,
    onEdge: (Int) -> Boolean,
    title: String
) {
    TvCard(
        onClick = onPlay,
        onLongClick = onOptions,
        color = if (live) Palette.surface else Palette.surfaceLow,
        modifier = Modifier.width(width).fillMaxHeight().padding(horizontal = 2.dp)
            .onFocusChanged { if (it.isFocused) onFocus(p) }
            .onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when {
                    e.key == Key.DirectionLeft && first -> onEdge(-1)
                    e.key == Key.DirectionRight && last -> onEdge(1)
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
