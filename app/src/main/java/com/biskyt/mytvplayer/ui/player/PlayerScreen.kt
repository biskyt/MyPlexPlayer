package com.biskyt.mytvplayer.ui.player

import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
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
import com.biskyt.mytvplayer.ui.common.FlashLabel
import com.biskyt.mytvplayer.ui.common.Muted
import com.biskyt.mytvplayer.ui.common.PillButton
import com.biskyt.mytvplayer.ui.common.ProgressLine
import com.biskyt.mytvplayer.ui.common.TvCard
import com.biskyt.mytvplayer.ui.common.digit
import com.biskyt.mytvplayer.ui.common.formatTime
import com.biskyt.mytvplayer.ui.common.numberLabel
import com.biskyt.mytvplayer.ui.common.rememberFirstFocus
import com.biskyt.mytvplayer.ui.common.rememberMinuteClock
import com.biskyt.mytvplayer.ui.theme.LocalAccent
import com.biskyt.mytvplayer.ui.theme.Palette

@Composable
fun PlayerScreen(screen: Screen.Player) {
    val appContext = LocalContext.current.applicationContext
    val vm = appViewModel { PlayerViewModel(it, appContext, screen) }
    PlayerFrame(
        vm,
        controls = { buttons, root -> LiveControls(vm, buttons, root) },
        extras = { if (vm.numberEntry.typed.isNotEmpty()) FlashLabel(vm.numberEntry.typed, Modifier.align(Alignment.TopEnd)) }
    )
    if (vm.overlay == OverlayState.GUIDE) GuidePanel(vm)
}

/**
 * The video with everything common to live and on-demand playback: keys, the loading and error states, flash
 * messages, side sheets, and stopping when the app is left. [controls] draws the overlay shown on OK.
 */
@OptIn(UnstableApi::class)
@Composable
internal fun PlayerFrame(
    vm: PlaybackViewModel,
    controls: @Composable (buttons: FocusRequester, root: FocusRequester) -> Unit,
    extras: @Composable BoxScope.() -> Unit = {}
) {
    val nav = LocalNavigator.current
    val root = remember { FocusRequester() }
    val buttons = remember { FocusRequester() }
    var videoFocused by remember { mutableStateOf(false) }

    if (vm.exit) {
        LaunchedEffect(Unit) { nav.pop() }
        return
    }
    BackHandler { vm.back() }
    val view = LocalView.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        view.keepScreenOn = true
        // Leaving the app (Home button) stops playback instead of streaming in the background.
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> vm.onAppHidden()
                Lifecycle.Event.ON_START -> vm.onAppShown()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            view.keepScreenOn = false
        }
    }

    Box(
        Modifier.fillMaxSize().background(Color.Black)
            .focusRequester(root)
            .onFocusChanged { videoFocused = it.isFocused }
            // While a control button has focus the D-pad and OK belong to it; every other key (channel, digits, media)
            // still works.
            .onKeyEvent { e ->
                if (e.type == KeyEventType.KeyDown &&
                    (videoFocused || e.key !in buttonKeys)
                ) {
                    handleKey(e, vm, buttons)
                } else {
                    false
                }
            }
            .focusable()
    ) {
        vm.engine?.let { engine ->
            val presentation = rememberPresentationState(engine.player)
            PlayerSurface(
                player = engine.player,
                surfaceType = SURFACE_TYPE_SURFACE_VIEW,
                modifier = Modifier.align(Alignment.Center).resizeWithContentScale(vm.contentScale, presentation.videoSizeDp)
            )
        }
        if (vm.buffering && vm.error == null) {
            Text("Loading…", style = MaterialTheme.typography.titleLarge, modifier = Modifier.align(Alignment.Center))
        }
        vm.error?.let { ErrorBox(it, vm) }
        if (vm.overlay == OverlayState.CONTROLS) controls(buttons, root)
        vm.message?.let { FlashLabel(it, Modifier.align(Alignment.TopCenter)) }
        extras()
    }
    vm.sheet?.let { PlayerSheet(it, vm) }
    LaunchedEffect(vm.overlay, vm.sheet) { if (vm.sheet == null && vm.overlay != OverlayState.GUIDE) runCatching { root.requestFocus() } }
}

/** Remote keys on the video: left/right skip, up/down zap, down with controls showing moves into the buttons. */
private fun handleKey(e: KeyEvent, vm: PlaybackViewModel, buttons: FocusRequester): Boolean {
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
    val digit = e.key.digit() ?: return false
    return vm.onKey(RemoteKey.DIGIT, digit)
}

@Composable
private fun LiveControls(vm: PlayerViewModel, buttons: FocusRequester, root: FocusRequester) {
    val ch = vm.channel ?: return
    LaunchedEffect(ch.streamId) { vm.nowNext.request(ch.streamId) }
    val nn = vm.nowNext.entries[ch.streamId]
    val accent = LocalAccent.current
    val now by rememberMinuteClock()
    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier.fillMaxWidth().align(Alignment.BottomCenter)
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xF2080A0D), Color(0xF5080A0D))))
                .padding(start = 56.dp, end = 56.dp, top = 80.dp, bottom = 36.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(22.dp)) {
                    ChannelLogo(ch.logoUrl, ch.name, 110.dp, 66.dp)
                    Column(Modifier.weight(1f)) {
                        Muted(listOf(ch.numberLabel, ch.name).filter { it.isNotEmpty() }.joinToString(" · "))
                        Text(
                            nn?.now?.title ?: ch.name,
                            style = MaterialTheme.typography.displaySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    nn?.next?.let { next ->
                        Column(Modifier.width(320.dp), horizontalAlignment = Alignment.End) {
                            Text(
                                "UP NEXT · ${formatTime(next.startMs)}",
                                style = MaterialTheme.typography.labelMedium,
                                color = accent.color
                            )
                            Text(next.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                nn?.now?.let { current ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        Muted(formatTime(current.startMs))
                        ProgressLine(nn.progress(now), Modifier.weight(1f), height = 6.dp)
                        Muted(formatTime(current.stopMs))
                    }
                }
                ControlsBar(vm, root) {
                    PillButton("Channel guide", vm::openGuide, Modifier.focusRequester(buttons))
                }
                Muted("◀ ▶ skip · ▲ ▼ change channel · ▼ for buttons · OK pause", Modifier.align(Alignment.CenterHorizontally))
            }
        }
    }
}

/** The row of buttons under the video: [leading] on the left, play controls in the middle, tracks and more on the right. */
@Composable
internal fun ControlsBar(vm: PlaybackViewModel, root: FocusRequester, leading: @Composable RowScope.() -> Unit) {
    Row(
        Modifier.onPreviewKeyEvent { e ->
            // Up from the buttons returns to the video, where left/right skip again.
            if (e.type == KeyEventType.KeyDown && e.key == Key.DirectionUp) runCatching { root.requestFocus() }.isSuccess else false
        },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        leading()
        Box(Modifier.weight(1f))
        RoundButton(AppIcons.Rewind, "Skip back") { vm.onKey(RemoteKey.LEFT) }
        RoundButton(if (vm.isPlaying) AppIcons.Pause else AppIcons.Play, if (vm.isPlaying) "Pause" else "Play", primary = true) {
            vm.togglePlayPause()
        }
        RoundButton(AppIcons.Forward, "Skip forward") { vm.onKey(RemoteKey.RIGHT) }
        RoundButton(AppIcons.Stop, "Stop") { vm.stop() }
        Box(Modifier.weight(1f))
        PillButton("Audio", { vm.sheet = PlaybackViewModel.Sheet.AUDIO })
        PillButton("Subtitles", { vm.sheet = PlaybackViewModel.Sheet.SUBTITLES })
        PillButton("More", { vm.sheet = PlaybackViewModel.Sheet.MORE })
    }
}

@Composable
private fun RoundButton(icon: ImageVector, label: String, primary: Boolean = false, onClick: () -> Unit) {
    val accent = LocalAccent.current
    TvCard(
        onClick = onClick,
        shape = CircleShape,
        color = if (primary) accent.color else Palette.surfaceHigh,
        focusedColor = if (primary) accent.color else Palette.surfaceHigh
    ) {
        Icon(
            icon,
            label,
            tint = if (primary) accent.onAccent else Palette.text,
            modifier = Modifier.padding(if (primary) 18.dp else 14.dp).size(24.dp)
        )
    }
}

@Composable
private fun ErrorBox(text: String, vm: PlaybackViewModel) {
    val retry = rememberFirstFocus(text)
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.width(560.dp)
        ) {
            Text(text, style = MaterialTheme.typography.titleLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PillButton("Try again", vm::retry, retry, primary = true)
                PillButton("Back", vm::stop)
            }
        }
    }
}

/** Keys a focused control button handles itself. */
private val buttonKeys =
    setOf(Key.DirectionLeft, Key.DirectionRight, Key.DirectionUp, Key.DirectionDown, Key.DirectionCenter, Key.Enter, Key.NumPadEnter)
