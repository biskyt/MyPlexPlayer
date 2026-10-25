package com.biskyt.myplexplayer.ui.player

import androidx.media3.common.Player
import androidx.test.core.app.ApplicationProvider
import com.biskyt.myplexplayer.testutil.FakePlex
import com.biskyt.myplexplayer.testutil.TestApp
import com.biskyt.myplexplayer.testutil.addHomePlex
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

/** Nothing streams while the app is in the background: a film whose details come meanwhile waits for it to be back. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestApp::class)
class HiddenPlaybackTest {
    @get:Rule val compose = createUiComposeRule()
    private val plex = FakePlex()
    private val app = ApplicationProvider.getApplicationContext<TestApp>()
    private var source = 0L

    @Before
    fun setUp() {
        runBlocking {
            source = app.container.addHomePlex(plex)
            app.container.vod.sync(app.container.db.playlists().get(source)!!)
        }
    }

    @After
    fun tearDown() = plex.close()

    @Test
    fun `a film left while its details load stays stopped, and starts on coming back`() {
        plex.metadataDelayMs = 1_500
        val vm = VodPlayerViewModel(app.container, app, Screen.VodPlayer(source, 11))
        try {
            vm.onAppHidden()
            // Its details arrive (in real time) and the film is opened.
            compose.waitAdvancingClock { vm.engine?.player?.currentMediaItem != null }
            compose.idleFrames()
            assertEquals("nothing loading in the background", Player.STATE_IDLE, vm.engine!!.player.playbackState)

            vm.onAppShown()
            compose.waitAdvancingClock { vm.engine!!.player.playbackState != Player.STATE_IDLE }
            assertTrue(vm.engine!!.player.playWhenReady)
        } finally {
            vm.engine?.release()
        }
    }
}
