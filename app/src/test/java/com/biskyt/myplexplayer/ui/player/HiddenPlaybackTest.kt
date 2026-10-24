package com.biskyt.myplexplayer.ui.player

import androidx.media3.common.Player
import androidx.test.core.app.ApplicationProvider
import com.biskyt.myplexplayer.testutil.FakeProvider
import com.biskyt.myplexplayer.testutil.TestApp
import com.biskyt.myplexplayer.testutil.addHomeTv
import com.biskyt.myplexplayer.testutil.createUiComposeRule
import com.biskyt.myplexplayer.testutil.idleFrames
import com.biskyt.myplexplayer.testutil.waitAdvancingClock
import com.biskyt.myplexplayer.ui.Screen
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Leaving the app (Home) while a stream is still being looked up: nothing plays in the background, and coming back plays it. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestApp::class)
class HiddenPlaybackTest {
    @get:Rule val compose = createUiComposeRule()
    private val provider = FakeProvider()
    private val app = ApplicationProvider.getApplicationContext<TestApp>()
    private var playlistId = 0L

    @Before
    fun setUp() = runBlocking {
        provider.streamsTrickle = true // a stream that never fails (its retries would play it too)
        playlistId = app.container.addHomeTv(provider)
    }

    @After
    fun tearDown() = provider.close()

    @Test
    fun `catch-up left while its listing loads stays stopped, and on coming back starts at the programme`() {
        provider.catchupDelayMs = 1_500
        val vm = CatchupPlayerViewModel(app.container, app, Screen.CatchupPlayer(playlistId, 101))
        try {
            vm.onAppHidden()
            // The listing arrives (in real time) and the programme on now is opened.
            compose.waitAdvancingClock { vm.engine?.player?.currentMediaItem != null }
            compose.idleFrames()
            assertEquals("nothing loading in the background", Player.STATE_IDLE, vm.engine!!.player.playbackState)

            vm.onAppShown()
            compose.waitAdvancingClock { vm.engine!!.player.playbackState != Player.STATE_IDLE }
            val uri = vm.engine!!.player.currentMediaItem!!.localConfiguration!!.uri.toString()
            assertTrue(uri, vm.engine!!.player.playWhenReady)
            // The address starts at the programme on now (as in FakeProvider, half an hour ago), not at 1970.
            assertTrue(uri, !uri.contains("/1970-"))
        } finally {
            vm.engine?.release()
        }
    }

    @Test
    fun `catch-up left while it plays carries on from there on coming back`() {
        val vm = CatchupPlayerViewModel(app.container, app, Screen.CatchupPlayer(playlistId, 101))
        try {
            compose.waitAdvancingClock { vm.engine?.player?.playbackState == Player.STATE_BUFFERING }
            val opened = vm.engine!!.player.currentMediaItem!!.localConfiguration!!.uri.toString()
            vm.onAppHidden()
            compose.idleFrames()
            assertEquals(Player.STATE_IDLE, vm.engine!!.player.playbackState)

            vm.onAppShown()
            compose.waitAdvancingClock { vm.engine!!.player.playbackState != Player.STATE_IDLE }
            // Reopened (timeshift streams start where they're asked to) from where it had got to: the same moment here.
            assertEquals(opened, vm.engine!!.player.currentMediaItem!!.localConfiguration!!.uri.toString())
            assertTrue(vm.engine!!.player.playWhenReady)
        } finally {
            vm.engine?.release()
        }
    }
}
