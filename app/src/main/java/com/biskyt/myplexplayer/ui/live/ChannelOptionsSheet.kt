package com.biskyt.myplexplayer.ui.live

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.biskyt.myplexplayer.R
import com.biskyt.myplexplayer.core.epg.ProgrammeKind
import com.biskyt.myplexplayer.data.db.CAT_LIVE
import com.biskyt.myplexplayer.data.db.ChannelEntity
import com.biskyt.myplexplayer.data.programmeTitle
import com.biskyt.myplexplayer.ui.LocalNavigator
import com.biskyt.myplexplayer.ui.common.BoardOption
import com.biskyt.myplexplayer.ui.common.BoardPanel
import com.biskyt.myplexplayer.ui.common.BoardPanelSubtitle
import com.biskyt.myplexplayer.ui.common.BoardPanelTitle
import com.biskyt.myplexplayer.ui.common.ChannelLogo
import com.biskyt.myplexplayer.ui.common.ProgrammeAbout
import com.biskyt.myplexplayer.ui.common.ScheduleList
import com.biskyt.myplexplayer.ui.common.collectIds
import com.biskyt.myplexplayer.ui.common.favouriteLabel
import com.biskyt.myplexplayer.ui.common.formatTime
import com.biskyt.myplexplayer.ui.common.numberLabel
import com.biskyt.myplexplayer.ui.common.rememberDetails
import com.biskyt.myplexplayer.ui.common.rememberFirstFocus
import com.biskyt.myplexplayer.ui.common.rememberSchedule
import com.biskyt.myplexplayer.ui.theme.bd

/** Long-press options for a channel (agreed "LiveMenu" design). */
@Composable
fun ChannelOptionsSheet(ch: ChannelEntity, vm: LiveViewModel, play: (ChannelEntity) -> Unit) {
    val nav = LocalNavigator.current
    val favs = vm.favouriteIds.collectIds()
    var showGuide by remember { mutableStateOf(false) }
    val close = vm::closeOptions
    val programme = vm.optionsProgramme
    val findKind = programme?.let(vm::findKind)
    // The channel's guide is a list of its own, which does not go in a scrolling column.
    BoardPanel(onDismiss = { if (showGuide) showGuide = false else close() }, scroll = !showGuide) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 6.bd)) {
            ChannelLogo(ch.logoUrl, ch.name, 72.dp, 44.dp)
            Column(Modifier.padding(start = 14.dp)) {
                BoardPanelTitle(ch.name)
                BoardPanelSubtitle(ch.numberLabel)
            }
        }
        if (programme != null) {
            BoardPanelTitle(programmeTitle(programme.title), small = true)
            BoardPanelSubtitle("${formatTime(programme.startMs)} – ${formatTime(programme.stopMs)}", Modifier.padding(bottom = 4.bd))
            // What the guide says about it, in full (as far as the panel has room, above its options).
            rememberDetails(ch.playlistId, programme).value?.let { ProgrammeAbout(it, maxLines = 5, Modifier.padding(bottom = 4.bd)) }
        }
        if (showGuide) {
            ScheduleList(rememberSchedule(ch.playlistId, ch.streamId), onSelect = {
                close()
                play(ch)
            })
        } else {
            BoardOption(stringResource(R.string.common_watch), modifier = rememberFirstFocus()) {
                close()
                play(ch)
            }
            // MyPlexPlayer's own: recording it with Plex's DVR. From the channel list, what's on now.
            (programme ?: vm.nowNext.entries[ch.streamId]?.now)?.let { RecordOptions(ch.playlistId, it, close) }
            val kind = findKind
            if (programme != null && kind != null) {
                val label = when (kind) {
                    ProgrammeKind.SERIES -> stringResource(R.string.live_option_find_series)
                    ProgrammeKind.MOVIE -> stringResource(R.string.live_option_find_movie)
                    else -> stringResource(R.string.live_option_search_for, programme.title)
                }
                BoardOption(label, stringResource(R.string.live_option_find_help)) {
                    close()
                    // Only if the user is still here when the lookup finishes.
                    val from = nav.current
                    vm.find(ch, programme, kind) { if (nav.current === from) nav.push(it) }
                }
            }
            BoardOption(favouriteLabel(ch.streamId in favs)) { vm.toggleFavourite(ch) }
            BoardOption(stringResource(R.string.live_option_channel_guide), stringResource(R.string.live_option_channel_guide_help)) {
                showGuide = true
            }
            BoardOption(
                stringResource(if (vm.guideMode) R.string.live_option_switch_to_list else R.string.live_option_switch_to_grid),
                stringResource(R.string.live_option_switch_help)
            ) {
                close()
                vm.showGuide(!vm.guideMode, moveFocus = true)
            }
            if (vm.canMoveFavourite) {
                BoardOption(stringResource(R.string.common_move)) {
                    close()
                    vm.mover.start(CAT_LIVE, ch.streamId)
                }
            }
            BoardOption(stringResource(R.string.live_option_hide_channel), stringResource(R.string.live_option_hide_channel_help)) {
                vm.hide(ch)
            }
        }
    }
}
