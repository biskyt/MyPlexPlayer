package com.biskyt.myplexplayer.ui

import android.view.KeyEvent as NativeKeyEvent
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.runtime.remember
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import com.biskyt.myplexplayer.core.player.OverlayState
import com.biskyt.myplexplayer.data.Settings
import com.biskyt.myplexplayer.testutil.FakePlex
import com.biskyt.myplexplayer.testutil.Navigators
import com.biskyt.myplexplayer.testutil.TestApp
import com.biskyt.myplexplayer.testutil.addSyncedHomePlex
import com.biskyt.myplexplayer.testutil.createUiComposeRule
import com.biskyt.myplexplayer.testutil.sendKey
import com.biskyt.myplexplayer.testutil.settle
import com.biskyt.myplexplayer.testutil.waitAdvancingClock
import com.biskyt.myplexplayer.testutil.waitForText
import com.biskyt.myplexplayer.ui.player.VodPlayerViewModel
import com.biskyt.myplexplayer.ui.theme.MyPlexTheme
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Plex's intro and credits markers: skipped by themselves after a countdown, or by OK or a tap on the pill. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestApp::class, qualifiers = "+w960dp-h540dp-tvdpi")
class SkipMarkersUiTest {
    @get:Rule val compose = createUiComposeRule()

    @get:Rule(order = Navigators.ORDER)
    val navigators = Navigators()

    private val plex = FakePlex()
    private val app = ApplicationProvider.getApplicationContext<TestApp>()
    private lateinit var vm: VodPlayerViewModel

    @After
    fun tearDown() = plex.close()

    /**
     * Test Show's first episode (211) with one marker, from [startMs] to [endMs], intros and credits skipped by
     * themselves after [autoSec] (null: the defaults, 3 s for intros, off for credits); the controls gone.
     */
    private fun playPilotWith(type: String, endMs: Long, final: Boolean = false, autoSec: Int? = null, startMs: Long = 0) {
        plex.episodes = listOf(
            FakePlex.episode("21", "Test Show", "211", 1, 1, "Pilot", markers = listOf(FakePlex.Marker(type, startMs, endMs, final))),
            FakePlex.episode("21", "Test Show", "212", 1, 2, "Second")
        )
        val source = runBlocking {
            if (autoSec != null) app.container.settings.update { it.copy(autoSkipIntroSec = autoSec, autoSkipCreditsSec = autoSec) }
            app.container.addSyncedHomePlex(plex)
        }
        val navigator = navigators.new(Screen.VodPlayer(source, 211, seriesId = 21, fromStart = true))
        compose.setContent {
            backDispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
            MyPlexTheme { NavHost(remember { navigator }) { ScreenContent(it) } }
        }
        vm = ViewModelProvider(navigator.current)[VodPlayerViewModel::class.java]
        compose.waitAdvancingClock { vm.engine?.player?.currentMediaItem != null }
        compose.runOnIdle { vm.hideOverlay() }
    }

    private var backDispatcher: OnBackPressedDispatcher? = null

    private fun ok() {
        compose.sendKey(NativeKeyEvent.KEYCODE_DPAD_CENTER, NativeKeyEvent.ACTION_DOWN)
        compose.sendKey(NativeKeyEvent.KEYCODE_DPAD_CENTER, NativeKeyEvent.ACTION_UP)
    }

    private val position get() = compose.runOnIdle { vm.engine!!.player.currentPosition }

    private fun seekTo(ms: Long) = compose.runOnIdle { vm.engine!!.player.seekTo(ms) }

    @Test
    fun `OK skips the intro at once, without bringing up the controls`() {
        playPilotWith("intro", endMs = 30_000, autoSec = Settings.AUTO_SKIP_OFF)
        compose.waitForText("Skip intro")
        ok()
        assertTrue("past the intro", position >= 30_000)
        assertEquals(OverlayState.NONE, compose.runOnIdle { vm.overlay })
        compose.waitAdvancingClock(stepMs = 100) { vm.skip == null }
        compose.onAllNodesWithText("Skip intro").assertCountEquals(0)
    }

    @Test
    fun `the pill counts down, and the intro is skipped by itself 2 seconds in`() {
        playPilotWith("intro", endMs = 30_000)
        compose.waitForText("Skip intro (2)")
        seekTo(2_000)
        compose.waitAdvancingClock(stepMs = 100) { vm.engine!!.player.currentPosition >= 30_000 }
    }

    @Test
    fun `Back cancels the automatic skip, and leaves the pill to press`() {
        playPilotWith("intro", endMs = 30_000)
        compose.waitForText("Skip intro (2)")
        compose.runOnUiThread { backDispatcher!!.onBackPressed() }
        compose.waitForText("Skip intro")
        seekTo(2_000)
        compose.settle()
        assertTrue("not skipped", position < 30_000)
        assertFalse("still playing", compose.runOnIdle { vm.exit })
        ok()
        assertTrue("skipped when pressed", position >= 30_000)
    }

    @Test
    fun `sought into the intro, it waits to be pressed`() {
        playPilotWith("intro", startMs = 60_000, endMs = 90_000)
        seekTo(70_000)
        compose.waitForText("Skip intro")
        compose.settle()
        assertTrue("not skipped", position < 90_000)
    }

    @Test
    fun `credits wait to be pressed, to start with`() {
        playPilotWith("credits", endMs = 600_000, final = true)
        compose.waitForText("Skip credits")
        seekTo(1_000)
        compose.settle() // a tick at 1 s, then playing on past 2 s
        seekTo(3_000)
        compose.settle()
        assertEquals("still the pilot", "Pilot", compose.runOnIdle { vm.subtitle }.substringAfterLast(" · "))
        assertTrue("still in the credits", position in 3_000 until 600_000)
        compose.onAllNodesWithText("Skip credits", useUnmergedTree = true).assertCountEquals(1)
    }

    @Test
    fun `skipping an episode's last credits plays the next episode`() {
        playPilotWith("credits", endMs = 600_000, final = true, autoSec = 3)
        compose.waitForText("Skip credits (2)")
        ok()
        compose.waitAdvancingClock(stepMs = 100) { vm.subtitle.contains("Second") }
    }

    @Test
    fun `with the controls up, OK is theirs`() {
        playPilotWith("intro", endMs = 30_000, autoSec = Settings.AUTO_SKIP_OFF)
        compose.waitForText("Skip intro")
        compose.runOnIdle { vm.showControls() }
        ok()
        assertTrue("not skipped by OK", position < 30_000)
        compose.waitForText("Skip intro")
    }
}
