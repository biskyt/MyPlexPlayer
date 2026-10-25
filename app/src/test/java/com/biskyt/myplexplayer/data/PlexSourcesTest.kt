package com.biskyt.myplexplayer.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.biskyt.myplexplayer.core.plex.PlexException
import com.biskyt.myplexplayer.data.db.AppDatabase
import com.biskyt.myplexplayer.data.db.CAT_MOVIE
import com.biskyt.myplexplayer.data.db.CAT_SERIES
import com.biskyt.myplexplayer.data.db.KIND_PLEX
import com.biskyt.myplexplayer.testutil.FakePlex
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Plex servers as sources: signing in, adding the account's servers, and their films and series. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = com.biskyt.myplexplayer.testutil.PlainApp::class)
class PlexSourcesTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val fake = FakePlex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var db: AppDatabase
    private lateinit var settings: SettingsRepository
    private lateinit var plex: PlexSources
    private lateinit var playlists: PlaylistRepository
    private lateinit var vod: VodRepository

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        val http = OkHttpClient()
        settings = SettingsRepository(context)
        val categoryStore = CategoryStore(db) { Locale.UK }
        val epg = EpgRepository(db, http, context.cacheDir.resolve("test-epg"), scope)
        playlists = PlaylistRepository(db, http, settings, epg, categoryStore)
        plex = PlexSources(db, http, settings).apply { plexTv = fake.url }
        vod = VodRepository(db, playlists, categoryStore, plex)
    }

    @After
    fun tearDown() {
        scope.cancel()
        db.close()
        fake.close()
    }

    private suspend fun homePlex() = db.playlists().all().single { it.machineId == FakePlex.MACHINE_ID }

    @Test
    fun `a code to enter at plex dot tv slash link, then the token once it's entered`() = runBlocking {
        val pin = plex.createPin()
        assertEquals("ABCD", pin.code)
        assertNull(plex.pin(pin.id).authToken)
        fake.claimed = true
        assertEquals(FakePlex.ACCOUNT_TOKEN, plex.pin(pin.id).authToken)
    }

    @Test
    fun `signing in adds each server that answers, and the first is used`() = runBlocking {
        val added = plex.signIn(FakePlex.ACCOUNT_TOKEN)
        val source = homePlex()
        assertEquals(PlexSources.Added(listOf(source.id), listOf("Friend's Plex")), added)
        assertEquals(KIND_PLEX, source.kind)
        assertEquals("Home Plex", source.name)
        assertEquals(fake.url, source.server)
        assertEquals("the server's own token, not the account's", FakePlex.SERVER_TOKEN, source.password)
        assertEquals(source.id, settings.current().activePlaylistId)
        assertEquals(FakePlex.ACCOUNT_TOKEN, settings.plexToken())

        // Again (from Settings): the same source, not a second one.
        plex.addServers(FakePlex.ACCOUNT_TOKEN)
        assertEquals(listOf(source.id), playlists.allIds())
    }

    @Test
    fun `a token plex dot tv no longer accepts says so`() = runBlocking {
        fake.tokenAccepted = false
        try {
            plex.addServers(FakePlex.ACCOUNT_TOKEN)
            fail("expected the token to be refused")
        } catch (e: PlexException) {
            assertEquals(PlexException.Reason.UNAUTHORIZED, e.reason)
        }
        assertTrue(playlists.allIds().isEmpty())
    }

    @Test
    fun `each library is a category, with its films or series`() = runBlocking {
        plex.signIn(FakePlex.ACCOUNT_TOKEN)
        val source = homePlex()
        playlists.syncChannels(source.id)
        vod.sync(source)

        assertEquals(listOf("Films"), db.categories().all(source.id, CAT_MOVIE).map { it.name })
        assertEquals(listOf("TV Programmes"), db.categories().all(source.id, CAT_SERIES).map { it.name })
        val movies = db.vod().moviesSnapshot(source.id)
        assertEquals(listOf("Big Buck Bunny", "Sintel"), movies.map { it.title })
        assertEquals(listOf(11L, 12L), movies.map { it.streamId })
        assertEquals(2008, movies[0].year)
        val poster = movies[0].posterUrl!!.toHttpUrl()
        assertEquals("/library/metadata/11/thumb/1", poster.queryParameter("url"))
        assertEquals("the image loader sends no headers", FakePlex.SERVER_TOKEN, poster.queryParameter("X-Plex-Token"))
        assertNull(movies[1].posterUrl)
        assertEquals(listOf("Test Show"), db.vod().seriesSnapshot(source.id).map { it.title })
        assertTrue("the source counts as up to date", !playlists.get(source.id)!!.isStale())
    }

    @Test
    fun `a server that fails while loading says so, and keeps what it had`() = runBlocking {
        plex.signIn(FakePlex.ACCOUNT_TOKEN)
        val source = homePlex()
        vod.sync(source)
        fake.libraryUp = false
        try {
            vod.sync(source)
            fail("expected the failure to show")
        } catch (e: PlexException) {
            assertEquals(PlexException.Reason.SERVER_ERROR, e.reason)
        }
        assertEquals(2, db.vod().moviesSnapshot(source.id).size)
    }

    @Test
    fun `every library is included whatever its name, and one deleted on the server goes`() = runBlocking {
        fake.filmLibrary = "FR: Films" // on a UK device, a library named as French isn't left out
        plex.signIn(FakePlex.ACCOUNT_TOKEN)
        val source = homePlex()
        vod.sync(source)
        assertEquals(listOf(true), db.categories().all(source.id, CAT_MOVIE).map { it.included })
        assertEquals(2, db.vod().moviesSnapshot(source.id).size)

        fake.filmLibrary = null
        vod.sync(source)
        assertTrue(db.categories().all(source.id, CAT_MOVIE).isEmpty())
        assertTrue(db.vod().moviesSnapshot(source.id).isEmpty())
        assertEquals(1, db.vod().seriesSnapshot(source.id).size)
    }

    @Test
    fun `a server no longer at its address is found again`() = runBlocking {
        plex.signIn(FakePlex.ACCOUNT_TOKEN)
        val moved = MockWebServer().apply {
            repeat(4) { enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START)) }
            start()
        }
        try {
            val old = homePlex().copy(server = moved.url("/").toString().trimEnd('/'))
            db.playlists().upsert(old)
            vod.sync(old)
            assertEquals(fake.url, homePlex().server)
            assertEquals(2, db.vod().moviesSnapshot(old.id).size)
        } finally {
            moved.shutdown()
        }
    }

    @Test
    fun `details, episodes and where they play from come from the server`() = runBlocking {
        plex.signIn(FakePlex.ACCOUNT_TOKEN)
        val source = homePlex()
        vod.sync(source)

        val film = vod.movieDetails(source, 11)
        assertEquals("A giant rabbit takes on three bullies.", film.plot)
        assertEquals(596, film.durationSec)
        assertTrue(film.backdropUrl!!.contains("art"))

        val show = vod.seriesDetails(source, 21)
        assertEquals("Two episodes of colour bars.", show.info.plot)
        assertEquals(listOf(1), show.seasons.map { it.number })
        assertEquals(listOf("Pilot" to 1, "Second" to 2), show.episodes.map { it.title to it.number })

        val movie = db.vod().movie(source.id, 11)!!
        val url = vod.movieStream(source, movie).url.toHttpUrl()
        assertEquals("the file as it is", "/library/parts/111/1/file.mp4", url.encodedPath)
        assertEquals(FakePlex.SERVER_TOKEN, url.queryParameter("X-Plex-Token"))
        assertEquals("/library/parts/2102/1/file.mkv", vod.episodeStream(source, 212, "mkv").url.toHttpUrl().encodedPath)
    }
}
