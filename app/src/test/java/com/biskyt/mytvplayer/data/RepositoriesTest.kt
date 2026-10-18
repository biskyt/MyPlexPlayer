package com.biskyt.mytvplayer.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.biskyt.mytvplayer.core.xtream.XtreamException
import com.biskyt.mytvplayer.data.db.AppDatabase
import com.biskyt.mytvplayer.data.db.CAT_LIVE
import com.biskyt.mytvplayer.data.db.CAT_MOVIE
import com.biskyt.mytvplayer.data.db.CAT_SERIES
import com.biskyt.mytvplayer.data.db.VOD_EPISODE
import com.biskyt.mytvplayer.data.db.VOD_MOVIE
import com.biskyt.mytvplayer.data.db.WatchProgressEntity
import com.biskyt.mytvplayer.testutil.FakeProvider
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class RepositoriesTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val provider = FakeProvider()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var db: AppDatabase
    private lateinit var settings: SettingsRepository
    private lateinit var epg: EpgRepository
    private lateinit var playlists: PlaylistRepository
    private lateinit var channels: ChannelRepository
    private lateinit var vod: VodRepository

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        val http = OkHttpClient()
        settings = SettingsRepository(context)
        epg = EpgRepository(db, http, context.cacheDir.resolve("test-epg"), scope)
        val categoryStore = CategoryStore(db) { Locale.UK }
        playlists = PlaylistRepository(db, http, settings, epg, context.cacheDir.resolve("test-playlists"), categoryStore)
        channels = ChannelRepository(db)
        vod = VodRepository(db, playlists, categoryStore)
    }

    @After
    fun tearDown() {
        scope.cancel()
        db.close()
        provider.close()
    }

    private suspend fun addPlaylist(): Long = playlists.add("Home", provider.url, "me", "secret")

    @Test
    fun `wrong password is rejected and nothing is saved`() = runBlocking {
        try {
            playlists.add("Home", provider.url, "me", "wrong")
            fail("expected login failure")
        } catch (_: XtreamException) {
        }
        assertTrue(playlists.allIds().isEmpty())
    }

    @Test
    fun `new playlist includes the TV's own language groups and becomes active`() = runBlocking {
        val id = addPlaylist()
        val cats = db.categories().all(id, CAT_LIVE).associateBy { it.categoryId }
        assertTrue(cats.getValue("1").included)
        assertTrue(cats.getValue("2").included)
        assertFalse("French category should be excluded for a UK TV", cats.getValue("3").included)
        assertFalse(cats.getValue("4").included)
        assertEquals("FR", cats.getValue("3").lang)
        assertEquals(id, settings.current().activePlaylistId)
    }

    @Test
    fun `a TV whose country the provider lacks falls back to its language`() = runBlocking {
        val us =
            PlaylistRepository(
                db,
                OkHttpClient(),
                settings,
                epg,
                context.cacheDir.resolve("test-playlists"),
                CategoryStore(db) {
                    Locale.US
                }
            )
        val id = us.add("Home", provider.url, "me", "secret")
        val included = db.categories().all(id, CAT_LIVE).filter { it.included }.map { it.categoryId }
        assertEquals(listOf("1", "2"), included)
    }

    @Test
    fun `m3u playlist loads the TV's country and keeps ids stable across refreshes`() = runBlocking {
        val id = playlists.addM3u("Free", provider.url + "free.m3u")
        val cats = db.categories().all(id, CAT_LIVE).associateBy { it.categoryId }
        assertTrue(cats.getValue("United Kingdom").included)
        assertFalse(cats.getValue("France").included)
        assertEquals("UK", cats.getValue("United Kingdom").lang)

        playlists.syncChannels(id)
        val ids = channels.ids(id, ChannelFilter.All)
        assertEquals(2, ids.size)
        val kite = channels.get(id, ids[1])!!
        assertEquals("Kite Kids (720p)", kite.name)
        assertEquals("http://stream.example/kite.m3u8", kite.streamUrl)
        assertEquals("Agent/2", kite.userAgent)
        assertEquals("KiteKids.uk", kite.providerEpgId)

        // The provider changes a stream URL; the channel keeps its id (and so its favourite).
        channels.setFavourite(id, kite.streamId, true)
        provider.m3u = provider.m3u.replace("kite.m3u8", "kite-new.m3u8")
        playlists.syncChannels(id)
        assertEquals("http://stream.example/kite-new.m3u8", channels.get(id, kite.streamId)!!.streamUrl)
        assertEquals(listOf(kite.streamId), channels.ids(id, ChannelFilter.Favourites))
    }

    @Test
    fun `an address that isn't a playlist is rejected`() = runBlocking {
        try {
            playlists.addM3u("Bad", provider.url + "not-a-playlist")
            fail("expected rejection")
        } catch (_: XtreamException) {
        }
        assertTrue(playlists.allIds().isEmpty())
    }

    @Test
    fun `sync only downloads included categories and keeps user choices`() = runBlocking {
        val id = addPlaylist()
        playlists.syncChannels(id)
        assertEquals(listOf(101L, 102L, 103L), channels.ids(id, ChannelFilter.All))
        // Few categories included out of many → fetched one category at a time.
        assertTrue(provider.requests.any { it.endsWith("get_live_streams") })

        channels.setHidden(id, 103, true)
        channels.setFavourite(id, 101, true)
        channels.move(id, ChannelFilter.All, 102, -1)
        assertEquals(listOf(102L, 101L), channels.ids(id, ChannelFilter.All))

        // Provider renames a channel and drops another; user choices survive.
        provider.streams = provider.streams.filter { it.id != 103L }.map { if (it.id == 101L) it.copy(name = "UK: CHANNEL 1") else it }
        playlists.syncChannels(id)
        assertEquals(listOf(102L, 101L), channels.ids(id, ChannelFilter.All))
        assertEquals("UK: CHANNEL 1", channels.get(id, 101)!!.name)
        assertNull(channels.get(id, 103))
        assertEquals(listOf(101L), channels.ids(id, ChannelFilter.Favourites))
    }

    @Test
    fun `excluding a category removes its channels on the next sync`() = runBlocking {
        val id = addPlaylist()
        playlists.syncChannels(id)
        db.categories().setIncluded(id, CAT_LIVE, listOf("2"), false)
        db.categories().setIncluded(id, CAT_LIVE, listOf("3"), true)
        playlists.syncChannels(id)
        assertEquals(setOf(101L, 103L, 201L), channels.ids(id, ChannelFilter.All).toSet())
        assertEquals(listOf(201L), channels.ids(id, ChannelFilter.Category("3")))
    }

    @Test
    fun `guide import matches channels and stores programmes only for used guide channels`() = runBlocking {
        val id = addPlaylist()
        playlists.syncChannels(id)
        epg.ensureProviderSource(playlists.get(id)!!, playlists.client(playlists.get(id)!!).xmltvUrl())
        epg.importAll(listOf(id))

        val now = System.currentTimeMillis()
        val programmes = epg.programmes(id, listOf(101, 102, 103), now, now + 3 * 3_600_000)
        // 101 matches automatically via its provider epg id.
        assertEquals("Now on one.uk", programmes.getValue(101).first().title)
        assertEquals("Next on one.uk", programmes.getValue(101)[1].title)
        // Misspelt names aren't matched automatically (too risky) but are the top suggestion for the user to confirm.
        assertNull(programmes[102])
        assertEquals("arena1.uk", epg.suggestions("UK: ARENA SPRTS 1 FHD", null).first().candidate.xmlId)
        assertEquals("kite.uk", epg.suggestions("UK: KITE KIDZ", null).first().candidate.xmlId)

        // cine.fr is in the guide but no included channel uses it, so its programmes aren't stored.
        val stored = db.query("SELECT COUNT(*) FROM programme WHERE xmlId = 'cine.fr'", null).use {
            it.moveToFirst()
            it.getInt(0)
        }
        assertEquals(0, stored)
    }

    @Test
    fun `manual guide choice wins over automatic matching and survives re-import`() = runBlocking {
        val id = addPlaylist()
        playlists.syncChannels(id)
        val p = playlists.get(id)!!
        epg.ensureProviderSource(p, playlists.client(p).xmltvUrl())
        epg.importAll(listOf(id))
        val source = db.epg().enabledSources().first().id

        epg.setManual(id, 103, source, "cine.fr", offsetMinutes = 60)
        delay(2_500) // debounced programme import
        val now = System.currentTimeMillis()
        val list = epg.programmes(id, listOf(103), now - 4 * 3_600_000, now + 4 * 3_600_000).getValue(103)
        assertEquals("Now on cine.fr", list.first().title)
        // The guide has it starting 30 min ago; the +1 h time shift moves it to 30 min from now.
        assertTrue(kotlin.math.abs(list.first().startMs - (now + 30 * 60_000)) < 120_000)

        epg.importAll(listOf(id))
        assertEquals("cine.fr", db.epg().mapping(id, 103)!!.xmlId)

        epg.setNoGuide(id, 102)
        epg.importAll(listOf(id))
        assertNull(epg.programmes(id, listOf(102), now, now + 3_600_000)[102])
    }

    @Test
    fun `deleting a playlist removes its data and guide`() = runBlocking {
        val id = addPlaylist()
        playlists.syncChannels(id)
        val p = playlists.get(id)!!
        epg.ensureProviderSource(p, playlists.client(p).xmltvUrl())
        playlists.delete(id)
        assertTrue(channels.ids(id, ChannelFilter.All).isEmpty())
        assertTrue(db.epg().enabledSources().isEmpty())
        assertEquals(0L, settings.current().activePlaylistId)
    }

    @Test
    fun `movies and series sync for the TV's language, with clean titles`() = runBlocking {
        val id = addPlaylist()
        val p = playlists.get(id)!!
        vod.sync(p)
        val movies = db.vod().moviesSnapshot(id)
        // Untagged categories ("ACTION") count as the provider's own language, so they're included too.
        assertEquals(listOf("Dune" to 2021, "Paddington 2" to 2017, "Heat" to 1995), movies.map { it.title to it.year })
        assertEquals("10", movies.first().categoryId)
        assertEquals(listOf("Line of Duty"), db.vod().seriesSnapshot(id).map { it.title })
        // Categories of each kind are kept apart, and live categories are untouched.
        assertEquals(listOf("10", "11", "12"), db.categories().all(id, CAT_MOVIE).map { it.categoryId })
        assertEquals(2, db.categories().all(id, CAT_MOVIE).first().channelCount)
        assertEquals(4, db.categories().all(id, CAT_LIVE).size)

        // Including French movies brings Amélie in on the next sync; removing a movie at the provider drops it.
        db.categories().setIncluded(id, CAT_MOVIE, listOf("11"), true)
        provider.movies = provider.movies.filter { it.first != 502L }
        vod.sync(p)
        assertEquals(setOf("Dune", "Amélie", "Heat"), db.vod().moviesSnapshot(id).map { it.title }.toSet())

        // A movie list with nothing included (as stored by 0.3.0, which ignored untagged categories) gets its defaults again.
        db.categories().setIncluded(id, CAT_MOVIE, listOf("10", "11", "12"), false)
        vod.sync(p)
        assertEquals(listOf("10", "12"), db.categories().all(id, CAT_MOVIE).filter { it.included }.map { it.categoryId })
    }

    @Test
    fun `details, favourites and carry on watching`() = runBlocking {
        val id = addPlaylist()
        val p = playlists.get(id)!!
        vod.sync(p)
        assertEquals(6000, vod.movieDetails(p, 501).durationSec)
        val show = vod.seriesDetails(p, 77)
        assertEquals(listOf(1, 2), show.seasons.map { it.number })
        assertEquals(3, show.episodes.size)

        vod.setFavourite(id, CAT_SERIES, 77, true)
        assertTrue(vod.observeFavourite(id, CAT_SERIES, 77).first())

        fun progress(kind: String, item: Long, pos: Long, at: Long, seriesId: Long? = null, hasNext: Boolean = false) =
            WatchProgressEntity(id, kind, item, pos, 3_600_000, at, "t", null, seriesId = seriesId, hasNext = hasNext)
        vod.saveProgress(progress(VOD_MOVIE, 501, 600_000, 1))
        vod.saveProgress(progress(VOD_EPISODE, 9001, 3_500_000, 2, seriesId = 77)) // finished
        vod.saveProgress(progress(VOD_EPISODE, 9002, 60_000, 3, seriesId = 77))
        vod.saveProgress(progress(VOD_MOVIE, 502, 3_400_000, 4)) // finished
        // Newest first; one row per series (its latest episode); finished things drop off.
        assertEquals(listOf(9002L, 501L), vod.observeCarryOn(id).first().map { it.itemId })
        // A finished episode that has a next one stays, so the series carries on.
        vod.saveProgress(progress(VOD_EPISODE, 9002, 3_500_000, 5, seriesId = 77, hasNext = true))
        assertEquals(listOf(9002L, 501L), vod.observeCarryOn(id).first().map { it.itemId })
        vod.saveProgress(progress(VOD_EPISODE, 9003, 3_500_000, 6, seriesId = 77), trim = true) // the last episode
        assertEquals(listOf(501L), vod.observeCarryOn(id).first().map { it.itemId })
    }

    @Test
    fun `search finds channels, programmes, movies and series`() = runBlocking {
        val id = addPlaylist()
        playlists.syncChannels(id)
        playlists.get(id)?.let { epg.ensureProviderSource(it, playlists.client(it).xmltvUrl()) }
        epg.importAll(listOf(id))
        vod.sync(playlists.get(id)!!)
        val search = SearchRepository(db)
        suspend fun find(q: String) = search.search(id, q)!!

        assertNull(search.search(id, "a")) // one letter: too broad to search
        assertEquals(listOf(102L), find("2").channels.map { it.streamId }) // one digit: channel number only
        assertEquals(listOf("UK: KITE KIDZ"), find("kite").channels.map { it.name })
        val news = find("on one.uk")
        assertEquals(listOf("Now on one.uk", "Next on one.uk"), news.programmes.map { it.title })
        assertEquals("UK: CHANNEL ONE HD", news.programmes.first().channelName)
        assertTrue(find("on").programmes.isEmpty()) // the guide needs 3+ letters
        assertEquals(listOf("Paddington 2"), find("padd").movies.map { it.title })
        assertEquals(listOf("Line of Duty"), find("duty").series.map { it.title })
        assertTrue(find("zzz").isEmpty)
        assertTrue(find("%%").isEmpty) // typed literally, not as wildcards
        assertTrue(find("__").isEmpty)
    }
}
