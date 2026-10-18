package com.biskyt.mytvplayer.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.biskyt.mytvplayer.ui.Screen
import com.biskyt.mytvplayer.ui.appViewModel
import com.biskyt.mytvplayer.ui.common.Muted
import com.biskyt.mytvplayer.ui.common.PillButton
import com.biskyt.mytvplayer.ui.common.ProgressLine
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
    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier.fillMaxWidth().align(Alignment.BottomCenter)
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xF2080A0D), Color(0xF5080A0D))))
                .padding(start = 56.dp, end = 56.dp, top = 80.dp, bottom = 36.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Column {
                    if (vm.subtitle.isNotEmpty()) Muted(vm.subtitle)
                    Text(vm.title, style = MaterialTheme.typography.displaySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Muted(formatDuration(vm.positionMs))
                    val progress = if (vm.durationMs > 0) vm.positionMs.toFloat() / vm.durationMs else 0f
                    ProgressLine(progress, Modifier.weight(1f), height = 6.dp)
                    Muted(if (vm.durationMs > 0) formatDuration(vm.durationMs) else "")
                }
                ControlsBar(vm, root) {
                    PillButton("From the start", vm::startOver, Modifier.focusRequester(buttons))
                    if (vm.nextEpisode != null) PillButton("Next episode", vm::playNext)
                }
                Muted("◀ ▶ skip · ▼ for buttons · Back to stop", Modifier.align(Alignment.CenterHorizontally))
            }
        }
    }
}
