package com.biskyt.myplexplayer.ui.phone

import android.content.pm.PackageManager
import androidx.compose.runtime.remember
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import com.biskyt.myplexplayer.data.ChannelFilter
import com.biskyt.myplexplayer.testutil.FakePlex
import com.biskyt.myplexplayer.testutil.Navigators
import com.biskyt.myplexplayer.testutil.TestApp
import com.biskyt.myplexplayer.testutil.addSyncedHomePlex
import com.biskyt.myplexplayer.testutil.createUiComposeRule
import com.biskyt.myplexplayer.testutil.plexChannel
import com.biskyt.myplexplayer.testutil.waitAdvancingClock
import com.biskyt.myplexplayer.testutil.waitForText
import com.biskyt.myplexplayer.ui.NavHost
import com.biskyt.myplexplayer.ui.Screen
import com.biskyt.myplexplayer.ui.ScreenContent
import com.biskyt.myplexplayer.ui.player.PlaybackViewModel
import com.biskyt.myplexplayer.ui.player.PlayerViewModel
import com.biskyt.myplexplayer.ui.player.VodPlayerViewModel
import com.biskyt.myplexplayer.ui.theme.MyPlexTheme
import com.biskyt.myplexplayer.ui.theme.autoFindsTv
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** The player's touch controls on a phone held sideways (the owner, 2026-11-25): screenshots to look at. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestApp::class, qualifiers = "w844dp-h390dp-xxhdpi")
class PhonePlayerControlsTest {
    @get:Rule val compose = createUiComposeRule()

    @get:Rule(order = Navigators.ORDER)
    val navigators = Navigators()

    private val plex = FakePlex().apply { liveTrickles = true } // never fails, so no retry plays it again
    private val app = ApplicationProvider.getApplicationContext<TestApp>()
    private var playlistId = 0L

    @Before
    fun setUp() = runBlocking {
        shadowOf(app.packageManager).setSystemFeature(PackageManager.FEATURE_TOUCHSCREEN, true)
        autoFindsTv = { false } // the phone layout (TestApp sets the TV's)
        playlistId = app.container.addSyncedHomePlex(plex)
    }

    @After
    fun tearDown() = plex.close()

    private fun <T : PlaybackViewModel> play(screen: Screen, type: Class<T>): T {
        val navigator = navigators.new(screen)
        compose.setContent { MyPlexTheme { NavHost(remember { navigator }) { ScreenContent(it) } } }
        val vm = ViewModelProvider(navigator.current)[type]
        compose.waitAdvancingClock { vm.engine?.player?.currentMediaItem != null }
        return vm
    }

    @Test
    fun `an episode's controls`() {
        // Test Show's first episode: its second follows it.
        play(Screen.VodPlayer(playlistId, 211, seriesId = 21), VodPlayerViewModel::class.java)
        compose.waitForText("Next episode")
        compose.waitForText("Episodes")
        compose.onRoot().captureRoboImage("screenshots/phone-player-episode.png")
    }

    @Test
    fun `Next episode plays the next episode in place of this one`() {
        val navigator = navigators.new(Screen.Home)
        navigator.push(Screen.VodPlayer(playlistId, 211, seriesId = 21))
        compose.setContent { MyPlexTheme { NavHost(remember { navigator }) { ScreenContent(it) } } }
        compose.waitForText("Next episode")
        compose.onNode(hasText("Next episode") and hasClickAction()).performClick()
        compose.waitAdvancingClock(stepMs = 50) { (navigator.current.screen as? Screen.VodPlayer)?.itemId == 212L }
        // In place of the first: Back goes to Home.
        assertEquals(2, navigator.stack.size)
    }

    @Test
    fun `with no channel, the live controls still have Back`() {
        val navigator = navigators.new(Screen.Home)
        navigator.push(Screen.Player(playlistId, NO_CHANNEL, ChannelFilter.All))
        compose.setContent { MyPlexTheme { NavHost(remember { navigator }) { ScreenContent(it) } } }
        val vm = ViewModelProvider(navigator.current)[PlayerViewModel::class.java]
        compose.runOnUiThread { vm.showControls() }
        compose.waitAdvancingClock(stepMs = 50) {
            compose.onAllNodes(hasContentDescription("Back") and hasClickAction()).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNode(hasContentDescription("Back") and hasClickAction()).performClick()
        compose.waitAdvancingClock(stepMs = 50) { navigator.current.screen == Screen.Home }
    }

    @Test
    fun `a flash message shows under the controls' top row`() {
        val vm = play(Screen.VodPlayer(playlistId, 11), VodPlayerViewModel::class.java)
        compose.runOnUiThread {
            vm.showControls()
            vm.flash("Added to favourites")
        }
        compose.waitForText("Added to favourites")
        val back = compose.onNode(hasContentDescription("Back") and hasClickAction()).fetchSemanticsNode().boundsInRoot
        val flash = compose.onNode(hasText("Added to favourites")).fetchSemanticsNode().boundsInRoot
        assertTrue("$flash over $back", flash.top >= back.bottom)
    }

    @Test
    fun `a live channel's controls`() {
        play(Screen.Player(playlistId, plexChannel("1"), ChannelFilter.All), PlayerViewModel::class.java)
        compose.waitForText("Channel guide")
        compose.onRoot().captureRoboImage("screenshots/phone-player-live.png")
    }

    private companion object {
        /** A channel the server does not have. */
        const val NO_CHANNEL = 999_999L
    }
}
