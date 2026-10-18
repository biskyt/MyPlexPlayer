package com.biskyt.mytvplayer.ui.live

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.biskyt.mytvplayer.data.db.ChannelEntity
import com.biskyt.mytvplayer.ui.LocalNavigator
import com.biskyt.mytvplayer.ui.Screen
import com.biskyt.mytvplayer.ui.common.ChannelLogo
import com.biskyt.mytvplayer.ui.common.Muted
import com.biskyt.mytvplayer.ui.common.ScheduleList
import com.biskyt.mytvplayer.ui.common.SidePanel
import com.biskyt.mytvplayer.ui.common.TvCard
import com.biskyt.mytvplayer.ui.common.numberLabel
import com.biskyt.mytvplayer.ui.common.rememberFirstFocus
import com.biskyt.mytvplayer.ui.common.rememberSchedule
import com.biskyt.mytvplayer.ui.theme.Palette

/** Long-press options for a channel (agreed "LiveMenu" design). */
@Composable
fun ChannelOptionsSheet(ch: ChannelEntity, vm: LiveViewModel, play: (ChannelEntity) -> Unit) {
    val nav = LocalNavigator.current
    val favs by vm.favouriteIds.collectAsState()
    var showGuide by remember { mutableStateOf(false) }
    val close = { vm.optionsFor = null }
    SidePanel(onDismiss = { if (showGuide) showGuide = false else close() }) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 8.dp)) {
                ChannelLogo(ch.logoUrl, ch.name, 72.dp, 44.dp)
                Column(Modifier.padding(start = 14.dp)) {
                    Text(ch.name, style = MaterialTheme.typography.titleLarge, maxLines = 2)
                    Muted(ch.numberLabel)
                }
            }
            if (showGuide) {
                ScheduleList(rememberSchedule(ch.playlistId, ch.streamId), onSelect = {
                    close()
                    play(ch)
                })
            } else {
                Option("Watch", modifier = rememberFirstFocus()) {
                    close()
                    play(ch)
                }
                Option(if (ch.streamId in favs) "Remove from favourites" else "Add to favourites") { vm.toggleFavourite(ch) }
                Option("Show TV guide for this channel", "Upcoming programmes") { showGuide = true }
                Option("Change guide source…", "Pick which guide data this channel uses") {
                    close()
                    nav.push(Screen.GuideMatching(ch.playlistId, ch.streamId))
                }
                if (vm.canMove) {
                    Option("Move up") { vm.move(ch, -1) }
                    Option("Move down") { vm.move(ch, 1) }
                }
                Option("Hide channel", "Unhide later in Settings › Languages & categories") { vm.hide(ch) }
            }
        }
    }
}

@Composable
private fun Option(label: String, help: String? = null, modifier: Modifier = Modifier, onClick: () -> Unit) {
    TvCard(onClick = onClick, modifier = modifier.fillMaxWidth(), color = Palette.surface) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(label, style = MaterialTheme.typography.titleMedium)
            if (help != null) Muted(help)
        }
    }
}
