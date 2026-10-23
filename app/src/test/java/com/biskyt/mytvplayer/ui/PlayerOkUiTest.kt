package com.biskyt.mytvplayer.ui

import android.view.KeyEvent as NativeKeyEvent
import androidx.compose.runtime.remember
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.performKeyPress
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import com.biskyt.mytvplayer.core.player.OverlayState
import com.biskyt.mytvplayer.data.ChannelFilter
import com.biskyt.mytvplayer.testutil.FakeProvider
import com.biskyt.mytvplayer.testutil.Navigators
import com.biskyt.mytvplayer.testutil.TestApp
import com.biskyt.mytvplayer.testutil.addHomeTv
import com.biskyt.mytvplayer.testutil.createUiComposeRule
import com.biskyt.mytvplayer.testutil.focusedText
import com.biskyt.mytvplayer.testutil.idleFrames
import com.biskyt.mytvplayer.testutil.topRoot
import com.biskyt.mytvplayer.testutil.waitAdvancingClock
import com.biskyt.mytvplayer.ui.player.PlayerViewModel
import com.biskyt.mytvplayer.ui.theme.MyTvTheme
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * OK on the video: it pauses and brings up the controls, unless up and down change channel (then it's the way to the
 * controls, and only shows them). Its release, or holding it, must not then press the button the controls focus.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestApp::class, qualifiers = "+w960dp-h540dp-tvdpi")
class PlayerOkUiTest {
    @get:Rule val compose = createUiComposeRule()

    @get:Rule(order = Navigators.ORDER)
    val navigators = Navigators()

    private val provider = FakeProvider()
    private val app = ApplicationProvider.getApplicationContext<TestApp>()
    private var playlistId = 0L

    @Before
    fun setUp() = runBlocking {
        // A stream that never fails: a failed one is retried, or tried in the other format, which plays it again
        // whenever the error comes in (in real time), undoing a pause.
        provider.streamsTrickle = true
        playlistId = app.container.addHomeTv(provider)
    }

    @After
    fun tearDown() = provider.close()

    private lateinit var vm: PlayerViewModel
    private val playWhenReady get(): Boolean = compose.runOnIdle { vm.engine!!.player.playWhenReady }

    /** The live player with its controls hidden and focus on the video, as after watching for a while. */
    private fun playerWithoutControls() {
        val navigator = navigators.new(Screen.Player(playlistId, 101, ChannelFilter.All))
        compose.setContent { MyTvTheme { NavHost(remember { navigator }) { ScreenContent(it) } } }
        vm = ViewModelProvider(navigator.current)[PlayerViewModel::class.java]
        // Tuning (which brings the controls up) has finished: the channel is known and its stream is loading.
        compose.waitAdvancingClock { vm.channel != null && vm.engine?.player?.currentMediaItem != null }
        compose.runOnIdle { vm.hideOverlay() }
        compose.waitAdvancingClock { vm.overlay == OverlayState.NONE && compose.focusedText().isEmpty() }
    }

    /** A remote key: down ([repeat] > 0 while held) or up. The clock isn't moved on (see [idleFrames]). */
    private fun key(action: Int, code: Int = NativeKeyEvent.KEYCODE_DPAD_CENTER, repeat: Int = 0) {
        compose.topRoot().performKeyPress(KeyEvent(NativeKeyEvent(0, 0, action, code, repeat)))
        compose.idleFrames()
    }

    /** OK held for [repeats] repeats, then let go. */
    private fun holdOk(repeats: Int) {
        for (i in 1..repeats) key(NativeKeyEvent.ACTION_DOWN, repeat = i)
        key(NativeKeyEvent.ACTION_UP)
    }

    /** OK down; the controls come up (focus moves to play/pause); [repeats] more as it's held; then OK up. */
    private fun pressOkSlowly(repeats: Int = 0) {
        key(NativeKeyEvent.ACTION_DOWN)
        compose.waitAdvancingClock { vm.overlay == OverlayState.CONTROLS }
        compose.idleFrames()
        holdOk(repeats)
    }

    @Test
    fun `ok pauses and brings up the controls, and letting go doesn't play again`() {
        playerWithoutControls()
        assertTrue(playWhenReady)
        pressOkSlowly()
        assertEquals(OverlayState.CONTROLS, vm.overlay)
        assertFalse("paused", playWhenReady)
    }

    @Test
    fun `holding ok still pauses once, without the controls flickering`() {
        playerWithoutControls()
        pressOkSlowly(repeats = 5)
        assertEquals(OverlayState.CONTROLS, vm.overlay)
        assertFalse("paused", playWhenReady)
    }

    @Test
    fun `when up and down change channel, ok only brings up the controls`() {
        runBlocking { app.container.settings.update { it.copy(zapWithUpDown = true) } }
        playerWithoutControls()
        pressOkSlowly()
        assertEquals(OverlayState.CONTROLS, vm.overlay)
        assertTrue("still playing", playWhenReady)
    }

    @Test
    fun `holding ok on the video with the controls up hides them once`() {
        playerWithoutControls()
        pressOkSlowly() // controls up, focus on play/pause
        key(NativeKeyEvent.ACTION_DOWN, NativeKeyEvent.KEYCODE_DPAD_UP)
        key(NativeKeyEvent.ACTION_UP, NativeKeyEvent.KEYCODE_DPAD_UP)
        assertEquals(OverlayState.CONTROLS, vm.overlay) // focus back on the video, the controls still up
        key(NativeKeyEvent.ACTION_DOWN)
        holdOk(repeats = 4)
        assertEquals(OverlayState.NONE, vm.overlay)
    }

    @Test
    fun `a fresh ok on the focused play-pause button still works`() {
        playerWithoutControls()
        pressOkSlowly() // paused, with focus on play/pause
        key(NativeKeyEvent.ACTION_DOWN)
        key(NativeKeyEvent.ACTION_UP)
        assertTrue("playing again", playWhenReady)
    }
}
