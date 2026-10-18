package com.biskyt.mytvplayer.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.biskyt.mytvplayer.container
import com.biskyt.mytvplayer.data.db.ChannelProgramme
import com.biskyt.mytvplayer.ui.theme.Palette

/** Programmes for one channel from now until tomorrow, loaded from the local guide. */
@Composable
fun rememberSchedule(playlistId: Long, streamId: Long): List<ChannelProgramme>? {
    val epg = LocalContext.current.container.epg
    val guideVersion by epg.changes.collectAsState()
    val list by produceState<List<ChannelProgramme>?>(null, playlistId, streamId, guideVersion) {
        val now = System.currentTimeMillis()
        value = epg.programmes(playlistId, listOf(streamId), now, now + 24 * 3_600_000L)[streamId].orEmpty()
    }
    return list
}

/** Vertical list of upcoming programmes; the current one is marked and gets focus. */
@Composable
fun ScheduleList(programmes: List<ChannelProgramme>?, modifier: Modifier = Modifier, onSelect: (ChannelProgramme) -> Unit = {}) {
    val now by rememberMinuteClock()
    val first = rememberFirstFocus(programmes?.isNotEmpty() == true)
    when {
        programmes == null -> Unit
        programmes.isEmpty() -> Muted("No guide information for this channel.", modifier, maxLines = 2)
        else -> LazyColumn(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            itemsIndexed(programmes, key = { _, p -> p.startMs }) { i, p ->
                val live = p.isLive(now)
                TvCard(onClick = { onSelect(p) }, modifier = (if (i == 0) first else Modifier).fillMaxWidth(), color = Palette.surfaceLow) {
                    Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(
                            formatTime(p.startMs),
                            style = MaterialTheme.typography.bodyMedium,
                            color = Palette.textMuted,
                            modifier = Modifier.width(52.dp)
                        )
                        Column(Modifier.weight(1f)) {
                            Text(p.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (live) Text("ON NOW", style = MaterialTheme.typography.labelSmall, color = Palette.live)
                            val detail = p.subtitle ?: p.description
                            if (detail != null && (live || i < 3)) {
                                Text(
                                    detail,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Palette.textMuted,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * A panel sliding in from the right over a dimmed screen. Shown as a dialog so focus stays inside it and Back closes it.
 */
@Composable
fun SidePanel(onDismiss: () -> Unit, width: Dp = 430.dp, dim: Boolean = true, content: @Composable () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Box(
            Modifier.fillMaxSize().background(
                if (dim) Palette.background.copy(alpha = 0.6f) else androidx.compose.ui.graphics.Color.Transparent
            ),
            contentAlignment = Alignment.CenterEnd
        ) {
            Box(Modifier.fillMaxHeight().width(width).background(Palette.surface.copy(alpha = 0.97f)).padding(28.dp)) { content() }
        }
    }
}
