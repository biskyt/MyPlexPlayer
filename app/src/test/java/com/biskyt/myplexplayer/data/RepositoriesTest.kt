package com.biskyt.myplexplayer.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.biskyt.myplexplayer.core.epg.ProgrammeKind
import com.biskyt.myplexplayer.data.db.AppDatabase
import com.biskyt.myplexplayer.data.db.CAT_MOVIE
import com.biskyt.myplexplayer.data.db.CAT_SERIES
import com.biskyt.myplexplayer.data.db.PlaylistEntity
import com.biskyt.myplexplayer.data.db.VOD_EPISODE
import com.biskyt.myplexplayer.data.db.VOD_MOVIE
import com.biskyt.myplexplayer.data.db.WatchProgressEntity
import com.biskyt.myplexplayer.testutil.FakePlex
import com.biskyt.myplexplayer.testutil.PlainApp
import com.biskyt.myplexplayer.testutil.plexChannel
import com.biskyt.myplexplayer.testutil.signInHome
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = PlainApp::class)
class RepositoriesTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val fake = FakePlex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var db: AppDatabase
    private lateinit var settings: SettingsRepository
    private lateinit var epg: EpgRepository
    private lateinit var playlists: PlaylistRepository
    private lateinit var channels: ChannelRepository
    private lateinit var vod: VodRepository
    private lateinit var plex: PlexSources

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        val http = OkHttpClient()
        settings = SettingsRepository(context)
        epg = EpgRepository(db)
        val categoryStore = CategoryStore(db)
        plex = PlexSources(db, http, settings).apply {
            plexTv = fake.url
            plexGuide = fake.url
        }
        playlists = PlaylistRepository(db, settings, epg, categoryStore, plex)
        channels = ChannelRepository(db, plex, Notices())
        vod = VodRepository(db, playlists, categoryStore, plex, Notices())
    }

    @After
    fun tearDown() {
        scope.cancel()
        db.close()
        fake.close()
    }

    /** Signs in to [fake] and adds Home Plex: its source's id. */
    private suspend fun addHomePlex(): Long {
        return plex.signInHome(db)
    }

    /** Three tuner channels, One, Two and Three, in place of FakePlex's two. */
    private fun threeChannels() {
        fake.channels = listOf(FakePlex.Channel("1", "One"), FakePlex.Channel("2", "Two"), FakePlex.Channel("3", "Three"))
    }

    @Test
    fun `a sync keeps user choices on a Plex server's channels`() = runBlocking {
        threeChannels()
        val id = addHomePlex()
        playlists.syncChannels(id)
        assertEquals(listOf(plexChannel("1"), plexChannel("2"), plexChannel("3")), channels.ids(id, ChannelFilter.All))

        channels.setHidden(id, plexChannel("3"), true)
        channels.setFavourite(id, plexChannel("1"), true)
        db.channels().setSortOrder(id, plexChannel("2"), 0)
        assertEquals(listOf(plexChannel("2"), plexChannel("1")), channels.ids(id, ChannelFilter.All))

        // The server renames a channel and drops another; user choices survive.
        fake.channels = fake.channels.filter { it.number != "3" }.map { if (it.number == "1") it.copy(title = "One HD") else it }
        playlists.syncChannels(id)
        assertEquals(listOf(plexChannel("2"), plexChannel("1")), channels.ids(id, ChannelFilter.All))
        assertEquals("One HD", channels.get(id, plexChannel("1"))!!.name)
        assertNull(channels.get(id, plexChannel("3")))
        assertEquals(listOf(plexChannel("1")), channels.ids(id, ChannelFilter.Favourites))
    }

    private fun now() = System.currentTimeMillis()

    @Test
    fun `deleting a playlist removes its data and guide`() = runBlocking {
        val id = addHomePlex()
        playlists.syncChannels(id)
        vod.sync(playlists.get(id)!!)
        assertNotNull(db.epg().providerSource(id)) // the server's own guide
        playlists.delete(id)
        assertTrue(channels.ids(id, ChannelFilter.All).isEmpty())
        assertTrue(db.vod().moviesSnapshot(id).isEmpty())
        assertNull(db.epg().providerSource(id))
        assertEquals(0L, settings.current().activePlaylistId)
    }

    @Test
    fun `details, favourites and carry on watching`() = runBlocking {
        fake.episodes = listOf(
            FakePlex.episode("21", "Test Show", "211", 1, 1, "Pilot"),
            FakePlex.episode("21", "Test Show", "212", 1, 2, "Second"),
            FakePlex.episode("21", "Test Show", "213", 2, 1, "Return"),
        )
        val id = addHomePlex()
        val p = playlists.get(id)!!
        vod.sync(p)
        assertEquals(596, vod.movieDetails(p, 11).durationSec)
        val show = vod.seriesDetails(p, 21)
        assertEquals(listOf(1, 2), show.seasons.map { it.number })
        assertEquals(3, show.episodes.size)

        vod.setFavourite(id, CAT_SERIES, 21, true)
        assertTrue(vod.observeFavourite(id, CAT_SERIES, 21).first())

        fun progress(kind: String, item: Long, pos: Long, at: Long, seriesId: Long? = null, hasNext: Boolean = false) =
            WatchProgressEntity(id, kind, item, pos, 3_600_000, at, "t", null, seriesId = seriesId, hasNext = hasNext)
        vod.saveProgress(progress(VOD_MOVIE, 11, 600_000, 1))
        vod.saveProgress(progress(VOD_EPISODE, 211, 3_500_000, 2, seriesId = 21)) // finished
        vod.saveProgress(progress(VOD_EPISODE, 212, 60_000, 3, seriesId = 21))
        vod.saveProgress(progress(VOD_MOVIE, 12, 3_400_000, 4)) // finished
        // Newest first; one row per series (its latest episode); finished things drop off.
        assertEquals(listOf(212L, 11L), vod.observeCarryOn(id).first().map { it.itemId })
        // A finished episode that has a next one stays, so the series carries on.
        vod.saveProgress(progress(VOD_EPISODE, 212, 3_500_000, 5, seriesId = 21, hasNext = true))
        assertEquals(listOf(212L, 11L), vod.observeCarryOn(id).first().map { it.itemId })
        vod.saveProgress(progress(VOD_EPISODE, 213, 3_500_000, 6, seriesId = 21), trim = true) // the last episode
        assertEquals(listOf(11L), vod.observeCarryOn(id).first().map { it.itemId })
    }

    @Test
    fun `a watched episode's card becomes the next episode's`() = runBlocking {
        fake.episodes = listOf(
            FakePlex.episode("21", "Test Show", "211", 1, 1, "Pilot"),
            FakePlex.episode("21", "Test Show", "212", 1, 2, "Second"),
        )
        val id = addHomePlex()
        val p = playlists.get(id)!!
        vod.sync(p)
        val episodes = vod.seriesDetails(p, 21).episodes
        fun watched(item: Long, at: Long, hasNext: Boolean) =
            WatchProgressEntity(id, VOD_EPISODE, item, 3_500_000, 3_600_000, at, "Test Show", "poster", seriesId = 21, hasNext = hasNext)
        suspend fun card() = vod.observeCarryOn(id).first().single()

        // The pilot watched: its card is the second episode's, not started, with its running time.
        vod.saveProgress(watched(211, 10, hasNext = true), next = episodes[1])
        val up = card()
        assertEquals(212L, up.itemId)
        assertEquals(0L, up.positionMs)
        assertEquals(600_000L, up.durationMs)
        assertEquals(listOf("Test Show", "poster", 1, 2), listOf(up.title, up.imageUrl, up.season, up.episode))
        assertTrue("not reported to the server", !up.pending)
        // The credits play on, saved again: the second episode stays the card.
        vod.saveProgress(watched(211, 20, hasNext = true), next = episodes[1])
        assertEquals(212L, card().itemId)

        // Started already: it keeps where it got.
        vod.saveProgress(WatchProgressEntity(id, VOD_EPISODE, 212, 60_000, 600_000, 30, "Test Show", null, seriesId = 21))
        vod.saveProgress(watched(211, 40, hasNext = true), next = episodes[1])
        assertEquals(212L to 60_000L, card().let { it.itemId to it.positionMs })

        // The last episode watched: no card.
        vod.saveProgress(watched(212, 50, hasNext = false), next = null)
        assertTrue(vod.observeCarryOn(id).first().isEmpty())
    }

    @Test
    fun `search finds channels, programmes, movies and series`() = runBlocking {
        threeChannels()
        fake.programme = { i, n -> FakePlex.Programme((if (n == 0) "Now on " else "Next on ") + fake.channels[i].title) }
        val id = addHomePlex()
        playlists.syncChannels(id)
        vod.sync(playlists.get(id)!!)
        val search = SearchRepository(db)
        suspend fun find(q: String) = search.search(id, q)!!

        assertNull(search.search(id, "a")) // one letter: too broad to search
        assertEquals(listOf(plexChannel("2")), find("2").channels.map { it.streamId }) // one digit: channel number only
        assertEquals(listOf("Three"), find("thre").channels.map { it.name })
        val news = find("on One")
        assertEquals(listOf("Now on One", "Next on One"), news.programmes.map { it.title })
        assertEquals("One", news.programmes.first().channelName)
        assertTrue(find("on").programmes.isEmpty()) // the guide needs 3+ letters
        assertEquals(listOf("Big Buck Bunny"), find("bunn").movies.map { it.title })
        assertEquals(listOf("Test Show"), find("show").series.map { it.title })
        assertTrue(find("zzz").isEmpty)
        assertTrue(find("%%").isEmpty) // typed literally, not as wildcards
        assertTrue(find("__").isEmpty)
    }

    @Test
    fun `a backup's M3U playlist or Xtream account is left out of the restore, with what it had`() = runBlocking {
        val backup = BackupRepository(db, settings, SyncManager(scope, playlists, vod, epg, settings), epg)
        val kept = addHomePlex()
        val retired = listOf("m3u", "xtream").map { kind ->
            db.playlists().insert(PlaylistEntity(name = "Old", kind = kind, server = "http://x/a", password = ""))
        }
        retired.forEach { channels.setFavourite(it, 1, true) }
        settings.update { it.copy(activePlaylistId = retired.last()) }
        val guideRead = epg.changes.value
        assertTrue(backup.apply(backup.read(backup.export().inputStream())))
        assertTrue("open guides read again", epg.changes.value > guideRead)
        assertEquals(listOf(kept), playlists.allIds())
        assertEquals(kept, settings.current().activePlaylistId)
        retired.forEach { assertTrue(channels.ids(it, ChannelFilter.Favourites).isEmpty()) }
    }

    @Test
    fun `a backup restores sources, choices, favourites and settings`() = runBlocking {
        val backup = BackupRepository(db, settings, SyncManager(scope, playlists, vod, epg, settings), epg)
        fun import(text: String) = runBlocking { backup.apply(backup.read(text.byteInputStream())) }
        threeChannels()
        val id = addHomePlex()
        playlists.syncChannels(id)
        vod.sync(playlists.get(id)!!)
        channels.setHidden(id, plexChannel("3"), true)
        channels.setFavourite(id, plexChannel("1"), true)
        db.channels().setSortOrder(id, plexChannel("2"), 0) // an order set with Move up, before 1.2.1
        db.categories().setIncluded(id, CAT_MOVIE, listOf("1"), false)
        vod.setFavourite(id, CAT_MOVIE, 11, true)
        vod.saveProgress(WatchProgressEntity(id, VOD_MOVIE, 11, 600_000, 3_600_000, 1, "Big Buck Bunny", null))
        settings.update { it.copy(skipBackSec = 20, largeText = true) }

        val file = backup.export().decodeToString()
        // A bad file is turned down and changes nothing.
        for (bad in listOf("not json", "{}", file.replace("\"MyPlexPlayer\"", "\"Other\""))) {
            try {
                import(bad)
                fail("accepted $bad")
            } catch (_: BackupException) {
            }
        }
        // A newer app's file says so, even when its contents have changed shape.
        try {
            import("""{"app":"MyPlexPlayer","version":99,"playlists":"changed"}""")
            fail("accepted a newer file")
        } catch (e: BackupException) {
            assertTrue(e.message!!.contains("newer version"))
        }
        assertEquals(listOf(id), playlists.allIds())

        playlists.delete(id)
        settings.update { Settings() }
        assertTrue(import(file))

        assertEquals(id, settings.current().activePlaylistId)
        assertEquals(20, settings.current().skipBackSec)
        assertTrue(settings.current().largeText)
        assertEquals(FakePlex.SERVER_TOKEN, playlists.get(id)!!.password)
        assertTrue(playlists.get(id)!!.isStale())
        assertFalse(db.categories().all(id, CAT_MOVIE).single { it.categoryId == "1" }.included)
        assertTrue(vod.observeFavourite(id, CAT_MOVIE, 11).first())
        assertEquals(600_000L, vod.progress(id, VOD_MOVIE, 11)!!.positionMs)

        // The next download fills the channel list back in, keeping the hidden, moved and favourite channels.
        playlists.syncChannels(id)
        assertEquals(listOf(plexChannel("2"), plexChannel("1")), channels.ids(id, ChannelFilter.All))
        assertTrue(channels.get(id, plexChannel("3"))!!.hidden)
        assertEquals(listOf(plexChannel("1")), channels.ids(id, ChannelFilter.Favourites))
    }

    @Test
    fun `guide programmes know if they are a series episode or a film, and are found on demand`() = runBlocking {
        fake.programme = { i, n -> FakePlex.Programme("Second", show = "Test Show", season = 1, episode = 2).takeIf { i == 0 && n == 0 } }
        val id = addHomePlex()
        playlists.syncChannels(id)
        vod.sync(playlists.get(id)!!)
        val now = System.currentTimeMillis()
        val onNow = epg.programmes(id, listOf(plexChannel("1")), now, now + 60_000).getValue(plexChannel("1")).first()
        assertEquals(Triple(ProgrammeKind.SERIES, 1, 2), Triple(onNow.kind, onNow.season, onNow.episode))
        assertEquals(21L, vod.findTitle(id, onNow.title, CAT_SERIES))

        fake.programme = { i, n -> FakePlex.Programme("Big Buck Bunny").takeIf { i == 0 && n == 0 } }
        playlists.syncChannels(id)
        assertEquals(
            ProgrammeKind.MOVIE,
            epg.programmes(id, listOf(plexChannel("1")), now, now + 60_000).getValue(plexChannel("1")).first().kind
        )
        assertEquals(11L, vod.findTitle(id, "Big Buck Bunny", CAT_MOVIE))
        assertEquals(11L, vod.findTitle(id, "BIG BUCK BUNNY!", CAT_MOVIE)) // compared without case or punctuation
        assertNull(vod.findTitle(id, "Big Buck Bunny Returns", CAT_MOVIE))
        assertNull(vod.findTitle(id, "Test Show", CAT_MOVIE))
    }

    @Test
    fun `settings of what the app no longer has are forgotten`() = runBlocking {
        // Where Live TV opened last (until 1.2.1), and Xtream's live stream format, set and per account (until 0.5.2).
        val old = listOf("last_live_filter", "stream_format", "stream_format_for_3").map {
            androidx.datastore.preferences.core.stringPreferencesKey(it)
        }
        context.dataStore.edit { prefs -> old.forEach { prefs[it] = "TS" } }
        settings.update { it }
        val prefs = context.dataStore.data.first()
        old.forEach { assertNull(it.name, prefs[it]) }
    }

    @Test
    fun `credits skipped by themselves as 0_8_0 saved it, with every setting, are off`() = runBlocking {
        val saved = androidx.datastore.preferences.core.intPreferencesKey("auto_skip_credits")
        context.dataStore.edit { it[saved] = 3 }
        assertEquals(Settings.AUTO_SKIP_OFF, settings.current().autoSkipCreditsSec)
        settings.update { it }
        assertNull(context.dataStore.data.first()[saved])
        // Nor from a 0.8.0 backup.
        val restored = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
            .decodeFromString(Settings.serializer(), """{"autoSkipCreditsSec":3}""")
        assertEquals(Settings.AUTO_SKIP_OFF, restored.autoSkipCreditsSec)
        // One chosen since is kept.
        settings.update { it.copy(autoSkipCreditsSec = 5) }
        assertEquals(5, settings.current().autoSkipCreditsSec)
    }
}
