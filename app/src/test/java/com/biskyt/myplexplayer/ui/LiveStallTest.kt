package com.biskyt.myplexplayer.ui

import android.content.Context
import android.net.ConnectivityManager
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.media3.common.PlaybackException
import androidx.test.core.app.ApplicationProvider
import com.biskyt.myplexplayer.AppContainer
import com.biskyt.myplexplayer.HTTP_READ_TIMEOUT_MS
import com.biskyt.myplexplayer.R
import com.biskyt.myplexplayer.data.ChannelFilter
import com.biskyt.myplexplayer.data.plexChannelId
import com.biskyt.myplexplayer.testutil.FakePlex
import com.biskyt.myplexplayer.testutil.TestApp
import com.biskyt.myplexplayer.testutil.addSyncedHomePlex
import com.biskyt.myplexplayer.testutil.createUiComposeRule
import com.biskyt.myplexplayer.testutil.reportNetwork
import com.biskyt.myplexplayer.testutil.waitAdvancingClock
import com.biskyt.myplexplayer.ui.player.PlaybackViewModel
import com.biskyt.myplexplayer.ui.player.PlayerViewModel
import kotlinx.coroutines.launch
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

    /** Waits for the stream to be asked for, then plays it and loses it: as a picture that started, then the feed went. */
    private fun playedThenLost(vm: PlayerViewModel) {
        compose.waitAdvancingClock(1_000) { plex.liveStreamed.isNotEmpty() }
        vm.playingChanged(true)
        vm.playingChanged(false)
    }

    /** Moves the (simulated) clock on by [n] seconds, a second at a time. */
    private fun advanceSeconds(n: Int) {
        var steps = 0
        compose.waitAdvancingClock(1_000) { ++steps >= n }
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
        playedThenLost(vm)
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
        advanceSeconds(50)
        assertTrue(vm.tuning)
        assertNull(vm.error)
        assertFalse(vm.reconnecting)
        assertEquals(1, plex.liveStreamed.size)
        assertEquals(listOf("ch-1"), plex.tuned.toList())
    }

    @Test
    fun `a session that showed no picture waits a tune's minute once, then a short wait on each reopen`() {
        tunedChannel()
        // The tune's minute, then the first reopen of the same session.
        compose.waitAdvancingClock(1_000) { plex.liveStreamed.size == 2 }
        // The next reopen comes after a stream's 20 s, not a minute for each reopen (that gave about 4 minutes of
        // "Tuning…").
        advanceSeconds(15)
        assertEquals(plex.liveStreamed.toString(), 2, plex.liveStreamed.size)
        advanceSeconds(15)
        assertTrue(plex.liveStreamed.toString(), plex.liveStreamed.size >= 3)
        assertEquals(listOf("ch-1"), plex.tuned.toList())
    }

    @Test
    fun `a channel that played waits as long as a tune for more data, so a slow server's reconnect is not cut off`() {
        val vm = tunedChannel()
        compose.waitAdvancingClock(1_000) { plex.liveStreamed.isNotEmpty() }
        // The tune's own wait (TUNE_WAIT_MS): the HTTP read time-out, not live TV's 12 s.
        assertEquals(HTTP_READ_TIMEOUT_MS, vm.engine!!.rebufferMs)
    }

    @Test
    fun `a session the server ended fails to the error, and Try again tunes once more after letting the old tuner go`() {
        val vm = tunedChannel()
        playedThenLost(vm)
        // The server ends the session: asking again does not mend a 404, so the tries stop at the usual limit.
        plex.liveGone = true
        compose.waitAdvancingClock(1_000) { vm.error != null }
        assertEquals(listOf("ch-1"), plex.tuned.toList())
        // Only Try again tunes again, once the old session is let go.
        vm.retry()
        compose.waitAdvancingClock(1_000) { plex.tuned.size == 2 }
        assertTrue(plex.liveStopped.toString(), plex.liveStopped.any { it.endsWith("1-ch-1") })
    }

    @Test
    fun `a try still waiting does not open the stream again once the player is left`() {
        val vm = tunedChannel()
        playedThenLost(vm)
        // The stall comes, and its try waits (a second): the player is left before it runs.
        // Half-second steps: a tuned stream has a minute before its stall, and the try waits a second after it.
        compose.waitAdvancingClock(500) { vm.reconnecting }
        val asked = plex.liveStreamed.size
        vm.stop()
        advanceSeconds(30)
        assertEquals(asked, plex.liveStreamed.size)
        assertFalse(vm.reconnecting)
    }

    @Test
    fun `a recording opened again from its start has as long as a tune to start its picture`() {
        val vm = tunedChannel()
        compose.waitAdvancingClock(1_000) { plex.liveStreamed.isNotEmpty() }
        // "Watch from start": the server starts the session again, with no new tune.
        vm.watchFromStart()
        compose.waitAdvancingClock(1_000) { plex.liveStreamed.size == 2 }
        advanceSeconds(50)
        assertEquals(plex.liveStreamed.toString(), 2, plex.liveStreamed.size)
        assertEquals(listOf("ch-1"), plex.tuned.toList())
    }

    @Test
    fun `a tune whose first try fails at once still gets its minute in all before the short waits`() {
        val vm = tunedChannel()
        compose.waitAdvancingClock(1_000) { plex.liveStreamed.isNotEmpty() }
        advanceSeconds(3)
        // A quick failure that passes (the session's playlist not there yet), while the tuner still locks. As a real
        // failure does, it stops the player first.
        val engine = vm.engine!!
        engine.player.stop()
        engine.onStalled(PlaybackException("drop", null, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED))
        compose.waitAdvancingClock(1_000) { plex.liveStreamed.size == 2 }
        // The reopen waits for the rest of the tune's minute, not only a stream's 20 s.
        advanceSeconds(35)
        assertEquals(plex.liveStreamed.toString(), 2, plex.liveStreamed.size)
        assertEquals(listOf("ch-1"), plex.tuned.toList())
    }

    @Test
    fun `a session started again from its start that shows no picture gets the short wait on each reopen too`() {
        val vm = tunedChannel()
        compose.waitAdvancingClock(1_000) { plex.liveStreamed.isNotEmpty() }
        vm.pictureStarted() // the first session showed its picture
        vm.watchFromStart()
        // The new session's minute, then its first reopen.
        compose.waitAdvancingClock(1_000) { plex.liveStreamed.size == 3 }
        advanceSeconds(15)
        assertEquals(plex.liveStreamed.toString(), 3, plex.liveStreamed.size)
        advanceSeconds(15)
        assertTrue(plex.liveStreamed.toString(), plex.liveStreamed.size >= 4)
        assertEquals(listOf("ch-1"), plex.tuned.toList())
    }

    @Test
    fun `a session that keeps failing in a way that passes shows the error after 5 minutes, with no second tune`() {
        val vm = tunedChannel()
        playedThenLost(vm)
        // The feed never comes back: stall after stall, each one that passes.
        compose.waitAdvancingClock(5_000) { vm.error != null }
        assertFalse(vm.reconnecting)
        assertTrue(plex.liveStreamed.toString(), plex.liveStreamed.size > 4)
        assertEquals(listOf("ch-1"), plex.tuned.toList())
    }

    /** A live player that keeps the start of what the stream holds, as a recording watched from its start does. */
    private class FromStart(c: AppContainer, app: Context) : PlaybackViewModel(c, app) {
        override val isLive = true
        override val failureMessage = R.string.player_reconnecting
        override val keepsWindowStart = true
        val reconnects = mutableListOf<Boolean>()

        init {
            viewModelScope.launch { startEngine() }
        }

        override fun reconnect() {
            reconnects += behind
        }
    }

    @Test
    fun `the network coming back keeps a fall behind the live window, so a recording from its start stays in it`() {
        val vm = ViewModelProvider(store, viewModelFactory { initializer { FromStart(app.container, app) } })[FromStart::class.java]
        compose.waitAdvancingClock { vm.engine != null }
        vm.playingChanged(true)
        vm.playingChanged(false)
        vm.engine!!.onStalled(PlaybackException("behind", null, PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW))
        // The try waits; the network comes back first, and Android finds that it works.
        app.getSystemService(ConnectivityManager::class.java).reportNetwork(vpn = false, validated = true)
        compose.waitAdvancingClock { vm.reconnects.isNotEmpty() }
        assertEquals(listOf(true), vm.reconnects)
    }
}
