package com.biskyt.mytvplayer.core.xtream

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class XtreamClientTest {
    private val server = MockWebServer()
    private lateinit var client: XtreamClient

    @Before
    fun setUp() {
        server.start()
        client = XtreamClient(OkHttpClient(), XtreamCredentials(server.url("/").toString(), "user one", "p@ss"))
    }

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun `account parses user info and sends credentials`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """{"user_info":{"auth":1,"status":"Active","exp_date":"1800000000","max_connections":"2","active_cons":0},"server_info":{}}""",
            ),
        )
        val account = client.account()
        assertTrue(account.active)
        assertEquals(1_800_000_000L, account.expiresAtEpochSec)
        assertEquals(2, account.maxConnections)
        val url = server.takeRequest().requestUrl!!
        assertEquals("user one", url.queryParameter("username"))
        assertEquals("p@ss", url.queryParameter("password"))
        assertNull(url.queryParameter("action"))
    }

    @Test
    fun `failed login gives a friendly error`() = runTest {
        server.enqueue(MockResponse().setBody("""{"user_info":{"auth":0}}"""))
        assertFalse(client.account().active)
        server.enqueue(MockResponse().setBody("[]"))
        expectXtreamError { client.account() }
    }

    @Test
    fun `http error and garbage are reported as XtreamException`() = runTest {
        server.enqueue(MockResponse().setResponseCode(503))
        expectXtreamError { client.liveCategories() }
        server.enqueue(MockResponse().setBody("<html>not json</html>"))
        expectXtreamError { client.liveCategories() }
    }

    @Test
    fun `categories and streams tolerate mixed types`() = runTest {
        server.enqueue(MockResponse().setBody("""[{"category_id":"5","category_name":"UK | SPORT","parent_id":0},{"category_id":6}]"""))
        val cats = client.liveCategories()
        assertEquals(listOf(XtreamCategory("5", "UK | SPORT"), XtreamCategory("6", "6")), cats)

        server.enqueue(
            MockResponse().setBody(
                """[
                  {"num":1,"name":"UK: ARENA 1 HD","stream_id":"101","stream_icon":"","epg_channel_id":null,"category_id":"5","tv_archive":1,"tv_archive_duration":"7"},
                  {"num":"2","name":" Kite Kids ","stream_id":102,"stream_icon":"http://x/logo.png","epg_channel_id":"kite.uk","category_id":5,"tv_archive":"0"},
                  {"name":"broken, no id"}
                ]""",
            ),
        )
        val streams = mutableListOf<XtreamLiveStream>()
        val count = client.liveStreams("5") { streams += it }
        assertEquals(2, count)
        assertEquals(XtreamLiveStream(101, 1, "UK: ARENA 1 HD", "5", null, null, 7), streams[0])
        assertEquals(XtreamLiveStream(102, 2, "Kite Kids", "5", "http://x/logo.png", "kite.uk", 0), streams[1])
        server.takeRequest()
        assertEquals("5", server.takeRequest().requestUrl!!.queryParameter("category_id"))
    }

    @Test
    fun `large stream list is decoded incrementally`() = runTest {
        val body = Buffer().writeUtf8("[")
        repeat(5000) { i ->
            if (i > 0) body.writeUtf8(",")
            body.writeUtf8("""{"num":$i,"name":"Channel $i","stream_id":$i,"category_id":"${i % 400}","stream_icon":"","tv_archive":0}""")
        }
        body.writeUtf8("]")
        server.enqueue(MockResponse().setBody(body))
        var seen = 0
        var last = -1L
        client.liveStreams { seen++; last = it.streamId }
        assertEquals(5000, seen)
        assertEquals(4999L, last)
    }

    @Test
    fun `stream and xmltv urls are built from credentials`() {
        val c = XtreamClient(OkHttpClient(), XtreamCredentials("example.tv:8080/", "a b", "x/y"))
        assertEquals("http://example.tv:8080/live/a%20b/x%2Fy/42.ts", c.liveStreamUrl(42))
        assertEquals("http://example.tv:8080/xmltv.php?username=a%20b&password=x%2Fy", c.xmltvUrl())
    }

    private suspend fun expectXtreamError(block: suspend () -> Unit) {
        try {
            block()
            fail("Expected XtreamException")
        } catch (_: XtreamException) {
        }
    }
}
