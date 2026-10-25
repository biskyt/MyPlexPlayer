package com.biskyt.myplexplayer.player

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.biskyt.myplexplayer.data.Settings
import com.biskyt.myplexplayer.testutil.PlainApp
import com.biskyt.myplexplayer.ui.player.RetryBudget
import java.util.concurrent.TimeUnit
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = PlainApp::class)
class PlaybackEngineTest {
    private val engine = PlaybackEngine(ApplicationProvider.getApplicationContext<Context>(), Settings())
    private var unsupported = 0
    private var unsupportedUrl: String? = null

    init {
        engine.onUnsupported = {
            unsupported++
            unsupportedUrl = it
        }
    }

    @After
    fun tearDown() = engine.release()

    @Test
    fun `DASH streams play, and a stream type with no support reports it instead of crashing`() {
        // BBC Four in the free UK list is DASH: this used to throw and close the app.
        engine.play("https://example.invalid/bbc/stream/BBC_FOUR/hevc_iptv_mse_v0.mpd", hls = null)
        assertEquals(0, unsupported)
        // SmoothStreaming isn't included.
        engine.play("https://example.invalid/channel.ism/Manifest", hls = null)
        assertEquals(1, unsupported)
        // The message names where it comes from.
        assertEquals("Stream from example.invalid.", streamSource(unsupportedUrl))
    }

    @Test
    fun `headers given with a stream go with its requests, not in its address`() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(404))
            engine.play(server.url("/file.mkv").toString(), hls = false, headers = mapOf("X-Plex-Token" to "secret"))
            val request = (1..WAIT_STEPS).firstNotNullOf {
                shadowOf(Looper.getMainLooper()).idle()
                server.takeRequest(WAIT_STEP_MS, TimeUnit.MILLISECONDS)
            }
            assertEquals("secret", request.getHeader("X-Plex-Token"))
            assertEquals("/file.mkv", request.path)
        }
    }

    @Test
    fun `a failing stream gets a few retries in a row, then fresh ones once it has played`() {
        val budget = RetryBudget(3)
        repeat(3) { assertTrue(budget.take()) }
        assertFalse(budget.take()) // starts and fails again and again: the user is told
        budget.reset() // played steadily for a while
        assertTrue(budget.take())
        assertEquals(1, budget.used)
    }

    @Test
    fun `the sound label comes from the selected audio track, before the audio output has started`() {
        val format = androidx.media3.common.Format.Builder()
            .setSampleMimeType(androidx.media3.common.MimeTypes.AUDIO_AAC).setChannelCount(2).build()
        val group = androidx.media3.common.Tracks.Group(
            androidx.media3.common.TrackGroup(format),
            false,
            intArrayOf(androidx.media3.common.C.FORMAT_HANDLED),
            booleanArrayOf(true)
        )
        // Nothing has played, so the player itself has no audio format yet: this used to say "No audio".
        assertEquals("Audio · Stereo", engine.audioOutputLabel(androidx.media3.common.Tracks(listOf(group))))
        assertEquals("No audio", engine.audioOutputLabel(androidx.media3.common.Tracks.EMPTY))
    }

    private companion object {
        const val WAIT_STEPS = 100
        const val WAIT_STEP_MS = 50L
    }
}
