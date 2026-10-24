package com.biskyt.mytvplayer.ui.common

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.biskyt.mytvplayer.R
import com.biskyt.mytvplayer.appString
import com.biskyt.mytvplayer.container
import com.biskyt.mytvplayer.data.GuideStep
import com.biskyt.mytvplayer.data.SyncState
import com.biskyt.mytvplayer.ui.Navigator
import com.biskyt.mytvplayer.ui.isPlayback
import com.biskyt.mytvplayer.ui.theme.LocalAccent
import com.biskyt.mytvplayer.ui.theme.Palette
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow

/** What a background update is doing, in words: what it is, the step it's on, and how far along that is (when known). */
data class SyncText(val title: String, val detail: String?, val fraction: Float? = null, val failed: Boolean = false)

/** [state] in words, null when idle. */
fun syncText(state: SyncState): SyncText? = when (state) {
    SyncState.Idle -> null
    is SyncState.Channels ->
        SyncText(appString(R.string.common_sync_channels_start), loaded(state.playlist, state.loaded, R.string.common_sync_channels_loaded))
    is SyncState.Vod -> SyncText(
        appString(R.string.common_sync_vod_start),
        loaded(state.playlist, state.loaded, R.string.common_sync_vod_loaded)
    )
    is SyncState.Guide -> SyncText(appString(R.string.common_sync_guide), state.step.text(), state.step.fraction)
    is SyncState.Failed -> SyncText(appString(R.string.common_last_update_failed), state.message, failed = true)
}

/** The playlist being updated, and how much of it ([count], with [counted]: "%1$s: %2$s loaded") has loaded. */
private fun loaded(playlist: String, count: Int, @StringRes counted: Int) = when {
    count > 0 -> appString(counted, playlist, formatCount(count))
    else -> playlist.ifBlank { null }
}

private fun GuideStep.text(): String = when (this) {
    is GuideStep.Downloading -> appString(R.string.data_downloading, guide)
    is GuideStep.Reading -> appString(R.string.data_reading, guide)
    is GuideStep.Matching -> when (total) {
        0 -> appString(R.string.data_matching)
        else -> appString(R.string.data_matching_progress, formatCount(done), formatCount(total))
    }
    is GuideStep.Loading -> when (guide) {
        null -> appString(R.string.data_loading_programmes_guide)
        else -> appString(R.string.data_loading_programmes, guide)
    }
}

/** How long a failed update stays in the corner. */
private const val FAILURE_SHOWN_MS = 10_000L

/**
 * A background update (channels, guide, movies and series), in the bottom-right corner of every screen but playback:
 * in the accent colour while it runs, then gone; a failure shows in red for a while. It never takes focus.
 */
@Composable
fun SyncToast(nav: Navigator, modifier: Modifier = Modifier, updates: StateFlow<SyncState> = LocalContext.current.container.sync.state) {
    val state by updates.collectAsState()
    // Kept over playback too: a failure already shown doesn't come back after watching something.
    var shownFailure by remember { mutableStateOf<SyncState?>(null) }
    if (state is SyncState.Failed) {
        LaunchedEffect(state) {
            delay(FAILURE_SHOWN_MS)
            shownFailure = state
        }
    }
    if (state === shownFailure || nav.current.screen.isPlayback) return
    val text = syncText(state) ?: return
    val colour = if (text.failed) Palette.danger else LocalAccent.current.color
    val shape = RoundedCornerShape(14.dp)
    Column(
        // Clear of a phone's navigation bar.
        modifier.windowInsetsPadding(
            WindowInsets.navigationBars
        ).widthIn(max = 380.dp).fillMaxWidth().clip(shape).background(Palette.surfaceHigh).border(2.dp, colour, shape)
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Text(text.title, style = MaterialTheme.typography.titleSmall, color = colour, maxLines = 1, overflow = TextOverflow.Ellipsis)
        text.detail?.let { Muted(it) }
        text.fraction?.let { ProgressLine(it, Modifier.padding(top = 8.dp), height = 4.dp) }
    }
}
