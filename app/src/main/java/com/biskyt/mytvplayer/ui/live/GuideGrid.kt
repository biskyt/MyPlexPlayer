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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
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
import com.biskyt.mytvplayer.ui.common.ChannelLogo
import com.biskyt.mytvplayer.ui.common.Muted
import com.biskyt.mytvplayer.ui.common.TvCard
import com.biskyt.mytvplayer.ui.common.formatRange
import com.biskyt.mytvplayer.ui.common.formatTime
import com.biskyt.mytvplayer.ui.theme.LocalAccent
import com.biskyt.mytvplayer.ui.theme.Palette
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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
    val cache = remember(windowStart) { mutableStateMapOf<Long, List<ChannelProgramme>>() }
    val pending = remember(windowStart) { LinkedHashSet<Long>() }
    var batch by remember { mutableStateOf<Job?>(null) }
    var focused by remember { mutableStateOf<Pair<ChannelEntity, ChannelProgramme?>?>(null) }

    fun request(id: Long) {
        if (id in cache || !pending.add(id) || batch?.isActive == true) return
        val start = windowStart
        batch = scope.launch {
            delay(120)
            val ids = pending.toList()
            pending.clear()
            batch = null
            val result = epg.programmes(vm.playlistId.value, ids, start, start + WINDOW_MS)
            ids.forEach { cache[it] = result[it].orEmpty() }
        }
    }

    /** Moves the window; returns false at the limits so the key can move focus normally (e.g. to the categories). */
    fun shift(by: Long): Boolean {
        val now = System.currentTimeMillis() / HALF_HOUR * HALF_HOUR
        val next = (windowStart + by).coerceIn(now, now + 3 * 24 * 3_600_000L)
        if (next == windowStart) return false
        windowStart = next
        return true
    }

    Column(Modifier.fillMaxSize()) {
        // Details of the focused programme.
        Column(Modifier.fillMaxWidth().height(64.dp)) {
            val f = focused
            if (f?.second != null) {
                Text(f.second!!.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Muted("${f.first.name} · ${formatRange(f.second!!.startMs, f.second!!.stopMs)}" + (f.second!!.description?.let { " · $it" } ?: ""))
            } else if (f != null) {
                Text(f.first.name, style = MaterialTheme.typography.titleMedium)
                Muted("No guide information")
            }
        }
        TimeHeader(windowStart)
        val nowLine = (System.currentTimeMillis() - windowStart) / 60_000f
        Box(Modifier.fillMaxSize()) {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp), contentPadding = PaddingValues(bottom = 40.dp)) {
                items(count = items.itemCount, key = items.itemKey { it.streamId }) { index ->
                    val ch = items[index] ?: return@items
                    LaunchedEffect(ch.streamId, windowStart) { request(ch.streamId) }
                    GuideRow(
                        ch = ch,
                        programmes = cache[ch.streamId],
                        windowStart = windowStart,
                        onFocus = { p -> focused = ch to p },
                        onPlay = { play(ch) },
                        onOptions = { vm.optionsFor = ch },
                        onEdge = { dir -> shift(dir * 2 * HALF_HOUR) },
                    )
                }
            }
            if (nowLine in 0f..(WINDOW_MS / 60_000f)) {
                Box(Modifier.offset(x = ChannelColumn + DpPerMinute * nowLine).width(2.dp).fillMaxHeight().background(LocalAccent.current.color))
            }
        }
    }
}

@Composable
private fun TimeHeader(windowStart: Long) {
    Row(Modifier.fillMaxWidth().height(30.dp)) {
        Spacer(Modifier.width(ChannelColumn))
        for (i in 0 until 5) {
            Text(formatTime(windowStart + i * HALF_HOUR), style = MaterialTheme.typography.bodyMedium, color = Palette.textMuted, modifier = Modifier.width(DpPerMinute * 30).padding(start = 8.dp))
        }
    }
}

@Composable
private fun GuideRow(
    ch: ChannelEntity,
    programmes: List<ChannelProgramme>?,
    windowStart: Long,
    onFocus: (ChannelProgramme?) -> Unit,
    onPlay: () -> Unit,
    onOptions: () -> Unit,
    onEdge: (Int) -> Boolean,
) {
    val windowEnd = windowStart + WINDOW_MS
    val now = System.currentTimeMillis()
    Row(Modifier.fillMaxWidth().height(60.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.width(ChannelColumn).padding(end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(ch.number.takeIf { it > 0 }?.toString() ?: "", style = MaterialTheme.typography.bodyMedium, color = Palette.textDim, modifier = Modifier.width(44.dp))
            ChannelLogo(ch.logoUrl, ch.name, 56.dp, 34.dp)
            Text(ch.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 10.dp))
        }
        val visible = programmes.orEmpty().filter { it.stopMs > windowStart && it.startMs < windowEnd }
        if (visible.isEmpty()) {
            // One cell for the whole window so the row stays focusable and playable.
            Cell(null, DpPerMinute * (WINDOW_MS / 60_000f), live = true, first = true, last = true, onFocus, onPlay, onOptions, onEdge, if (programmes == null) "" else "No guide information")
        } else {
            var cursor = windowStart
            visible.forEachIndexed { i, p ->
                val start = maxOf(p.startMs, windowStart)
                if (start > cursor) Spacer(Modifier.width(DpPerMinute * ((start - cursor) / 60_000f)))
                val end = minOf(p.stopMs, windowEnd)
                val live = p.startMs <= now && p.stopMs > now
                Cell(p, DpPerMinute * ((end - start) / 60_000f), live, i == 0, i == visible.lastIndex, onFocus, onPlay, onOptions, onEdge, p.title)
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
    title: String,
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
            },
    ) {
        Text(title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 10.dp, vertical = 18.dp))
    }
}
