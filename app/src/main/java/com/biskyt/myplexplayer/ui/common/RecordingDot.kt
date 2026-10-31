package com.biskyt.myplexplayer.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.biskyt.myplexplayer.R
import com.biskyt.myplexplayer.container
import com.biskyt.myplexplayer.data.RecordingMark
import com.biskyt.myplexplayer.data.RecordingState

private val DOT = 10.dp

/**
 * Source [playlistId]'s recordings as last read, for marks: kept up to date while the app shows (see
 * `Recordings.startWatching`), so this doesn't ask the server again.
 */
@Composable
fun rememberRecordings(playlistId: Long): State<RecordingState> {
    val recordings = LocalContext.current.container.recordings
    return remember(playlistId) { recordings.watch(playlistId) }.collectAsState(recordings.current(playlistId))
}

/** The red of a recording, as on a recorder's button. */
val RecordRed = Color(0xFFE53935)

/**
 * A programme set to record: a red dot; two for a series (its airings set to record by a series recording); a dot
 * with a white ring while it records.
 */
@Composable
fun RecordingDot(mark: RecordingMark, modifier: Modifier = Modifier) {
    val label = stringResource(
        when (mark) {
            RecordingMark.EPISODE -> R.string.dvr_set_to_record
            RecordingMark.SERIES -> R.string.dvr_series_set_to_record
            RecordingMark.RECORDING -> R.string.dvr_recording_now
        }
    )
    Row(modifier.semantics { contentDescription = label }) {
        Dot(ring = mark == RecordingMark.RECORDING)
        if (mark == RecordingMark.SERIES) Dot(ring = false, Modifier.offset(x = -DOT / 3))
    }
}

@Composable
private fun Dot(ring: Boolean, modifier: Modifier = Modifier) {
    Box(
        modifier.size(DOT).background(RecordRed, CircleShape)
            .then(if (ring) Modifier.border(2.dp, Color.White, CircleShape) else Modifier)
    )
}
