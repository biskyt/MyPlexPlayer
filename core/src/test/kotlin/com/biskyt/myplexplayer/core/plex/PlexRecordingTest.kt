package com.biskyt.myplexplayer.core.plex

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Recording from the guide, as proven on the owner's server (docs/plex-api.md, 14 and 22-24), on invented data. */
class PlexRecordingTest {
    private val server = MockWebServer()
    private lateinit var client: PlexClient

    private val responses = mapOf(
        "GET media/subscriptions/template" to "recording-template",
        "GET media/subscriptions" to "recording-rules",
        "GET media/subscriptions/scheduled" to "recording-scheduled",
        "POST media/subscriptions" to "recording-rules",
    ).mapValues { (_, name) -> javaClass.getResource("/plex/$name.json")!!.readText() }

    @Before
    fun setUp() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val body = responses["${request.method} ${request.requestUrl!!.encodedPath.trimStart('/')}"]
                return if (body != null) MockResponse().setBody(body) else MockResponse()
            }
        }
        server.start()
        val device = PlexDevice(clientId = "client-1", product = "MyPlexPlayer", version = "0.1.0", deviceName = "Test TV")
        client = PlexClient(OkHttpClient(), PlexServer(server.url("/").toString(), "secret-token"), device)
    }

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun `a programme's ways to record, this airing or its series, with their settings`() = runTest {
        val options = client.recordingOptions("plex://episode/ep0001")
        assertEquals("plex://episode/ep0001", server.takeRequest().requestUrl!!.queryParameter("guid"))
        assertEquals(listOf(PlexRecordingOption.EPISODE, PlexRecordingOption.SERIES), options.map { it.type })
        val series = options.single { it.isSeries }
        assertEquals("0", series.settings.first { it.id == "onlyNewAirings" }.current)
        // Values as Plex reads them back: numbers as text, booleans in lower case.
        assertEquals("true", options.first().settings.first { it.id == "recordPartials" }.current)
        assertEquals("-1", options.first().settings.first { it.id == "startTimeslot" }.current)
    }

    @Test
    fun `recording sends the template's parameters as they are, what they leave out, and every setting`() = runTest {
        val series = client.recordingOptions("plex://episode/ep0001").single { it.isSeries }
        server.takeRequest()
        val rule = client.record(series, mapOf("onlyNewAirings" to "1"))
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        val url = request.requestUrl!!
        assertTrue(url.encodedQuery!!.startsWith(series.parameters))
        assertEquals("plex://show/show0001", url.queryParameter("hints[guid]"))
        assertEquals("2", url.queryParameter("targetLibrarySectionID"))
        assertEquals("2", url.queryParameter("type"))
        assertEquals("1", url.queryParameter("prefs[onlyNewAirings]")) // the change, over the template's 0
        assertEquals("0", url.queryParameter("prefs[endOffsetMinutes]"))
        assertEquals("false", url.queryParameter("prefs[oneShot]")) // hidden ones too: the server needs every one
        assertEquals("11", rule.key)
    }

    @Test
    fun `the rules, a series with its show and airings, and what's scheduled`() = runTest {
        val rules = client.recordingRules()
        server.takeRequest()
        val series = rules.first()
        assertTrue(series.isSeries)
        assertEquals("plex://show/show0001", series.subject?.guid)
        assertEquals("The Harbour", series.subject?.title)
        assertEquals("1", series.setting("onlyNewAirings")) // new airings only, as set
        assertEquals(null, rules.last().setting("onlyNewAirings"))
        assertFalse(rules.last().isSeries)
        assertEquals("A Film", rules.last().subject?.title)

        val scheduled = client.scheduledRecordings()
        server.takeRequest()
        assertEquals(listOf(true, false, false), scheduled.map { it.isScheduled })
        assertEquals(listOf(false, true, false), scheduled.map { it.isRecording })
        assertEquals("11", scheduled.first().mediaSubscriptionID)
        val first = scheduled.first().airing!!
        assertEquals(1_793_000_000L, first.beginsAt)
        assertEquals("5fc705db-ch1", first.channelIdentifier)
    }

    @Test
    fun `one airing is cancelled, and a rule removed`() = runTest {
        client.cancelRecording("/media/grabbers/operations/g1")
        server.takeRequest().let {
            assertEquals("DELETE", it.method)
            assertEquals("/media/grabbers/operations/g1", it.requestUrl!!.encodedPath)
        }
        client.removeRecording("11")
        server.takeRequest().let {
            assertEquals("DELETE", it.method)
            assertEquals("/media/subscriptions/11", it.requestUrl!!.encodedPath)
        }
    }

    @Test
    fun `a rule's setting is changed`() = runTest {
        client.changeRecording("11", mapOf("onlyNewAirings" to "0"))
        server.takeRequest().let {
            assertEquals("PUT", it.method)
            assertEquals("/media/subscriptions/11", it.requestUrl!!.encodedPath)
            assertEquals("0", it.requestUrl!!.queryParameter("prefs[onlyNewAirings]"))
        }
    }

    @Test
    fun `the server refusing a second rule for the same thing says so`() = runTest {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().setResponseCode(409)
        }
        val error = runCatching { client.record(PlexRecordingOption(type = PlexRecordingOption.EPISODE)) }.exceptionOrNull()
        assertEquals(409, (error as PlexException).code)
    }
}
