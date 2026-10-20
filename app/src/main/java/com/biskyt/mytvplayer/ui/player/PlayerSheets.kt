package com.biskyt.mytvplayer.ui.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import androidx.media3.common.C
import androidx.media3.common.Tracks
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.biskyt.mytvplayer.ui.LocalNavigator
import com.biskyt.mytvplayer.ui.common.ChannelLogo
import com.biskyt.mytvplayer.ui.common.Muted
import com.biskyt.mytvplayer.ui.common.ScheduleList
import com.biskyt.mytvplayer.ui.common.SidePanel
import com.biskyt.mytvplayer.ui.common.TvCard
import com.biskyt.mytvplayer.ui.common.cycle
import com.biskyt.mytvplayer.ui.common.perform
import com.biskyt.mytvplayer.ui.common.rememberFirstFocus
import com.biskyt.mytvplayer.ui.common.rememberSchedule
import com.biskyt.mytvplayer.ui.theme.LocalAccent
import com.biskyt.mytvplayer.ui.theme.Palette
import java.util.Locale

/** The channel guide beside the video (agreed side-overlay design); ◀ ▶ previews other channels' guides. */
@Composable
fun GuidePanel(vm: PlayerViewModel) {
    val ch = vm.guideChannel ?: return
    SidePanel(onDismiss = vm::closeGuide, dim = false) {
        Column(
            Modifier.mediaKeys(vm).onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (e.key) {
                    Key.DirectionLeft -> {
                        vm.previewGuide(-1)
                        true
                    }
                    Key.DirectionRight -> {
                        vm.previewGuide(1)
                        true
                    }
                    Key.Guide -> {
                        vm.closeGuide()
                        true
                    }
                    else -> false
                }
            },
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ChannelLogo(ch.logoUrl, ch.name, 64.dp, 40.dp)
                Column(Modifier.padding(start = 12.dp)) {
                    Text(ch.name, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                    Muted("◀ ▶ other channels · OK to watch")
                }
            }
            ScheduleList(rememberSchedule(ch.playlistId, ch.streamId), onSelect = { vm.watchGuideChannel() })
        }
    }
}

@Composable
fun PlayerSheet(sheet: PlaybackViewModel.Sheet, vm: PlaybackViewModel) {
    val engine = vm.engine ?: return
    val nav = LocalNavigator.current
    val close = { vm.sheet = null }
    SidePanel(onDismiss = close, dim = false) {
        val first = rememberFirstFocus()
        LazyColumn(Modifier.mediaKeys(vm), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when (sheet) {
                PlaybackViewModel.Sheet.AUDIO -> {
                    item { Header("Audio", vm.audioLabel) }
                    val options = trackOptions(vm.tracks, C.TRACK_TYPE_AUDIO)
                    items(options) { o ->
                        Choice(o.label, o.selected, if (o == options.first()) first else Modifier) {
                            close()
                            vm.chooseTrack(C.TRACK_TYPE_AUDIO, "Audio", o.group, o.index, o.label)
                        }
                    }
                    if (options.isEmpty()) item { Muted("No audio tracks") }
                }
                PlaybackViewModel.Sheet.SUBTITLES -> {
                    item { Header("Subtitles", null) }
                    val options = trackOptions(vm.tracks, C.TRACK_TYPE_TEXT)
                    item {
                        Choice("Off", options.none { it.selected }, first) {
                            engine.selectTrack(C.TRACK_TYPE_TEXT, null, 0)
                            close()
                            vm.flash("Subtitles off")
                        }
                    }
                    items(options) { o ->
                        Choice(o.label, o.selected) {
                            close()
                            vm.chooseTrack(C.TRACK_TYPE_TEXT, "Subtitles", o.group, o.index, o.label)
                        }
                    }
                }
                PlaybackViewModel.Sheet.MORE -> {
                    item { Header("More", null) }
                    val actions = vm.menuActions()
                    itemsIndexed(actions) { i, a ->
                        // A screen pushed over the player stops it while it's covered.
                        Choice(a.label, false, if (i == 0) first else Modifier) { a.perform(nav, close, vm::beforeLeaving) }
                    }
                    item {
                        Choice("Picture: ${vm.picture.label}", false, if (actions.isEmpty()) first else Modifier) {
                            vm.picture = vm.picture.next
                            vm.flash("Picture: ${vm.picture.label}")
                        }
                    }
                    item {
                        val next = listOf(0, 15, 30, 60, 90, 120).cycle(vm.sleepMinutes)
                        Choice("Sleep timer: ${if (vm.sleepMinutes == 0) "Off" else "${vm.sleepMinutes} min"}", false) {
                            vm.setSleepTimer(next)
                        }
                    }
                    item { Muted("Audio output: ${vm.audioLabel}", maxLines = 2) }
                    item { Muted(videoInfo(vm.tracks), maxLines = 2) }
                }
            }
        }
    }
}

@Composable
private fun Header(title: String, subtitle: String?) {
    Column(Modifier.padding(bottom = 8.dp)) {
        Text(title, style = MaterialTheme.typography.headlineSmall)
        if (!subtitle.isNullOrEmpty()) Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = LocalAccent.current.soft)
    }
}

@Composable
private fun Choice(label: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    TvCard(onClick = onClick, selected = selected, modifier = modifier.fillMaxWidth(), color = Palette.surface) {
        Text(label, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp))
    }
}

private data class TrackOption(val label: String, val group: Tracks.Group, val index: Int, val selected: Boolean)

private fun trackOptions(tracks: Tracks, type: Int): List<TrackOption> = tracks.groups.filter { it.type == type }.flatMap { g ->
    (0 until g.length).filter { g.isTrackSupported(it) }.map { i ->
        val f = g.getTrackFormat(i)
        val lang = f.language?.let { Locale.forLanguageTag(it).displayLanguage.ifEmpty { it } }
        val parts = listOfNotNull(
            f.label ?: lang ?: "Track ${i + 1}",
            if ((f.selectionFlags and C.SELECTION_FLAG_FORCED) != 0) "forced" else null,
            f.channelCount.takeIf { type == C.TRACK_TYPE_AUDIO && it > 2 }?.let { "${it}ch" }
        )
        TrackOption(parts.joinToString(" · "), g, i, g.isTrackSelected(i))
    }
}

private fun videoInfo(tracks: Tracks): String {
    val f =
        tracks.groups.firstOrNull {
            it.type == C.TRACK_TYPE_VIDEO && it.isSelected
        }?.let { g -> (0 until g.length).firstOrNull { g.isTrackSelected(it) }?.let(g::getTrackFormat) }
            ?: return "Video: unknown"
    return "Video: ${f.width}×${f.height} · ${f.sampleMimeType?.substringAfter('/') ?: "?"}"
}
