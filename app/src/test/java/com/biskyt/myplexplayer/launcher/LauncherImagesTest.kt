package com.biskyt.myplexplayer.launcher

import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.biskyt.myplexplayer.testutil.TestApp
import java.io.FileNotFoundException
import java.util.concurrent.CopyOnWriteArrayList
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The launcher's pictures come from the app (its User-Agent, the server's token), and only those of the app's own cards. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestApp::class)
class LauncherImagesTest {
    private val app = ApplicationProvider.getApplicationContext<TestApp>()
    private val poster = byteArrayOf(1, 2, 3, 4)

    /** A server with one picture, poster.jpg: what it was asked for is in [requests]. */
    private val server = MockWebServer()
    private val requests = CopyOnWriteArrayList<String>()
    private val base get() = server.url("/").toString()

    @Before
    fun setUp() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty().also(requests::add)
                return if (path.endsWith(
                        "poster.jpg"
                    )
                ) {
                    MockResponse().setBody(Buffer().write(poster))
                } else {
                    MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        Robolectric.setupContentProvider(LauncherImageProvider::class.java, LauncherImages.authority(app))
    }

    @After
    fun tearDown() {
        LauncherImages.clear(app)
        server.shutdown()
    }

    private fun read(uri: Uri) = app.contentResolver.openInputStream(uri)!!.use { it.readBytes() }

    @Test
    fun `a card's picture is fetched by the app and kept`() {
        val url = base + "art/poster.jpg"
        val uri = LauncherImages.publish(app, listOf(url)).getValue(url)
        assertEquals("content", uri.scheme)
        assertArrayEquals(poster, read(uri))
        assertEquals("one request", 1, requests.count { it.contains("poster.jpg") })
        // Kept: no second request.
        assertArrayEquals(poster, read(uri))
        assertEquals(1, requests.count { it.contains("poster.jpg") })
    }

    @Test
    fun `an address that is not a card's is not fetched`() {
        val stranger = Uri.parse("content://${LauncherImages.authority(app)}/${LauncherImages.keyOf(base + "art/other.jpg")}")
        assertThrows(FileNotFoundException::class.java) { read(stranger) }
        // A card's picture, then no longer a card's.
        val url = base + "art/poster.jpg"
        val uri = LauncherImages.publish(app, listOf(url)).getValue(url)
        LauncherImages.publish(app, emptyList())
        assertThrows(FileNotFoundException::class.java) { read(uri) }
        assertEquals(0, requests.count { it.contains("other.jpg") || it.contains("poster.jpg") })
    }
}
