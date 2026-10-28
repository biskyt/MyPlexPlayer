package com.biskyt.myplexplayer.ui.player

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.biskyt.myplexplayer.R
import com.biskyt.myplexplayer.core.player.OverlayState
import com.biskyt.myplexplayer.core.plex.PlexMarker
import com.biskyt.myplexplayer.core.plex.SkipPill
import com.biskyt.myplexplayer.ui.Screen
import com.biskyt.myplexplayer.ui.appViewModel
import com.biskyt.myplexplayer.ui.common.CastPanel
import com.biskyt.myplexplayer.ui.common.Muted
import com.biskyt.myplexplayer.ui.common.PillButton
import com.biskyt.myplexplayer.ui.common.formatDuration
import com.biskyt.myplexplayer.ui.common.isSelect

/** Movies and episodes. */
@Composable
fun VodPlayerScreen(screen: Screen.VodPlayer) {
    val appContext = LocalContext.current.applicationContext
    val vm = appViewModel { VodPlayerViewModel(it, appContext, screen) }
    Box(Modifier.skipKeys(vm)) {
        PlayerFrame(
            vm,
            controls = { buttons, root -> VodControls(vm, buttons, root) },
            extras = { vm.skip?.let { SkipPillButton(it, vm) } }
        )
        // Back that skipKeys doesn't see (a touch screen's, or the remote's with focus outside the player, as a panel
        // closes): while the pill counts down, it cancels the skip.
        BackHandler(enabled = vm.backCancelsSkip, onBack = vm::cancelAutoSkip)
    }
    // The cast, from More: over the picture, which plays on.
    vm.cast?.let { (title, cast) -> CastPanel(title, cast) { vm.cast = null } }
}

/**
 * Bottom right, or top right (below the clock) over the controls (where OK is theirs). Never focused: OK reaches it through [skipKeys],
 * so focus doesn't fall away when it goes; a tap presses it.
 */
@Composable
private fun BoxScope.SkipPillButton(pill: SkipPill, vm: VodPlayerViewModel) {
    val name = stringResource(if (pill.marker.type == PlexMarker.INTRO) R.string.player_skip_intro else R.string.player_skip_credits)
    val text = pill.countdownSec?.let { stringResource(R.string.player_skip_countdown, name, it) } ?: name
    // Over the controls, under the clock in their top corner (its line grows with the font size).
    val clockStyle = MaterialTheme.typography.headlineSmall
    val clockLine = with(LocalDensity.current) {
        (if (clockStyle.lineHeight.isSp) clockStyle.lineHeight else clockStyle.fontSize * 1.3f).toDp()
    }
    val place = if (vm.overlay == OverlayState.NONE) {
        Modifier.align(Alignment.BottomEnd).padding(48.dp)
    } else {
        Modifier.align(Alignment.TopEnd).padding(top = 27.dp + 12.dp + clockLine + 16.dp, end = 56.dp)
    }
    PillButton(text, vm::skipNow, place.focusProperties { canFocus = false }, primary = true)
}

/**
 * With the skip button showing on its own, OK skips; while it counts down, the remote's Back cancels the skip rather
 * than playback. Each acts as its key goes down, and takes the rest of that press (repeats, release), so it neither
 * brings up the controls nor, held, does anything more; OK and Back are each followed on their own.
 */
@Composable
private fun Modifier.skipKeys(vm: VodPlayerViewModel): Modifier {
    val taken = remember { mutableSetOf<Key>() }
    return onPreviewKeyEvent { e ->
        val ok = e.key.isSelect()
        if (!ok && e.key != Key.Back) return@onPreviewKeyEvent false
        if (e.type == KeyEventType.KeyDown && e.nativeKeyEvent.repeatCount == 0 && (if (ok) vm.skipsOnOk else vm.backCancelsSkip)) {
            taken += e.key
            vm.wake()
            if (ok) vm.skipNow() else vm.cancelAutoSkip()
        }
        if (e.type == KeyEventType.KeyUp) taken.remove(e.key) else e.key in taken
    }
}

@Composable
private fun VodControls(vm: VodPlayerViewModel, buttons: FocusRequester, root: FocusRequester) {
    ControlsPanel {
        Column {
            if (vm.subtitle.isNotEmpty()) Muted(vm.subtitle)
            Text(vm.title, style = MaterialTheme.typography.displaySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Position(vm)
        ControlsBar(vm, buttons, root)
    }
}

/** Its own composable, so the once-a-second time update redraws only this row. */
@Composable
private fun Position(vm: VodPlayerViewModel) {
    val dur = vm.durationSec
    val scrub = vm.scrubMs
    // While scrubbing, the time shown is where playback will go.
    val pos = scrub?.div(1000) ?: vm.positionSec
    // Known once playback has started.
    val totalMs = (dur * 1000).takeIf { it > 0 }
    fun fraction(sec: Long) = totalMs?.let { sec * 1000f / it } ?: 0f
    // The bar the whole width; under it, how far in of how long, and how long is left.
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        SeekBar(
            fraction(vm.positionSec),
            scrub?.let { fraction(it / 1000) },
            handle = LocalBarSelected.current,
            onSeek = totalMs?.let { t -> { vm.scrubTo(0L..t, it) } },
            modifier = Modifier.fillMaxWidth()
        )
        Row {
            Muted(formatDuration(pos * 1000) + totalMs?.let { " / " + formatDuration(it) }.orEmpty())
            Spacer(Modifier.weight(1f))
            totalMs?.let { Muted(stringResource(R.string.common_time_left, formatDuration((it - pos * 1000).coerceAtLeast(0)))) }
        }
    }
}
