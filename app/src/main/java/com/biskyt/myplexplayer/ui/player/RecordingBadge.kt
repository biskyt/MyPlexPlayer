package com.biskyt.myplexplayer.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.biskyt.myplexplayer.R
import com.biskyt.myplexplayer.data.RecordingMark
import com.biskyt.myplexplayer.data.db.ChannelEntity
import com.biskyt.myplexplayer.ui.common.RecordingDot
import com.biskyt.myplexplayer.ui.common.rememberRecordings
import com.biskyt.myplexplayer.ui.theme.Palette

/** "● Recording" while the channel playing ([channel]) is recording (MyPlexPlayer's own: Plex's DVR). */
@Composable
fun RecordingBadge(channel: ChannelEntity?, modifier: Modifier = Modifier) {
    val ch = channel ?: return
    val state by rememberRecordings(ch.playlistId)
    state.recordingOn(ch.providerEpgId) ?: return
    Row(
        modifier.clip(RoundedCornerShape(9.dp)).background(PanelShade).padding(horizontal = 14.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RecordingDot(RecordingMark.RECORDING)
        Text(stringResource(R.string.dvr_recording_now), style = MaterialTheme.typography.titleMedium, color = Palette.text)
    }
}
