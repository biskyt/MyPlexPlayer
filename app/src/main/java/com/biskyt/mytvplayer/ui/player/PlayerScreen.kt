package com.biskyt.mytvplayer.ui.player

import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.compose.PlayerSurface
import androidx.media3.ui.compose.SURFACE_TYPE_SURFACE_VIEW
import androidx.media3.ui.compose.modifiers.resizeWithContentScale
import androidx.media3.ui.compose.state.rememberPresentationState
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.biskyt.mytvplayer.core.player.OverlayState
import com.biskyt.mytvplayer.core.player.RemoteKey
import com.biskyt.mytvplayer.ui.LocalNavigator
import com.biskyt.mytvplayer.ui.Screen
import com.biskyt.mytvplayer.ui.appViewModel
import com.biskyt.mytvplayer.ui.common.AppIcons
import com.biskyt.mytvplayer.ui.common.ChannelLogo
import com.biskyt.mytvplayer.ui.common.Muted
import com.biskyt.mytvplayer.ui.common.PillButton
import com.biskyt.mytvplayer.ui.common.ProgressLine
import com.biskyt.mytvplayer.ui.common.TvCard
import com.biskyt.mytvplayer.ui.common.formatTime
import com.biskyt.mytvplayer.ui.theme.LocalAccent
import com.biskyt.mytvplayer.ui.theme.Palette

@OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(screen: Screen.Player) {
    val appContext = LocalContext.current.applicationContext
    val vm = appViewModel { PlayerViewModel(it, appContext, screen) }
    val nav = LocalNavigator.current
    val root = remember { FocusRequester() }
    val buttons = remember { FocusRequester() }

    if (vm.exit) {
        LaunchedEffect(Unit) { nav.pop() }
        return
    }
    BackHandler { vm.back() }
    val view = LocalView.current
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }

    Box(
        Modifier.fillMaxSize().background(Color.Black)
            .focusRequester(root)
            .onKeyEvent { e -> if (e.type == KeyEventType.KeyDown) handleKey(e, vm, buttons) else false }
            .focusable(),
    ) {
        vm.engine?.let { engine ->
            val presentation = rememberPresentationState(engine.player)
            PlayerSurface(
                player = engine.player,
                surfaceType = SURFACE_TYPE_SURFACE_VIEW,
                modifier = Modifier.align(Alignment.Center).resizeWithContentScale(vm.contentScale, presentation.videoSizeDp),
            )
        }
        if (vm.buffering && vm.error == null) {
            Text("Loading…", style = MaterialTheme.typography.titleLarge, modifier = Modifier.align(Alignment.Center))
        }
        vm.error?.let { ErrorBox(it, vm) }
        if (vm.overlay == OverlayState.CONTROLS) ControlsOverlay(vm, buttons, root)
        vm.message?.let { Flash(it, Modifier.align(Alignment.TopCenter)) }
        if (vm.typedNumber.isNotEmpty()) Flash(vm.typedNumber, Modifier.align(Alignment.TopEnd))
    }
    if (vm.overlay == OverlayState.GUIDE) GuidePanel(vm)
    vm.sheet?.let { PlayerSheet(it, vm) }
    LaunchedEffect(vm.overlay, vm.sheet) { if (vm.sheet == null && vm.overlay != OverlayState.GUIDE) runCatching { root.requestFocus() } }
}

/** Remote keys at the top level; left/right skip, up/down zap, down with controls showing moves into the buttons. */
private fun handleKey(e: KeyEvent, vm: PlayerViewModel, buttons: FocusRequester): Boolean {
    if (vm.overlay == OverlayState.CONTROLS && e.key == Key.DirectionDown) {
        return runCatching { buttons.requestFocus() }.isSuccess
    }
    val key = when (e.key) {
        Key.DirectionLeft -> RemoteKey.LEFT
        Key.DirectionRight -> RemoteKey.RIGHT
        Key.DirectionUp -> RemoteKey.UP
        Key.DirectionDown -> RemoteKey.DOWN
        Key.DirectionCenter, Key.Enter, Key.NumPadEnter, Key.Info -> RemoteKey.OK
        Key.MediaPlayPause -> RemoteKey.PLAY_PAUSE
        Key.MediaPlay -> RemoteKey.PLAY
        Key.MediaPause -> RemoteKey.PAUSE
        Key.MediaStop -> RemoteKey.STOP
        Key.MediaRewind -> RemoteKey.REWIND
        Key.MediaFastForward -> RemoteKey.FAST_FORWARD
        Key.Guide -> RemoteKey.GUIDE
        Key.Menu -> RemoteKey.MENU
        Key.ChannelUp, Key.PageUp -> RemoteKey.CHANNEL_UP
        Key.ChannelDown, Key.PageDown -> RemoteKey.CHANNEL_DOWN
        else -> null
    }
    if (key != null) return vm.onKey(key)
    val digit = digitOf(e.key) ?: return false
    return vm.onKey(RemoteKey.DIGIT, digit)
}

private fun digitOf(k: Key): Int? = listOf(Key.Zero, Key.One, Key.Two, Key.Three, Key.Four, Key.Five, Key.Six, Key.Seven, Key.Eight, Key.Nine)
    .indexOf(k).takeIf { it >= 0 }
    ?: listOf(Key.NumPad0, Key.NumPad1, Key.NumPad2, Key.NumPad3, Key.NumPad4, Key.NumPad5, Key.NumPad6, Key.NumPad7, Key.NumPad8, Key.NumPad9)
        .indexOf(k).takeIf { it >= 0 }

@Composable
private fun ControlsOverlay(vm: PlayerViewModel, buttons: FocusRequester, root: FocusRequester) {
    val ch = vm.channel ?: return
    LaunchedEffect(ch.streamId) { vm.nowNext.request(ch.streamId) }
    val nn = vm.nowNext.entries[ch.streamId]
    val accent = LocalAccent.current
    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier.fillMaxWidth().align(Alignment.BottomCenter)
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xF2080A0D), Color(0xF5080A0D))))
                .padding(start = 56.dp, end = 56.dp, top = 80.dp, bottom = 36.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(22.dp)) {
                    ChannelLogo(ch.logoUrl, ch.name, 110.dp, 66.dp)
                    Column(Modifier.weight(1f)) {
                        Muted("${if (ch.number > 0) "${ch.number} · " else ""}${ch.name}")
                        Text(nn?.now?.title ?: ch.name, style = MaterialTheme.typography.displaySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    nn?.next?.let { next ->
                        Column(Modifier.width(320.dp), horizontalAlignment = Alignment.End) {
                            Text("UP NEXT · ${formatTime(next.startMs)}", style = MaterialTheme.typography.labelMedium, color = accent.color)
                            Text(next.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                nn?.now?.let { now ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        Muted(formatTime(now.startMs))
                        ProgressLine(nn.progress(System.currentTimeMillis()), Modifier.weight(1f), height = 6.dp)
                        Muted(formatTime(now.stopMs))
                    }
                }
                Row(
                    Modifier.onPreviewKeyEvent { e ->
                        // Up from the buttons returns to the video, where left/right skip again.
                        if (e.type == KeyEventType.KeyDown && e.key == Key.DirectionUp) runCatching { root.requestFocus() }.isSuccess else false
                    },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    PillButton("Channel guide", vm::openGuide, Modifier.focusRequester(buttons))
                    Box(Modifier.weight(1f))
                    RoundButton(AppIcons.Rewind, "Skip back") { vm.onKey(RemoteKey.LEFT) }
                    RoundButton(if (vm.isPlaying) AppIcons.Pause else AppIcons.Play, if (vm.isPlaying) "Pause" else "Play", primary = true) { vm.togglePlayPause() }
                    RoundButton(AppIcons.Forward, "Skip forward") { vm.onKey(RemoteKey.RIGHT) }
                    RoundButton(AppIcons.Stop, "Stop") { vm.stop() }
                    Box(Modifier.weight(1f))
                    PillButton("Audio", { vm.sheet = PlayerViewModel.Sheet.AUDIO })
                    PillButton("Subtitles", { vm.sheet = PlayerViewModel.Sheet.SUBTITLES })
                    PillButton("More", { vm.sheet = PlayerViewModel.Sheet.MORE })
                }
                Muted("◀ ▶ skip · ▲ ▼ change channel · ▼ for buttons · OK pause", Modifier.align(Alignment.CenterHorizontally))
            }
        }
    }
}

@Composable
private fun RoundButton(icon: ImageVector, label: String, primary: Boolean = false, onClick: () -> Unit) {
    val accent = LocalAccent.current
    TvCard(onClick = onClick, shape = CircleShape, color = if (primary) accent.color else Palette.surfaceHigh, focusedColor = if (primary) accent.color else Palette.surfaceHigh) {
        Icon(icon, label, tint = if (primary) accent.onAccent else Palette.text, modifier = Modifier.padding(if (primary) 18.dp else 14.dp).size(24.dp))
    }
}

@Composable
private fun Flash(text: String, modifier: Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.headlineMedium,
        modifier = modifier.padding(40.dp).clip(RoundedCornerShape(14.dp)).background(Color(0xCC080A0D)).padding(horizontal = 24.dp, vertical = 12.dp),
    )
}

@Composable
private fun ErrorBox(text: String, vm: PlayerViewModel) {
    val retry = remember { FocusRequester() }
    LaunchedEffect(text) { runCatching { retry.requestFocus() } }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.width(560.dp)) {
            Text(text, style = MaterialTheme.typography.titleLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PillButton("Try again", vm::retry, Modifier.focusRequester(retry), primary = true)
                PillButton("Back", vm::stop)
            }
        }
    }
}
