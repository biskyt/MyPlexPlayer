package com.biskyt.myplexplayer.ui

import androidx.compose.runtime.remember
import androidx.lifecycle.ViewModelProvider
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.TrackGroup
import androidx.media3.common.Tracks
import androidx.test.core.app.ApplicationProvider
import com.biskyt.myplexplayer.testutil.FakePlex
import com.biskyt.myplexplayer.testutil.Navigators
import com.biskyt.myplexplayer.testutil.TestApp
import com.biskyt.myplexplayer.testutil.addSyncedHomePlex
import com.biskyt.myplexplayer.testutil.createUiComposeRule
import com.biskyt.myplexplayer.testutil.focusedText
import com.biskyt.myplexplayer.testutil.idleFrames
import com.biskyt.myplexplayer.testutil.waitAdvancingClock
import com.biskyt.myplexplayer.testutil.waitForText
import com.biskyt.myplexplayer.ui.player.PlaybackViewModel
import com.biskyt.myplexplayer.ui.player.VodPlayerViewModel
import com.biskyt.myplexplayer.ui.theme.MyPlexTheme
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.captureScreenRoboImage
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A player panel (Audio) in the shared board style, recorded to compare with the boards (see BoardPanel.kt). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestApp::class, qualifiers = "+w960dp-h540dp-tvdpi")
class PlayerSheetScreenshotTest {
    @get:Rule val compose = createUiComposeRule()

    @get:Rule(order = Navigators.ORDER)
    val navigators = Navigators()

    private val plex = FakePlex()
    private val app = ApplicationProvider.getApplicationContext<TestApp>()

    @After
    fun tearDown() = plex.close()

    @OptIn(ExperimentalRoborazziApi::class)
    @Test
    fun `the player's Audio panel`() {
        val source = runBlocking { app.container.addSyncedHomePlex(plex) }
        val navigator = navigators.new(Screen.VodPlayer(source, 211, seriesId = 21, fromStart = true))
        compose.setContent { MyPlexTheme { NavHost(remember { navigator }) { ScreenContent(it) } } }
        val vm = ViewModelProvider(navigator.current)[VodPlayerViewModel::class.java]
        compose.waitAdvancingClock { vm.engine?.player?.currentMediaItem != null }
        // Stopped, so that the player's own listeners (its tracks, a retry, an error) change nothing set below.
        compose.runOnIdle { vm.engine!!.player.stop() }
        compose.waitAdvancingClock { vm.engine?.player?.playbackState == Player.STATE_IDLE }
        // The test's file has no tracks the player reads: three soundtracks, the first in use, as a file has them.
        val tracks = Tracks(
            listOf(
                audio("en", MimeTypes.AUDIO_E_AC3, 6, selected = true),
                audio("fr", MimeTypes.AUDIO_AC3, 6),
                audio("en", MimeTypes.AUDIO_AAC, 2, label = "Commentary"),
            )
        )
        compose.runOnIdle {
            set(vm, "setTracks", Tracks::class.java, tracks)
            set(vm, "setAudioLabel", String::class.java, vm.engine!!.audioOutputLabel(tracks, null))
            vm.sheet = PlaybackViewModel.Sheet.AUDIO
        }
        compose.waitForText("Commentary", stepMs = 50)
        compose.waitAdvancingClock(stepMs = 50) { "English" in compose.focusedText() }
        compose.idleFrames()
        captureScreenRoboImage("screenshots/player-audio.png")
        val focused = compose.focusedText()
        assertTrue("the track in use has the remote: $focused", "English · 6ch" in focused)
        assertEquals(tracks, vm.tracks)
    }

    private fun audio(language: String, mime: String, channels: Int, selected: Boolean = false, label: String? = null) = Tracks.Group(
        TrackGroup(Format.Builder().setSampleMimeType(mime).setLanguage(language).setLabel(label).setChannelCount(channels).build()),
        false,
        intArrayOf(C.FORMAT_HANDLED),
        booleanArrayOf(selected)
    )

    /** The view model's tracks and audio label have private setters: the player's listener sets them. */
    private fun <T> set(vm: PlaybackViewModel, setter: String, type: Class<T>, value: T) {
        PlaybackViewModel::class.java.getDeclaredMethod(setter, type).apply { isAccessible = true }.invoke(vm, value)
    }
}
