package com.biskyt.myplexplayer.ui.common

import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import com.biskyt.myplexplayer.R
import com.biskyt.myplexplayer.appString
import com.biskyt.myplexplayer.container
import com.biskyt.myplexplayer.data.RecordingEvent

/**
 * A toast each time a recording starts or stops. It is Android's own toast, so it shows over everything: the video
 * and the menus and panels too (each panel is a window of its own, above the app's).
 */
@Composable
fun RecordingToasts() {
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        context.container.recordings.events.collect { Toast.makeText(context, toastText(it), Toast.LENGTH_LONG).show() }
    }
}

/** "Recording started: The Harbour". */
fun toastText(event: RecordingEvent): String = appString(
    when (event.kind) {
        RecordingEvent.Kind.STARTED -> R.string.dvr_toast_started
        RecordingEvent.Kind.FINISHED -> R.string.dvr_toast_finished
        RecordingEvent.Kind.STOPPED_EARLY -> R.string.dvr_toast_stopped
    },
    event.title
)
