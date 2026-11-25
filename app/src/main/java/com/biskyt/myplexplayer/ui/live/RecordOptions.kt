package com.biskyt.myplexplayer.ui.live

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.biskyt.myplexplayer.R
import com.biskyt.myplexplayer.container
import com.biskyt.myplexplayer.data.Recordings
import com.biskyt.myplexplayer.data.db.ChannelProgramme
import com.biskyt.myplexplayer.ui.common.BoardOption
import com.biskyt.myplexplayer.ui.common.ConfirmPanel
import com.biskyt.myplexplayer.ui.common.ItemAction
import com.biskyt.myplexplayer.ui.common.cancelRecordingAction
import com.biskyt.myplexplayer.ui.common.rememberRecordings
import com.biskyt.myplexplayer.ui.common.stopSeriesAction

/** [programme]'s recording options: see the other RecordOptions. */
@Composable
fun RecordOptions(playlistId: Long, programme: ChannelProgramme, close: () -> Unit) =
    RecordOptions(playlistId, programme.guid, programme.showGuid, programme.startMs, programme.stopMs, close)

/**
 * A guide programme's recording options (Plex's DVR, on the source [playlistId]): Record and Record series (new
 * airings only) when it isn't set to record; Cancel this recording, and for a series Stop recording the series, when
 * it is. These two ask first, in a panel over the options, as the DVR tab's do. The programme is Plex's [guid] (its
 * show's [showGuid]), from [startMs] to [stopMs]. Nothing for a programme that's over, or one the server gave no id
 * for. Each closes the options ([close]).
 */
@Composable
fun RecordOptions(playlistId: Long, guid: String?, showGuid: String?, startMs: Long, stopMs: Long, close: () -> Unit) {
    if (guid == null || stopMs <= System.currentTimeMillis()) return
    val recordings = LocalContext.current.container.recordings
    val state by rememberRecordings(playlistId)
    fun act(action: Recordings.() -> Unit) {
        close()
        recordings.action()
    }
    val airing = state.airing(guid, startMs)
    val series = state.seriesRule(showGuid)
    // The question being asked, and what it is about (the panel's title).
    var asking by remember { mutableStateOf<Pair<String, ItemAction.Confirmed>?>(null) }
    asking?.let { (name, question) -> ConfirmPanel(name, question) { asking = null } }
    if (airing == null) {
        BoardOption(stringResource(R.string.dvr_record), stringResource(R.string.dvr_record_help)) {
            act { record(playlistId, guid, series = false) }
        }
        if (showGuid != null && series == null) {
            BoardOption(stringResource(R.string.dvr_record_series), stringResource(R.string.dvr_record_series_help)) {
                act { record(playlistId, guid, series = true) }
            }
        }
    } else {
        BoardOption(stringResource(R.string.dvr_cancel), danger = true) {
            asking = airing.title to cancelRecordingAction(airing.title) { act { cancel(playlistId, airing, state.ruleOf(airing)) } }
        }
    }
    if (series != null) {
        BoardOption(stringResource(R.string.dvr_stop_series), danger = true) {
            asking = series.subjectTitle to stopSeriesAction(series.subjectTitle) { act { remove(playlistId, series) } }
        }
    }
}
