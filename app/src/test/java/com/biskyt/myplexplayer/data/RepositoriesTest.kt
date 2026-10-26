package com.biskyt.myplexplayer.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.biskyt.myplexplayer.core.epg.ProgrammeKind
import com.biskyt.myplexplayer.core.xtream.XtreamException
import com.biskyt.myplexplayer.data.db.AppDatabase
import com.biskyt.myplexplayer.data.db.CAT_LIVE
import com.biskyt.myplexplayer.data.db.CAT_MOVIE
import com.biskyt.myplexplayer.data.db.CAT_SERIES
import com.biskyt.myplexplayer.data.db.PlaylistEntity
import com.biskyt.myplexplayer.data.db.VOD_EPISODE
import com.biskyt.myplexplayer.data.db.VOD_MOVIE
import com.biskyt.myplexplayer.data.db.WatchProgressEntity
import com.biskyt.myplexplayer.testutil.FakePlex
import com.biskyt.myplexplayer.testutil.FakeProvider
import com.biskyt.myplexplayer.testutil.PlainApp
import com.biskyt.myplexplayer.testutil.plexChannel
import com.biskyt.myplexplayer.testutil.signInHome
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
@Config(sdk = [34], application = PlainApp::class)
class RepositoriesTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val provider = FakeProvider()
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
        epg = EpgRepository(db, http, context.cacheDir.resolve("test-epg"), scope)
        val categoryStore = CategoryStore(db) { Locale.UK }
        plex = PlexSources(db, http, settings).apply { plexTv = fake.url }
        playlists = PlaylistRepository(db, http, settings, epg, categoryStore, plex)
        channels = ChannelRepository(db)
        vod = VodRepository(db, playlists, categoryStore, plex)
    }

    @After
    fun tearDown() {
        scope.cancel()
        db.close()
        provider.close()
        fake.close()
    }

    private suspend fun addPlaylist(): Long = playlists.add("Home", provider.url, "me", "secret")

    /** Signs in to [fake] and adds Home Plex: its source's id. */
    private suspend fun addHomePlex(): Long {
        return plex.signInHome(db)
    }

    /** Three tuner channels, One, Two and Three, in place of FakePlex's two. */
    private fun threeChannels() {
        fake.channels = listOf(FakePlex.Channel("1", "One"), FakePlex.Channel("2", "Two"), FakePlex.Channel("3", "Three"))
    }

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
                CategoryStore(db) {
                    Locale.US
                },
                PlexSources(db, OkHttpClient(), settings)
            )
        val id = us.add("Home", provider.url, "me", "secret")
        val included = db.categories().all(id, CAT_LIVE).filter { it.included }.map { it.categoryId }
        assertEquals(listOf("1", "2"), included)
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
        db.channels().setSortOrder(id, 102, 0) // an order set with Move up, before 1.2.1
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
    fun `an unchanged guide isn't downloaded or read again, and adding a guide leaves the others alone`() = runBlocking {
        val id = addPlaylist()
        playlists.syncChannels(id)
        epg.ensureProviderSource(playlists.get(id)!!, playlists.client(playlists.get(id)!!).xmltvUrl())
        epg.importAll(listOf(id))
        val provider101 = db.epg().mapping(id, 101)!!.xmlId

        // The same guide again: the server says it hasn't changed, so its channels and programmes aren't read again.
        val again = mutableListOf<GuideStep>()
        epg.importAll(listOf(id)) { again += it }
        assertTrue(again.none { it is GuideStep.Reading || it is GuideStep.Loading })
        assertEquals("Now on $provider101", epg.programmes(id, listOf(101), now(), now() + 60_000).getValue(101).first().title)

        // Adding a guide reads its own programmes, with progress; the provider's guide isn't read again.
        val added = mutableListOf<GuideStep>()
        val extra = epg.addSource("Extra", provider.url + "xmltv.php?username=me&password=secret&extra=1")
        epg.importAll(listOf(id), only = extra) { added += it }
        assertTrue(added.any { it is GuideStep.Loading && it.guide == "Extra" && it.fraction != null })
        assertTrue(added.none { it is GuideStep.Loading && it.guide.orEmpty().startsWith("Home") })
    }

    private fun now() = System.currentTimeMillis()

    @Test
    fun `a guide listing channels between programmes still has all its channels`() = runBlocking {
        provider.interleavedGuide = true
        val id = addPlaylist()
        playlists.syncChannels(id)
        epg.ensureProviderSource(playlists.get(id)!!, playlists.client(playlists.get(id)!!).xmltvUrl())
        fun guideChannelCount() = db.query("SELECT COUNT(*) FROM epg_channel", null).use {
            it.moveToFirst()
            it.getInt(0)
        }
        epg.importAll(listOf(id))
        assertEquals(4, guideChannelCount())
        assertEquals("kite.uk", epg.suggestions("UK: KITE KIDZ", null).first().candidate.xmlId)
        // Later updates read such a guide in full straight away.
        epg.importAll(listOf(id))
        assertEquals(4, guideChannelCount())
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
        val id = addHomePlex()
        playlists.syncChannels(id)
        vod.sync(playlists.get(id)!!)
        assertEquals(1, db.epg().enabledSources().size) // the server's own guide
        playlists.delete(id)
        assertTrue(channels.ids(id, ChannelFilter.All).isEmpty())
        assertTrue(db.vod().moviesSnapshot(id).isEmpty())
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

        // Unticking every movie category turns movies off: the next sync doesn't tick the defaults again.
        db.categories().setIncluded(id, CAT_MOVIE, listOf("10", "11", "12"), false)
        vod.sync(p)
        assertEquals(listOf<String>(), db.categories().all(id, CAT_MOVIE).filter { it.included }.map { it.categoryId })
        assertEquals(listOf<String>(), db.vod().moviesSnapshot(id).map { it.title })
        // Nor does a new category the provider adds, though categories named without a language are usually ticked.
        provider.movieCategories = provider.movieCategories + ("13" to "DOCUMENTARIES")
        provider.movies = provider.movies + Triple(801L, "Planet (2020)", "13")
        vod.sync(p)
        assertEquals(listOf<String>(), db.categories().all(id, CAT_MOVIE).filter { it.included }.map { it.categoryId })
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
    fun `a backup's M3U playlist is left out of the restore, with what it had`() = runBlocking {
        val backup = BackupRepository(db, settings, epg, SyncManager(scope, playlists, vod, epg))
        val kept = addPlaylist()
        val m3u = db.playlists().insert(PlaylistEntity(name = "Old", kind = "m3u", server = "http://x/a.m3u", username = "", password = ""))
        channels.setFavourite(m3u, 1, true)
        settings.update { it.copy(activePlaylistId = m3u) }
        assertTrue(backup.apply(backup.read(backup.export().inputStream())))
        assertEquals(listOf(kept), playlists.allIds())
        assertEquals(kept, settings.current().activePlaylistId)
        assertTrue(channels.ids(m3u, ChannelFilter.Favourites).isEmpty())
    }

    @Test
    fun `a backup restores playlists, choices, favourites, guide choices and settings`() = runBlocking {
        val backup = BackupRepository(db, settings, epg, SyncManager(scope, playlists, vod, epg))
        fun import(text: String) = runBlocking { backup.apply(backup.read(text.byteInputStream())) }
        threeChannels()
        val id = addHomePlex()
        playlists.syncChannels(id)
        vod.sync(playlists.get(id)!!)
        val source = db.epg().enabledSources().single().id // the server's own guide
        channels.setHidden(id, plexChannel("3"), true)
        channels.setFavourite(id, plexChannel("1"), true)
        db.channels().setSortOrder(id, plexChannel("2"), 0) // an order set with Move up, before 1.2.1
        epg.setOffset(id, plexChannel("1"), 60)
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
        assertEquals(source, db.epg().enabledSources().single().id)
        assertEquals(60, db.epg().mapping(id, plexChannel("1"))!!.offsetMinutes)

        // The next download fills the channel list back in, keeping the hidden, moved and favourite channels.
        playlists.syncChannels(id)
        assertEquals(listOf(plexChannel("2"), plexChannel("1")), channels.ids(id, ChannelFilter.All))
        assertTrue(channels.get(id, plexChannel("3"))!!.hidden)
        assertEquals(listOf(plexChannel("1")), channels.ids(id, ChannelFilter.Favourites))
    }

    @Test
    fun `guide updates keep matches, retry when the guide changes, and match on request`() = runBlocking {
        provider.streams = provider.streams + FakeProvider.Stream(104, 4, "US: NBC 4 (WNBC) NEW YORK (A)", "1", null)
        val id = addPlaylist()
        playlists.syncChannels(id)
        val p = playlists.get(id)!!
        epg.ensureProviderSource(p, playlists.client(p).xmltvUrl())
        epg.importAll(listOf(id)) // the guide's first load matches everything
        assertEquals("one.uk", db.epg().mapping(id, 101)!!.xmlId)
        assertNull(db.epg().mapping(id, 104)!!.sourceId) // tried: nothing found

        // An unchanged guide keeps automatic matches as they are and doesn't retry channels already tried.
        db.epg().upsertMappings(listOf(db.epg().mapping(id, 101)!!.copy(score = 0.5)))
        epg.importAll(listOf(id))
        assertEquals(0.5, db.epg().mapping(id, 101)!!.score, 0.0)
        assertNull(db.epg().mapping(id, 104)!!.sourceId)

        // When the guide's channel list changes, channels without a guide are tried again.
        provider.guideChannels = provider.guideChannels + ("wnbc.us" to "US - NBC WNBC NY")
        epg.importAll(listOf(id))
        assertEquals("wnbc.us", db.epg().mapping(id, 104)!!.xmlId)
        assertEquals(0.5, db.epg().mapping(id, 101)!!.score, 0.0)

        // The button: near-certain matches for channels without a guide (here as an older version left it),
        // with their programmes loaded.
        db.epg().upsertMappings(listOf(db.epg().mapping(id, 104)!!.copy(sourceId = null, xmlId = null, score = 0.0)))
        val progress = mutableListOf<GuideStep>()
        val result = epg.matchMissing(listOf(id)) { progress += it }
        assertEquals("wnbc.us", db.epg().mapping(id, 104)!!.xmlId)
        assertEquals(1, result.matched)
        assertTrue(result.checked >= 1)
        assertTrue(progress.any { it is GuideStep.Matching && it.total > 0 })
        val now = System.currentTimeMillis()
        assertEquals("Now on wnbc.us", epg.programmes(id, listOf(104), now, now + 3_600_000).getValue(104).first().title)

        // A new guide source matches everything again.
        epg.addSource("Extra", provider.url + "xmltv.php?username=me&password=secret&extra=1")
        epg.importAll(listOf(id))
        assertEquals(1.0, db.epg().mapping(id, 101)!!.score, 0.0)

        // A matched guide channel that leaves the guide is matched again (here to nothing).
        assertEquals("wnbc.us", db.epg().mapping(id, 104)!!.xmlId)
        provider.guideChannels = provider.guideChannels.filter { it.first != "wnbc.us" }
        epg.importAll(listOf(id))
        assertNull(db.epg().mapping(id, 104)!!.sourceId)
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
    fun `where Live TV opened last (until 1_2_1) is forgotten`() = runBlocking {
        val old = androidx.datastore.preferences.core.stringPreferencesKey("last_live_filter")
        context.dataStore.edit { it[old] = "category:2" }
        settings.update { it }
        assertNull(context.dataStore.data.first()[old])
    }
}
