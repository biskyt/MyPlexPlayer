package com.biskyt.mytvplayer.ui.player

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.biskyt.mytvplayer.ui.Screen
import com.biskyt.mytvplayer.ui.appViewModel
import com.biskyt.mytvplayer.ui.common.Muted
import com.biskyt.mytvplayer.ui.common.PillButton
import com.biskyt.mytvplayer.ui.common.formatDuration

/** Movies and episodes. */
@Composable
fun VodPlayerScreen(screen: Screen.VodPlayer) {
    val appContext = LocalContext.current.applicationContext
    val vm = appViewModel { VodPlayerViewModel(it, appContext, screen) }
    PlayerFrame(vm, controls = { buttons, root -> VodControls(vm, buttons, root) })
}

@Composable
private fun VodControls(vm: VodPlayerViewModel, buttons: FocusRequester, root: FocusRequester) {
    ControlsPanel {
        Column {
            if (vm.subtitle.isNotEmpty()) Muted(vm.subtitle)
            Text(vm.title, style = MaterialTheme.typography.displaySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Position(vm)
        ControlsBar(vm, root) {
            PillButton("From the start", vm::startOver, Modifier.focusRequester(buttons))
            if (vm.nextEpisode != null) PillButton("Next episode", vm::playNext)
        }
        Muted("◀ ▶ skip · ▼ for buttons · Back to stop", Modifier.align(Alignment.CenterHorizontally))
    }
}

/** Its own composable, so the once-a-second time update redraws only this row. */
@Composable
private fun Position(vm: VodPlayerViewModel) {
    val pos = vm.positionSec
    val dur = vm.durationSec
    Timeline(formatDuration(pos * 1000), if (dur > 0) pos.toFloat() / dur else 0f, if (dur > 0) formatDuration(dur * 1000) else "")
}
