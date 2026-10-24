package com.biskyt.myplexplayer

import androidx.test.core.app.ApplicationProvider
import com.biskyt.myplexplayer.testutil.TestApp
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Hosts like Wikimedia and archive.org refuse or throttle clients that don't say who they are. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestApp::class)
class UserAgentTest {
    @Test
    fun `downloads and pictures name the app and where to reach its maker, unless a request names itself`() {
        val http = ApplicationProvider.getApplicationContext<TestApp>().container.http
        MockWebServer().use { server ->
            repeat(2) { server.enqueue(MockResponse()) }
            http.newCall(Request.Builder().url(server.url("/a")).build()).execute().close()
            http.newCall(Request.Builder().url(server.url("/b")).header("User-Agent", "VLC/3.0").build()).execute().close()
            val ours = server.takeRequest().getHeader("User-Agent")!!
            assertTrue(ours, ours.startsWith("MyPlexPlayer/") && "github.com/biskyt/MyPlexPlayer" in ours)
            assertEquals("VLC/3.0", server.takeRequest().getHeader("User-Agent"))
        }
    }
}
