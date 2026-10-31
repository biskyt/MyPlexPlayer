package com.biskyt.myplexplayer.ui.live

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.biskyt.myplexplayer.R
import com.biskyt.myplexplayer.container
import com.biskyt.myplexplayer.data.Recordings
import com.biskyt.myplexplayer.data.db.ChannelProgramme
import com.biskyt.myplexplayer.ui.common.MenuOption

/**
 * A guide programme's recording options (Plex's DVR, on the source [playlistId]): Record and Record series (new
 * airings only) when it isn't set to record; Cancel this recording, and for a series Stop recording the series, when
 * it is. Nothing for a programme that's over, or one the server gave no id for. Each closes the options ([close]).
 */
@Composable
fun RecordOptions(playlistId: Long, programme: ChannelProgramme, close: () -> Unit) {
    val guid = programme.guid ?: return
    if (programme.stopMs <= System.currentTimeMillis()) return
    val recordings = LocalContext.current.container.recordings
    val state by remember(playlistId) { recordings.watch(playlistId) }.collectAsState(recordings.current(playlistId))
    fun act(action: Recordings.() -> Unit) {
        close()
        recordings.action()
    }
    val airing = state.airing(guid, programme.startMs)
    val series = state.seriesRule(programme.showGuid)
    if (airing == null) {
        MenuOption(stringResource(R.string.dvr_record), stringResource(R.string.dvr_record_help)) {
            act { record(playlistId, guid, series = false) }
        }
        if (programme.showGuid != null && series == null) {
            MenuOption(stringResource(R.string.dvr_record_series), stringResource(R.string.dvr_record_series_help)) {
                act { record(playlistId, guid, series = true) }
            }
        }
    } else {
        MenuOption(stringResource(R.string.dvr_cancel)) { act { cancel(playlistId, airing, state.ruleOf(airing)) } }
    }
    if (series != null) {
        MenuOption(stringResource(R.string.dvr_stop_series)) { act { stopSeries(playlistId, series) } }
    }
}
