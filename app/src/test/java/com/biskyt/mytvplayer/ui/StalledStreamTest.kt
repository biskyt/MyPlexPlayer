package com.biskyt.mytvplayer.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import com.biskyt.mytvplayer.data.ChannelFilter
import com.biskyt.mytvplayer.testutil.FakeProvider
import com.biskyt.mytvplayer.testutil.TestApp
import com.biskyt.mytvplayer.testutil.UI_WAIT_MS
import com.biskyt.mytvplayer.ui.player.PlayerViewModel
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

/** A stream whose server keeps the connection open but sends nothing playable ends in an error, not "Loading…" for ever. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestApp::class)
class StalledStreamTest {
    @get:Rule val compose = createComposeRule()

    private val provider = FakeProvider().apply { streamsTrickle = true }
    private val app = ApplicationProvider.getApplicationContext<TestApp>()
    private var playlistId = 0L

    @Before
    fun setUp() = runBlocking {
        playlistId = app.container.playlists.add("Home TV", provider.url, "me", "secret")
        app.container.playlists.syncChannels(playlistId)
        Unit
    }

    @After
    fun tearDown() = provider.close()

    @Test
    fun `a stream that never gets going is given up on`() {
        val vm = PlayerViewModel(app.container, app, Screen.Player(playlistId, 101, ChannelFilter.All))
        compose.waitUntil(UI_WAIT_MS) {
            // The watchdog's (simulated) time passes quickly; the trickling server's real time doesn't.
            ShadowLooper.idleMainLooper(1, TimeUnit.SECONDS)
            vm.error != null
        }
        assertTrue(vm.error?.reason.orEmpty(), vm.error?.reason.orEmpty().contains("stopped arriving"))
        assertTrue(provider.requests.any { it.startsWith("/live/me/secret/101") })
        vm.engine?.release()
    }
}
