package com.biskyt.mytvplayer.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import com.biskyt.mytvplayer.data.ChannelFilter
import com.biskyt.mytvplayer.data.StreamFormat
import com.biskyt.mytvplayer.testutil.FakeProvider
import com.biskyt.mytvplayer.testutil.TestApp
import com.biskyt.mytvplayer.testutil.UI_WAIT_MS
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

/** Provider channels that won't start in one stream format are tried in the other, which is then remembered. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestApp::class)
class StreamFormatTest {
    @get:Rule val compose = createComposeRule()

    private val provider = FakeProvider()
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
    fun `a provider channel that won't start in the format set is tried in the other`() {
        val vm = com.biskyt.mytvplayer.ui.player.PlayerViewModel(app.container, app, Screen.Player(playlistId, 101, ChannelFilter.All))
        // The fake provider has no streams: MPEG-TS (the default) fails, so HLS is tried, once.
        compose.waitUntil(UI_WAIT_MS) {
            // The player retries a failed load a few times first: let its (simulated) time pass.
            org.robolectric.shadows.ShadowLooper.idleMainLooper(500, java.util.concurrent.TimeUnit.MILLISECONDS)
            provider.requests.any { it.startsWith("/live/me/secret/101.m3u8") }
        }
        assertTrue(provider.requests.any { it.startsWith("/live/me/secret/101.ts") })
        vm.engine?.release()
    }

    @Test
    fun `the format a provider fell back to is kept until the setting is changed or the playlist removed`() = runBlocking {
        val settings = app.container.settings
        assertEquals(null, settings.streamFormatFor(playlistId))
        settings.rememberStreamFormat(playlistId, StreamFormat.HLS)
        settings.update { it.copy(lastChannelId = 5) }
        assertEquals(StreamFormat.HLS, settings.streamFormatFor(playlistId))
        settings.setStreamFormat(StreamFormat.HLS)
        assertEquals(null, settings.streamFormatFor(playlistId))
        assertEquals(StreamFormat.HLS, settings.current().streamFormat)
        settings.rememberStreamFormat(playlistId, StreamFormat.TS)
        app.container.playlists.delete(playlistId)
        assertEquals(null, settings.streamFormatFor(playlistId))
        settings.setStreamFormat(StreamFormat.TS) // settings outlive the test
    }
}
