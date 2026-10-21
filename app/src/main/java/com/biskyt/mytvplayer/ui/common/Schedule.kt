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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.biskyt.mytvplayer.container
import com.biskyt.mytvplayer.data.db.ChannelProgramme
import com.biskyt.mytvplayer.hideSystemBars
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
                            if (live) OnNowLabel()
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

/** Marks a programme on now, wherever programmes are listed. */
@Composable
fun OnNowLabel() = Text("ON NOW", style = MaterialTheme.typography.labelSmall, color = Palette.live)

/**
 * A panel sliding in from the right over a dimmed screen. Shown as a dialog so focus stays inside it and Back closes it.
 */
@Composable
fun SidePanel(onDismiss: () -> Unit, width: Dp = 430.dp, dim: Boolean = true, content: @Composable () -> Unit) {
    // A dialog is its own window, which resets the density to the system's; keep the app's scaling (see MyTvTheme)
    // so panels are the same size as everything else.
    val density = LocalDensity.current
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        CompositionLocalProvider(LocalDensity provides density) {
            // A dialog is its own window: on a phone its bars would come back over the app otherwise.
            if (LocalTouch.current) {
                val dialogView = LocalView.current
                SideEffect { (dialogView.parent as? DialogWindowProvider)?.window?.hideSystemBars() }
            }
            Box(
                Modifier.fillMaxSize().ignoreHeldSelect()
                    // On a touch screen, tapping beside the panel closes it.
                    .onPlainTap(onDismiss)
                    .background(if (dim) Palette.background.copy(alpha = 0.6f) else Color.Transparent),
                contentAlignment = Alignment.CenterEnd
            ) {
                Box(
                    Modifier.fillMaxHeight().width(width)
                        .onPlainTap {} // taps on the panel itself don't close it
                        .background(Palette.surface.copy(alpha = 0.97f)).padding(28.dp)
                ) { content() }
            }
        }
    }
}
