package com.biskyt.myplexplayer.ui

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A live stream that never gets going ends in an error; one that played opens its session again, however long it takes.
 * Neither tunes again (a second tune can take a second tuner), and a slow tune has its time.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestApp::class)
class LiveStallTest {
    @get:Rule val compose = createUiComposeRule()

    private val plex = FakePlex()
    private val app = ApplicationProvider.getApplicationContext<TestApp>()

    // The test's player, cleared at the end as a screen's is: its retries, heartbeat and network callback stop.
    private val store = ViewModelStore()

    @After
    fun tearDown() {
        store.clear()
        plex.close()
    }

    /** A player on the server's first channel, whose stream trickles in: never enough to play. */
    private fun tunedChannel(): PlayerViewModel {
        plex.liveTrickles = true
        val source = runBlocking { app.container.addSyncedHomePlex(plex) }
        val screen = Screen.Player(source, plexChannelId("ch-1"), ChannelFilter.All)
        return ViewModelProvider(store, viewModelFactory { initializer { PlayerViewModel(app.container, app, screen) } })[
            PlayerViewModel::class.java
        ]
    }

    @Test
    fun `a stream that trickles in, never enough to play, ends in an error rather than loading for ever`() {
        val vm = tunedChannel()
        // The stall check and the retries run on (simulated) time; the trickling server's real time hardly passes.
        compose.waitAdvancingClock(1_000) { vm.error != null }
        // Each stall was handled like any failure: the stream retried, but the channel never tuned again (a second
        // tune can take a second tuner).
        assertTrue(plex.liveStreamed.toString(), plex.liveStreamed.size > 1)
        assertEquals(listOf("ch-1"), plex.tuned.toList())
    }

    @Test
    fun `a channel that played opens its session again for as long as it is gone, with no second tune`() {
        val vm = tunedChannel()
        compose.waitAdvancingClock(1_000) { plex.liveStreamed.isNotEmpty() }
        // Its picture started, then the feed went.
        vm.playingChanged(true)
        vm.playingChanged(false)
        // Many more tries than a stream that never played gets before its error.
        compose.waitAdvancingClock(1_000) { plex.liveStreamed.size > 4 }
        // Each time the same session, never a new tune.
        assertEquals(listOf("ch-1"), plex.tuned.toList())
        assertEquals(plex.liveStreamed.toString(), 1, plex.liveStreamed.toSet().size)
        assertNull(vm.error)
        assertTrue(vm.reconnecting)
    }

    @Test
    fun `a tuner still locking has as long as the tune itself to start its picture`() {
        val vm = tunedChannel()
        compose.waitAdvancingClock(1_000) { plex.liveStreamed.isNotEmpty() }
        // 50 s with no picture: well past the 20 s a stream gets, within the minute a tune may take.
        var steps = 0
        compose.waitAdvancingClock(1_000) { ++steps >= 50 }
        assertTrue(vm.tuning)
        assertNull(vm.error)
        assertFalse(vm.reconnecting)
        assertEquals(1, plex.liveStreamed.size)
        assertEquals(listOf("ch-1"), plex.tuned.toList())
    }
}
