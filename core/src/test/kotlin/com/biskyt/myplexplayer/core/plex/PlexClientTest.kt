package com.biskyt.myplexplayer.core.plex

import kotlinx.coroutines.test.runTest
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Against responses recorded from a real Plex Media Server (spikes/plex_spike.py, sanitised). */
class PlexClientTest {
    private val server = MockWebServer()
    private val device = PlexDevice(clientId = "client-1", product = "MyPlexPlayer", version = "0.1.0", deviceName = "Test TV")
    private lateinit var client: PlexClient

    /** Path (without the leading slash) to response; anything else is answered with [otherwise]. */
    private val responses = mapOf(
        "identity" to "identity",
        "library/sections" to "sections",
        "library/sections/1/all" to "movie-all-page1",
        "library/sections/2/all" to "shows-by-episode-addedAt",
        "library/metadata/1" to "movie-metadata",
        "library/metadata/2/children" to "show-children",
        "library/metadata/3/children" to "season-children",
        "library/metadata/2/allLeaves" to "show-allLeaves",
        "hubs/continueWatching" to "continueWatching",
        "hubs/search" to "hubs-search-show",
        "video/:/transcode/universal/decision" to "direct-stream-decision",
        "livetv/dvrs" to "livetv-dvrs",
        "media/providers" to "media-providers",
        "tv.plex.providers.epg.cloud:12/lineups/dvr/channels" to "lineup-channels",
        "tv.plex.providers.epg.cloud:12/grid" to "grid",
        "livetv/dvrs/7/channels/5fc705db-ch1/tune" to "tune",
    ).mapValues { (_, name) -> MockResponse().setBody(javaClass.getResource("/plex/$name.json")!!.readText()) }
        .toMutableMap()
    private var otherwise = MockResponse()

    @Before
    fun setUp() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = responses[request.requestUrl!!.encodedPath.trimStart('/')] ?: otherwise
        }
        server.start()
        client = PlexClient(OkHttpClient(), PlexServer(server.url("/").toString(), "secret-token"), device)
    }

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun `every request asks for JSON and says who is asking`() = runTest {
        assertEquals("MACHINEID", client.identity())
        val request = server.takeRequest()
        assertEquals("application/json", request.getHeader("Accept"))
        assertEquals("secret-token", request.getHeader("X-Plex-Token"))
        assertEquals("client-1", request.getHeader("X-Plex-Client-Identifier"))
        assertEquals("MyPlexPlayer", request.getHeader("X-Plex-Product"))
        assertEquals("Test TV", request.getHeader("X-Plex-Device-Name"))
        assertNull("blank values aren't sent", request.getHeader("X-Plex-Device"))
    }

    @Test
    fun `live tv, its channels and guide, and tuning a channel to stream it as it is`() = runTest {
        val live = client.liveTv()!!
        assertEquals(PlexLiveTv("7", "tv.plex.providers.epg.cloud:12/lineups/dvr/channels", "tv.plex.providers.epg.cloud:12/grid"), live)
        server.takeRequest()
        server.takeRequest()

        val channels = client.channels(live)
        assertEquals(listOf("5fc705db-ch1", "5fc705db-ch2"), channels.map { it.tuneKey })
        assertEquals(listOf("1", "101"), channels.map { it.number }) // a string, or a number
        assertEquals(listOf("Channel One", "CH2"), channels.map { it.name }) // no title: its call sign
        server.takeRequest()

        val guide = client.guide(live, fromS = 1_792_950_000, toS = 1_792_970_000)
        val url = server.takeRequest().requestUrl!!
        assertEquals("1792950000", url.queryParameter("endsAt>"))
        assertEquals("1792970000", url.queryParameter("beginsAt<"))
        // As the owner's server sends it: each showing names its channel, and the programme's Channel list holds tags
        // (a number and a name), which mustn't cost the programme.
        assertEquals(listOf("The Pilot", "A Film"), guide.map { it.title })
        assertEquals(listOf("5fc705db-ch1", "5fc705db-ch2", "5fc705db-ch2"), guide.flatMap { it.airings }.map { it.channelIdentifier })

        val tuned = client.tune(live, channels.first().tuneKey!!, "session-1")
        val tune = server.takeRequest()
        assertEquals("POST", tune.method)
        assertEquals("session-1", tune.requestUrl!!.queryParameter("X-Plex-Session-Identifier"))
        assertEquals("/livetv/sessions/0f3c-uuid", tuned.key)

        val stream = client.liveStream(tuned.key, "session-1")
        val decision = server.takeRequest().requestUrl!!
        assertEquals("/livetv/sessions/0f3c-uuid", decision.queryParameter("path"))
        assertNull("nothing to start into", decision.queryParameter("offset"))
        assertTrue(stream.url.contains("start.m3u8"))

        client.liveTimeline(tuned, PlexPlayState.STOPPED, 5_000, "session-1")
        val stopped = server.takeRequest().requestUrl!!
        assertEquals("/livetv/sessions/0f3c-uuid", stopped.queryParameter("key"))
        assertEquals("stopped", stopped.queryParameter("state"))
        assertEquals("3600000", stopped.queryParameter("duration"))
    }

    @Test
    fun `a guide with every field a real server sends reads in full`() = runTest {
        // Shaped like a real server's guide reply (every field it sends), with made-up titles, channels and ids.
        responses["tv.plex.providers.epg.cloud:12/grid"] =
            MockResponse().setBody(javaClass.getResource("/plex/grid-full.json")!!.readText())
        val guide = client.guide(client.liveTv()!!, fromS = 1_792_300_000, toS = 1_792_340_000)
        assertEquals(listOf("episode", "movie"), guide.map { it.type })
        val airings = guide.flatMap { it.airings }
        assertEquals(guide.size, airings.size)
        assertTrue(airings.all { it.beginsAt != null && it.endsAt != null && it.channelIdentifier?.contains('-') == true })
    }

    @Test
    fun `a guide none of which can be read fails, rather than looking empty`() = runTest {
        val live = client.liveTv()!!
        responses["tv.plex.providers.epg.cloud:12/grid"] = MockResponse().setBody(
            """{"MediaContainer":{"size":2,"Metadata":[{"title":"A","year":"x"},{"title":"B","Media":"no"}]}}"""
        )
        expectFailure(PlexException.Reason.UNEXPECTED_RESPONSE) { client.guide(live, 0, 1) }
        // An empty guide is just empty.
        responses["tv.plex.providers.epg.cloud:12/grid"] = MockResponse().setBody("""{"MediaContainer":{"size":0}}""")
        assertEquals(emptyList<PlexProgramme>(), client.guide(live, 0, 1))
    }

    @Test
    fun `a guide entry that can't be read is left out, not the whole guide`() = runTest {
        val live = client.liveTv()!!
        responses["tv.plex.providers.epg.cloud:12/grid"] = MockResponse().setBody(
            """{"MediaContainer":{"Metadata":[
                {"type":"movie","title":null,"Media":[{"beginsAt":1792950000,"endsAt":1792953600,"channelIdentifier":"ch1"}]},
                {"type":"movie","title":"Odd","year":"unknown","Media":[{"beginsAt":1792950000,"endsAt":1792953600}]},
                {"type":"episode","title":"Pilot","Media":[{"beginsAt":"later"},{"beginsAt":1792953600,"endsAt":1792957200,"channelIdentifier":"ch1"}]}
            ]}}"""
        )
        val guide = client.guide(live, fromS = 1_792_950_000, toS = 1_792_970_000)
        assertEquals(listOf("", "Pilot"), guide.map { it.title }) // no title: blank; a year that isn't one: left out
        assertEquals(listOf(1_792_953_600L), guide.last().airings.map { it.beginsAt }) // only the showing it can read
    }

    @Test
    fun `a server without live tv says so`() = runTest {
        responses["livetv/dvrs"] = MockResponse().setBody("""{"MediaContainer":{"size":0}}""")
        assertNull(client.liveTv())
    }

    @Test
    fun `titles played lately, and several items at once`() = runTest {
        assertEquals(listOf("Test Movie (2020)"), client.viewedSince("1", PlexType.MOVIE, 90).map { it.title })
        var url = server.takeRequest().requestUrl!!
        assertEquals("1", url.queryParameter("type"))
        assertEquals("-90", url.queryParameter("lastViewedAt>>"))
        client.viewedSince("2", PlexType.SHOW, 90)
        url = server.takeRequest().requestUrl!!
        assertEquals("2", url.queryParameter("type"))
        assertEquals("-90", url.queryParameter("episode.lastViewedAt>>"))

        responses["library/metadata/1,4"] = responses.getValue("library/metadata/1")
        assertEquals(listOf("1"), client.items(listOf("1", "4")).map { it.ratingKey })
        assertEquals("/library/metadata/1,4", server.takeRequest().requestUrl!!.encodedPath)
        assertEquals(emptyList<PlexItem>(), client.items(emptyList()))
        assertEquals("nothing asked", 0, server.requestCount - 3)
    }

    @Test
    fun `libraries, pages and recently added`() = runTest {
        assertEquals(listOf("movie", "show"), client.sections().map { it.type }.sorted())
        server.takeRequest()

        val page = client.page("1", PlexType.MOVIE, start = 0, size = 1, sort = "titleSort")
        assertEquals(listOf("Test Movie (2020)"), page.items.map { it.title })
        assertEquals(1, page.totalSize)
        val url = server.takeRequest().requestUrl!!
        assertEquals("1", url.queryParameter("type"))
        assertEquals("titleSort", url.queryParameter("sort"))
        assertEquals("0", url.queryParameter("X-Plex-Container-Start"))
        assertEquals("1", url.queryParameter("X-Plex-Container-Size"))

        // Shows by their newest episode (the recentlyAdded hub would give seasons); films by when they were added.
        assertEquals(listOf("show"), client.recentlyAdded("2", PlexType.SHOW, 20).map { it.type })
        assertEquals("episode.addedAt:desc", server.takeRequest().requestUrl!!.queryParameter("sort"))
        client.recentlyAdded("1", PlexType.MOVIE, 20)
        server.takeRequest().requestUrl!!.let {
            assertEquals("addedAt:desc", it.queryParameter("sort"))
            assertEquals("20", it.queryParameter("X-Plex-Container-Size"))
        }

        // A page that doesn't give the library's size leaves it unknown, rather than claiming this page is all.
        responses["library/sections/1/all"] = MockResponse().setBody("""{"MediaContainer":{"size":1,"Metadata":[{"ratingKey":"1"}]}}""")
        assertNull(client.page("1", PlexType.MOVIE, 0, 1).totalSize)
    }

    @Test
    fun `a film, a show's seasons and episodes`() = runTest {
        val movie = client.item("1")
        assertEquals("movie", movie.type)
        assertEquals(600_000L, movie.duration)
        assertEquals("/library/parts/1/1792875002/file.mp4", movie.part!!.key)

        val seasons = client.children("2")
        assertEquals(listOf("season"), seasons.map { it.type })
        val episodes = client.children("3")
        assertEquals(listOf(1, 2), episodes.map { it.index })
        assertEquals(listOf("Test Show", "Test Show"), episodes.map { it.grandparentTitle })
        assertEquals(episodes.map { it.ratingKey }, client.episodes("2").map { it.ratingKey })
    }

    @Test
    fun `continue watching and search`() = runTest {
        val carryOn = client.continueWatching().single()
        assertEquals("episode", carryOn.type)
        assertEquals("Test Show", carryOn.grandparentTitle)
        assertEquals(180_013L, carryOn.viewOffset)
        assertTrue("when it was last played", carryOn.lastViewedAt!! > 1_600_000_000)

        assertEquals(setOf("movie", "show"), client.search("Test").map { it.type }.toSet())
        server.takeRequest()
        assertEquals("Test", server.takeRequest().requestUrl!!.queryParameter("query"))
    }

    @Test
    fun `timeline reports progress with the playback's session`() = runTest {
        val episode = PlexItem(ratingKey = "4", duration = 600_046)
        client.timeline(episode, PlexPlayState.PLAYING, 90_000, "session-1")
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("session-1", request.getHeader("X-Plex-Session-Identifier"))
        val url = request.requestUrl!!
        assertEquals("/:/timeline", url.encodedPath)
        assertEquals("4", url.queryParameter("ratingKey"))
        assertEquals("/library/metadata/4", url.queryParameter("key"))
        assertEquals("playing", url.queryParameter("state"))
        assertEquals("90000", url.queryParameter("time"))
        assertEquals("600046", url.queryParameter("duration"))

        // The server refuses a duration shorter than the time (a live programme overrunning).
        client.timeline(PlexItem(ratingKey = "4", duration = 3_000), PlexPlayState.STOPPED, 5_000, "session-1")
        assertEquals("5000", server.takeRequest().requestUrl!!.queryParameter("duration"))
        // With no duration every report would read as the end, which the server takes as watched: refused.
        assertTrue(
            runCatching { client.timeline(PlexItem(ratingKey = "4"), PlexPlayState.PLAYING, 90_000, "s") }
                .exceptionOrNull() is IllegalArgumentException
        )
    }

    @Test
    fun `marking watched and unwatched`() = runTest {
        client.setWatched("4", watched = true)
        client.setWatched("4", watched = false)
        listOf("/:/scrobble", "/:/unscrobble").forEach { path ->
            val request = server.takeRequest()
            assertEquals("PUT", request.method)
            assertEquals(path, request.requestUrl!!.encodedPath)
            assertEquals("com.plexapp.plugins.library", request.requestUrl!!.queryParameter("identifier"))
            assertEquals("4", request.requestUrl!!.queryParameter("key"))
        }
    }

    @Test
    fun `taking a title off continue watching`() = runTest {
        client.removeFromContinueWatching("4")
        val request = server.takeRequest()
        assertEquals("PUT", request.method)
        assertEquals("/actions/removeFromContinueWatching", request.requestUrl!!.encodedPath)
        assertEquals("4", request.requestUrl!!.queryParameter("ratingKey"))
    }

    @Test
    fun `direct play opens the file, with the token in headers, not in the address`() {
        val stream = client.directPlay(PlexPart(key = "/library/parts/1/1792875002/file.mp4"))
        val url = stream.url.toHttpUrl()
        assertEquals("/library/parts/1/1792875002/file.mp4", url.encodedPath)
        assertNull(url.queryParameter("X-Plex-Token"))
        assertEquals("secret-token", stream.headers["X-Plex-Token"])
        assertEquals("client-1", stream.headers["X-Plex-Client-Identifier"])
    }

    @Test
    fun `direct stream copies audio and video, after the server's decision`() = runTest {
        val stream = client.directStream("1", "session-2", offsetMs = 125_500)
        val decision = server.takeRequest()
        // The server refuses the stream unless it has decided for the session first.
        assertEquals("/video/:/transcode/universal/decision", decision.requestUrl!!.encodedPath)
        // Plex's empty profile plus the app's codecs: the Android profile would re-encode (1080p, stereo AAC).
        assertEquals("Generic", decision.getHeader("X-Plex-Client-Profile-Name"))
        assertTrue(decision.getHeader("X-Plex-Client-Profile-Extra")!!.contains("videoCodec=h264,hevc"))

        val url = stream.url.toHttpUrl()
        assertEquals("/video/:/transcode/universal/start.m3u8", url.encodedPath)
        assertEquals(decision.requestUrl!!.query, url.query)
        assertEquals("/library/metadata/1", url.queryParameter("path"))
        assertEquals("0", url.queryParameter("directPlay"))
        assertEquals("1", url.queryParameter("directStream"))
        assertEquals("none", url.queryParameter("subtitles"))
        assertEquals("125", url.queryParameter("offset"))
        assertEquals("session-2", url.queryParameter("session"))
        assertNull(url.queryParameter("X-Plex-Token"))
        // The player sends these on the playlists and every segment.
        assertEquals("secret-token", stream.headers["X-Plex-Token"])
        assertEquals("session-2", stream.headers["X-Plex-Session-Identifier"])
        assertEquals("Generic", stream.headers["X-Plex-Client-Profile-Name"])
    }

    @Test
    fun `a file that would need re-encoding isn't streamed`() = runTest {
        responses["video/:/transcode/universal/decision"] = MockResponse().setBody(
            """{"MediaContainer":{"generalDecisionCode":1001,"Metadata":[{"ratingKey":"1","Media":[{"Part":[{"Stream":[""" +
                """{"streamType":1,"codec":"hevc","decision":"transcode"},{"streamType":2,"codec":"aac","decision":"copy"}]}]}]}]}}"""
        )
        val e = expectFailure(PlexException.Reason.NEEDS_TRANSCODE) { client.directStream("1", "session-3") }
        assertTrue(e.message!!, e.message!!.contains("hevc: transcode"))

        responses["video/:/transcode/universal/decision"] = MockResponse().setBody(
            """{"MediaContainer":{"generalDecisionCode":2000,"generalDecisionText":"Neither direct play nor conversion is available."}}"""
        )
        val refused = expectFailure(PlexException.Reason.CANNOT_STREAM) { client.directStream("1", "session-4") }
        assertTrue(refused.message!!, refused.message!!.contains("Neither direct play"))

        // Video it can't copy is dropped ("ignore"), leaving sound alone: refused too.
        responses["video/:/transcode/universal/decision"] = MockResponse().setBody(
            """{"MediaContainer":{"generalDecisionCode":1001,"Metadata":[{"ratingKey":"1","Media":[{"Part":[{"Stream":[""" +
                """{"streamType":1,"codec":"h264","decision":"ignore"},{"streamType":2,"codec":"aac","decision":"copy"}]}]}]}]}}"""
        )
        expectFailure(PlexException.Reason.NEEDS_TRANSCODE) { client.directStream("1", "session-5") }

        // Subtitles burnt into the picture would mean re-encoding the video: refused too.
        responses["video/:/transcode/universal/decision"] = MockResponse().setBody(
            """{"MediaContainer":{"generalDecisionCode":1001,"Metadata":[{"ratingKey":"1","Media":[{"Part":[{"Stream":[""" +
                """{"streamType":1,"codec":"h264","decision":"copy"},{"streamType":2,"codec":"aac","decision":"copy"},""" +
                """{"streamType":3,"codec":"pgs","decision":"burn"}]}]}]}]}}"""
        )
        expectFailure(PlexException.Reason.NEEDS_TRANSCODE) { client.directStream("1", "session-6") }

        // An answer with no streams at all isn't taken for one that needs re-encoding.
        responses["video/:/transcode/universal/decision"] = MockResponse().setBody("""{"MediaContainer":{"generalDecisionCode":1001}}""")
        expectFailure(PlexException.Reason.UNEXPECTED_RESPONSE) { client.directStream("1", "session-7") }

        // Each refusal closed the session its decision opened, and never asked for the stream.
        val paths = List(server.requestCount) { server.takeRequest().requestUrl!!.encodedPath }
        assertEquals(List(5) { listOf("/video/:/transcode/universal/decision", "/video/:/transcode/universal/stop") }.flatten(), paths)
    }

    @Test
    fun `a device name that isn't plain ASCII is sent as it is`() = runTest {
        val tv = PlexClient(OkHttpClient(), PlexServer(server.url("/").toString(), "t"), device.copy(deviceName = "Télé du salon"))
        tv.identity()
        assertEquals("Télé du salon", server.takeRequest().getHeader("X-Plex-Device-Name"))
    }

    @Test
    fun `a stream's token stays out of its text`() {
        val text = client.directPlay(PlexPart(key = "/library/parts/1/2/file.mp4")).toString()
        assertFalse(text, text.contains("secret-token"))
    }

    @Test
    fun `images carry the token in the address, for the image loader`() {
        val image = client.imageUrl("/library/metadata/1/thumb/1792874999", 240, 360).toHttpUrl()
        assertEquals("/photo/:/transcode", image.encodedPath)
        assertEquals("/library/metadata/1/thumb/1792874999", image.queryParameter("url"))
        assertEquals("240", image.queryParameter("width"))
        assertEquals("secret-token", image.queryParameter("X-Plex-Token"))
    }

    @Test
    fun `a server that needs no token gets none`() {
        val open = PlexClient(OkHttpClient(), PlexServer(server.url("/").toString(), null), device)
        assertNull(open.directPlay(PlexPart(key = "/library/parts/1/2/file.mp4")).headers["X-Plex-Token"])
        assertNull(open.imageUrl("/thumb", 1, 1).toHttpUrl().queryParameter("X-Plex-Token"))
    }

    @Test
    fun `failures say why`() = runTest {
        otherwise = MockResponse().setResponseCode(401)
        expectFailure(PlexException.Reason.UNAUTHORIZED) { client.item("99") }
        otherwise = MockResponse().setResponseCode(500)
        assertEquals(500, expectFailure(PlexException.Reason.SERVER_ERROR) { client.item("99") }.code)
        otherwise = MockResponse().setBody("<html>not json</html>")
        expectFailure(PlexException.Reason.UNEXPECTED_RESPONSE) { client.item("99") }
        otherwise = MockResponse().setBody("""{"MediaContainer":{"size":0}}""")
        expectFailure(PlexException.Reason.UNEXPECTED_RESPONSE) { client.item("99") }
        // A large page cut off part-way, as the body is read while it arrives.
        otherwise = MockResponse().setBody("""{"MediaContainer":{"size":1,"Metadata":[{"ratingKey":"1","title":"""" + "x".repeat(1 shl 16))
            .setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY)
        expectFailure(PlexException.Reason.UNREACHABLE) { client.page("9", PlexType.MOVIE, 0, 1) }

        val port = server.port
        server.shutdown()
        val gone = PlexClient(OkHttpClient(), PlexServer("http://127.0.0.1:$port", "t"), device)
        expectFailure(PlexException.Reason.UNREACHABLE) { gone.sections() }
        expectFailure(PlexException.Reason.INVALID_ADDRESS) { PlexClient(OkHttpClient(), PlexServer("not an address", "t"), device) }
    }

    private suspend fun expectFailure(reason: PlexException.Reason, block: suspend () -> Unit): PlexException {
        val thrown = try {
            block()
            null
        } catch (e: PlexException) {
            e
        }
        assertEquals("expected a PlexException", reason, thrown?.reason)
        return thrown!!
    }
}
