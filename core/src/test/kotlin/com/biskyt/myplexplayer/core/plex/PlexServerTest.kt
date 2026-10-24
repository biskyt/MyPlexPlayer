package com.biskyt.myplexplayer.core.plex

import java.util.UUID
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * [PlexClient] against a real Plex Media Server: the one `.github/scripts/start-plex.sh` starts (a film and a
 * two-episode series, 10 minutes each, H.264 and AAC, no token). Run by `./gradlew :core:plexServerTest`, which
 * passes the server's address as `plex.server`; the plain `test` task leaves this class out.
 */
class PlexServerTest {
    private val http = OkHttpClient()
    private val client = PlexClient(
        http,
        PlexServer(requireNotNull(System.getProperty("plex.server")) { "plex.server isn't set" }, null),
        PlexDevice(clientId = "plex-server-test", product = "MyPlexPlayer", version = "test"),
    )

    private suspend fun section(type: String) = client.sections().single { it.type == type }.key

    private suspend fun movie() = client.item(client.recentlyAdded(section("movie"), PlexType.MOVIE, 1).single().ratingKey)

    @Test
    fun `libraries, home rows and search`() = runBlocking {
        assertTrue(client.identity().isNotBlank())
        val movie = client.recentlyAdded(section("movie"), PlexType.MOVIE, 20).single()
        val show = client.recentlyAdded(section("show"), PlexType.SHOW, 20).single()
        assertEquals("Test Show", show.title)
        assertNotNull(client.item(movie.ratingKey).part)
        val season = client.children(show.ratingKey).single()
        assertEquals(listOf(1, 2), client.children(season.ratingKey).map { it.index })
        assertEquals(2, client.episodes(show.ratingKey).size)
        assertTrue(client.search("Test").any { it.ratingKey == show.ratingKey })
    }

    @Test
    fun `progress, watched by the server, and unwatched`() = runBlocking {
        val episode = client.episodes(client.recentlyAdded(section("show"), PlexType.SHOW, 1).single().ratingKey).last()
        val duration = episode.duration!!
        val session = UUID.randomUUID().toString()
        client.timeline(episode, PlexPlayState.PLAYING, 120_000, session)
        client.timeline(episode, PlexPlayState.STOPPED, 120_000, session)
        assertEquals(120_000L, client.item(episode.ratingKey).viewOffset)
        assertTrue(client.continueWatching().any { it.ratingKey == episode.ratingKey })

        // Played on past 90% in one session, the server marks it watched itself.
        val second = UUID.randomUUID().toString()
        listOf(0.5, 0.7, 0.93).forEach { client.timeline(episode, PlexPlayState.PLAYING, (duration * it).toLong(), second) }
        client.timeline(episode, PlexPlayState.STOPPED, (duration * 0.93).toLong(), second)
        assertEquals(1, client.item(episode.ratingKey).viewCount)

        client.setWatched(episode.ratingKey, watched = false)
        client.item(episode.ratingKey).let {
            assertNull(it.viewCount)
            assertNull(it.viewOffset)
        }
    }

    @Test
    fun `direct play serves the file in ranges`() = runBlocking {
        fetch(client.directPlay(movie().part!!), "bytes=0-1023").use {
            assertEquals(206, it.code)
            assertEquals("bytes", it.header("Accept-Ranges"))
        }
    }

    @Test
    fun `direct stream repackages without re-encoding, down to the segments`() = runBlocking {
        val session = UUID.randomUUID().toString()
        val stream = client.directStream(movie().ratingKey, session, offsetMs = 60_000)
        try {
            // As the player follows it: the master playlist, its variant, then a segment, each with the headers.
            val master = fetch(stream).use { it.body!!.string() }
            assertTrue(master, master.startsWith("#EXTM3U"))
            val variant = stream.url.toHttpUrl().resolve(master.lines().first { it.isNotBlank() && !it.startsWith("#") })!!
            val media = fetch(stream.copy(url = variant.toString())).use { it.body!!.string() }
            val segment = variant.resolve(media.lines().first { it.isNotBlank() && !it.startsWith("#") })!!
            fetch(stream.copy(url = segment.toString())).use {
                assertEquals(200, it.code)
                assertTrue("an MPEG-TS segment", it.body!!.bytes().first() == TS_SYNC)
            }
        } finally {
            client.stopStream(session)
        }
    }

    @Test
    fun `a file the app's codecs don't cover is refused, not re-encoded`() = runBlocking {
        try {
            client.directStream(movie().ratingKey, UUID.randomUUID().toString(), codecs = PlexCodecs(video = listOf("hevc")))
            fail("an H.264 film with only HEVC allowed should need re-encoding")
        } catch (e: PlexException) {
            assertEquals(PlexException.Reason.NEEDS_TRANSCODE, e.reason)
        }
    }

    @Test
    fun `images are scaled on the server`() = runBlocking {
        val request = Request.Builder().url(client.imageUrl(movie().thumb!!, 240, 360)).build()
        http.newCall(request).execute().use {
            assertEquals(200, it.code)
            assertTrue(it.header("Content-Type")!!.startsWith("image/"))
        }
    }

    /** As the player fetches: [stream]'s address with its headers. */
    private fun fetch(stream: PlexStream, range: String? = null): Response = http.newCall(
        Request.Builder().url(stream.url).apply {
            stream.headers.forEach { (name, value) -> header(name, value) }
            if (range != null) header("Range", range)
        }.build()
    ).execute()

    private companion object {
        const val TS_SYNC: Byte = 0x47
    }
}
