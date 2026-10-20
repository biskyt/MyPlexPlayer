package com.biskyt.mytvplayer.ui.player

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.biskyt.mytvplayer.ui.LocalNavigator
import com.biskyt.mytvplayer.ui.Screen
import com.biskyt.mytvplayer.ui.appViewModel
import com.biskyt.mytvplayer.ui.common.Muted
import com.biskyt.mytvplayer.ui.common.PillButton
import com.biskyt.mytvplayer.ui.common.formatRuntime
import com.biskyt.mytvplayer.ui.common.formatTime

/** Catch-up: a channel as it was earlier, running on to the present. */
@Composable
fun CatchupPlayerScreen(screen: Screen.CatchupPlayer) {
    val appContext = LocalContext.current.applicationContext
    val vm = appViewModel { CatchupPlayerViewModel(it, appContext, screen) }
    val nav = LocalNavigator.current
    if (vm.goLive) {
        LaunchedEffect(Unit) { nav.replace(Screen.Player(screen.playlistId, screen.streamId, screen.filter)) }
        return
    }
    PlayerFrame(vm, controls = { buttons, root -> CatchupControls(vm, buttons, root) })
}

@Composable
private fun CatchupControls(vm: CatchupPlayerViewModel, buttons: FocusRequester, root: FocusRequester) {
    // While scrubbing: the programme and time playback will go to.
    val scrub = vm.scrubMs
    val at = scrub ?: vm.at
    val p = scrub?.let(vm::programmeAt) ?: vm.programme
    ControlsPanel {
        Column {
            val behind = ((System.currentTimeMillis() - at) / 1000).toInt()
            Muted(listOfNotNull(vm.channel?.name, "Catch-up · ${formatRuntime(behind)} behind live").joinToString(" · "))
            Text(
                p?.title ?: vm.channel?.name.orEmpty(),
                style = MaterialTheme.typography.displaySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (p != null) {
            fun fraction(t: Long) = ((t - p.startMs).toFloat() / (p.stopMs - p.startMs).coerceAtLeast(1)).coerceIn(0f, 1f)
            Timeline(formatTime(p.startMs), fraction(vm.at), formatTime(p.stopMs), scrub?.let(::fraction))
        }
        ControlsBar(vm, root) {
            PillButton("From the start", vm::fromStart, Modifier.focusRequester(buttons))
            PillButton("Watch live", vm::watchLive)
        }
        Muted("◀ ▶ skip (hold to scrub) · ▼ for buttons · Back to stop", Modifier.align(Alignment.CenterHorizontally))
    }
}
