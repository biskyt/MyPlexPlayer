package com.biskyt.myplexplayer.ui

import androidx.test.core.app.ApplicationProvider
import com.biskyt.myplexplayer.data.ChannelFilter
import com.biskyt.myplexplayer.data.plexChannelId
import com.biskyt.myplexplayer.testutil.FakePlex
import com.biskyt.myplexplayer.testutil.TestApp
import com.biskyt.myplexplayer.testutil.addSyncedHomePlex
import com.biskyt.myplexplayer.testutil.createUiComposeRule
import com.biskyt.myplexplayer.testutil.waitAdvancingClock
import com.biskyt.myplexplayer.ui.player.PlayerViewModel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A live stream that never gets going ends in an error. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestApp::class)
class LiveStallTest {
    @get:Rule val compose = createUiComposeRule()

    private val plex = FakePlex()
    private val app = ApplicationProvider.getApplicationContext<TestApp>()

    @After
    fun tearDown() = plex.close()

    @Test
    fun `a stream that trickles in, never enough to play, ends in an error rather than loading for ever`() {
        plex.liveTrickles = true
        val source = runBlocking { app.container.addSyncedHomePlex(plex) }
        val vm = PlayerViewModel(app.container, app, Screen.Player(source, plexChannelId("ch-1"), ChannelFilter.All))
        // The stall check and the retries run on (simulated) time; the trickling server's real time hardly passes.
        compose.waitAdvancingClock(1_000) { vm.error != null }
        // Each stall was handled like any failure: the stream retried, but the channel never tuned again (a second
        // tune can take a second tuner).
        assertTrue(plex.liveStreamed.toString(), plex.liveStreamed.size > 1)
        assertEquals(listOf("ch-1"), plex.tuned.toList())
        vm.engine?.release()
    }
}
