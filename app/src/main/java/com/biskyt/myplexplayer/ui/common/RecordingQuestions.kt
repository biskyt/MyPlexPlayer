package com.biskyt.myplexplayer.ui.common

import com.biskyt.myplexplayer.R
import com.biskyt.myplexplayer.appString

// Cancelling a recording, and stopping a series recording, ask first, as every delete does: one tap (or a stray OK)
// must not undo what the user set up. The DVR tab and every programme's recording options use these.

/** "Cancel this recording" of what is titled [name]: it asks first, and yes does [run]. */
fun cancelRecordingAction(name: String, run: () -> Unit) = ItemAction.Confirmed(
    appString(R.string.dvr_cancel),
    appString(R.string.dvr_cancel_confirm, name),
    appString(R.string.dvr_cancel_yes),
    run
)

/** "Stop recording the series" called [name], with its episodes set to record: it asks first, and yes does [run]. */
fun stopSeriesAction(name: String, run: () -> Unit) = ItemAction.Confirmed(
    appString(R.string.dvr_stop_series),
    appString(R.string.dvr_stop_series_confirm, name),
    appString(R.string.dvr_stop_series_yes),
    run
)
