package com.biskyt.myplexplayer.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.biskyt.myplexplayer.data.db.AppDatabase
import com.biskyt.myplexplayer.data.db.CAT_LIVE
import com.biskyt.myplexplayer.data.db.FavouriteEntity
import com.biskyt.myplexplayer.data.db.PlaylistEntity
import com.biskyt.myplexplayer.data.db.ProgrammeEntity
import com.biskyt.myplexplayer.testutil.FakePlex
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
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
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Live TV from a Plex server: its tuner's channels and its guide, brought in by a sync. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = com.biskyt.myplexplayer.testutil.PlainApp::class)
class PlexLiveTvTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val fake = FakePlex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var db: AppDatabase
    private lateinit var epg: EpgRepository
    private lateinit var sync: SyncManager
    private lateinit var source: PlaylistEntity
    private lateinit var channelRepo: ChannelRepository
    private lateinit var favourites: Favourites
    private val notices = Notices()

    @Before
    fun setUp() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        val http = OkHttpClient()
        val settings = SettingsRepository(context)
        val categoryStore = CategoryStore(db)
        epg = EpgRepository(db)
        val plex = PlexSources(db, http, settings).apply {
            plexTv = fake.url
            plexGuide = fake.url
        }
        val playlists = PlaylistRepository(db, settings, epg, categoryStore, plex)
        val vod = VodRepository(db, playlists, categoryStore, plex, Notices())
        sync = SyncManager(scope, playlists, vod, epg)
        channelRepo = ChannelRepository(db, plex, notices)
        favourites = Favourites(scope, settings, channelRepo, vod)
        plex.signIn(FakePlex.ACCOUNT_TOKEN)
        source = db.playlists().all().single { it.machineId == FakePlex.MACHINE_ID }
    }

    @After
    fun tearDown() {
        scope.cancel()
        db.close()
        fake.close()
    }

    private suspend fun channels() = channelRepo.ids(source.id, ChannelFilter.All).map { db.channels().get(source.id, it)!! }

    @Test
    fun `a sync brings in the tuner's channels, in number order, and what's on them`() = runBlocking {
        sync.refreshNow(source.id).getOrThrow()

        val channels = channels()
        assertEquals(listOf("One", "Two"), channels.map { it.name })
        assertEquals(listOf(1, 2), channels.map { it.number })
        assertEquals(listOf(plexChannelId("ch-1"), plexChannelId("ch-2")), channels.map { it.streamId })

        val now = System.currentTimeMillis()
        val guide = epg.programmes(source.id, channels.map { it.streamId }, now, now + 2 * 3_600_000)
        val onOne = guide.getValue(plexChannelId("ch-1"))
        assertEquals(listOf("Show 0", "Film 0"), onOne.map { it.title }) // an episode by its series
        assertEquals("Episode 0", onOne.first().subtitle)
        assertTrue("on now", onOne.first().startMs <= now && now < onOne.first().stopMs)
    }

    @Test
    fun `channels keep their ids from sync to sync, so favourites stay`() = runBlocking {
        sync.refreshNow(source.id).getOrThrow()
        channelRepo.setFavourite(source.id, plexChannelId("ch-2"), true)

        sync.refreshNow(source.id).getOrThrow()

        assertEquals(listOf(plexChannelId("ch-2")), channelRepo.observeFavouriteIds(source.id).first())
    }

    @Test
    fun `the account's favourite channels in Plex are the favourites here, in Plex's order`() = runBlocking {
        fake.channels = listOf(FakePlex.Channel("1", "One"), FakePlex.Channel("2", "Two"), FakePlex.Channel("3", "Three"))
        fake.favouriteChannels = listOf("ch-3", "ch-1", "ch-9") // ch-9 isn't one of this server's channels

        sync.refreshNow(source.id).getOrThrow()

        // Not the other server's ch-1 twice, nor a channel this server doesn't have.
        assertEquals(listOf(plexChannelId("ch-3"), plexChannelId("ch-1")), channelRepo.favouriteOrder(source.id))
    }

    @Test
    fun `a favourite made or taken off here is saved to Plex, the others kept as they were`() = runBlocking {
        fake.channels = (1..3).map { FakePlex.Channel("$it", "Channel $it") }
        fake.favouriteChannels = listOf("ch-1")
        sync.refreshNow(source.id).getOrThrow()

        assertTrue(channelRepo.setFavourite(source.id, plexChannelId("ch-3"), true))
        assertTrue(channelRepo.setFavourite(source.id, plexChannelId("ch-1"), false))

        assertEquals(listOf("ch-3"), fake.favouriteChannels)
        val added = fake.accountFavourites.single { it["id"].toString() == "\"ch-3\"" }
        // Named as other Plex apps name it: its server and guide, title and number.
        assertEquals(FakePlex.favourite("ch-1", FakePlex.MACHINE_ID)["source"], added["source"])
        assertEquals("\"Channel 3\"", added["title"].toString())
        assertEquals("\"3\"", added["vcn"].toString())
        assertEquals("the friend's server's favourite stays", 2, fake.accountFavourites.size)
        assertEquals(listOf(plexChannelId("ch-3")), channelRepo.favouriteOrder(source.id))
    }

    @Test
    fun `Plex wins, a change made in Plex replacing what's here at the next sync`() = runBlocking {
        fake.channels = (1..4).map { FakePlex.Channel("$it", "Channel $it") }
        fake.favouriteChannels = listOf("ch-1", "ch-2")
        sync.refreshNow(source.id).getOrThrow()
        db.favourites().add(FavouriteEntity(source.id, plexChannelId("ch-4"), 1, 9)) // only here

        fake.favouriteChannels = listOf("ch-3", "ch-2")
        sync.refreshNow(source.id).getOrThrow()

        assertEquals(listOf(plexChannelId("ch-3"), plexChannelId("ch-2")), channelRepo.favouriteOrder(source.id))
    }

    @Test
    fun `favourites moved here are saved in that order to Plex, another server's keeping its place`() = runBlocking {
        fake.channels = (1..3).map { FakePlex.Channel("$it", "Channel $it") }
        fake.favouriteChannels = listOf("ch-1", "ch-2", "ch-3")
        sync.refreshNow(source.id).getOrThrow()
        val friends = fake.accountFavourites.last()

        val original = channelRepo.favouriteOrder(source.id)
        val order = listOf(plexChannelId("ch-3"), plexChannelId("ch-1"), plexChannelId("ch-2"))
        favourites.setOrder(source.id, CAT_LIVE, order, before = original) // the move's steps: here only
        assertEquals(listOf("ch-1", "ch-2", "ch-3"), fake.favouriteChannels)

        favourites.moveEnded(source.id, CAT_LIVE, original, order)?.join()

        assertEquals(listOf("ch-3", "ch-1", "ch-2"), fake.favouriteChannels)
        assertEquals(friends, fake.accountFavourites.last())
        assertEquals(order, channelRepo.favouriteOrder(source.id))
    }

    @Test
    fun `two favourites made at once both reach Plex`() = runBlocking {
        fake.channels = (1..3).map { FakePlex.Channel("$it", "Channel $it") }
        sync.refreshNow(source.id).getOrThrow()

        listOf("ch-1", "ch-2", "ch-3").map { async(Dispatchers.Default) { channelRepo.setFavourite(source.id, plexChannelId(it), true) } }
            .awaitAll()

        assertEquals(listOf("ch-1", "ch-2", "ch-3"), fake.favouriteChannels.sorted())
    }

    @Test
    fun `a channel Plex lists twice is ordered without losing either`() = runBlocking {
        fake.channels = (1..2).map { FakePlex.Channel("$it", "Channel $it") }
        fake.favouriteChannels = listOf("ch-1", "ch-1", "ch-2")
        sync.refreshNow(source.id).getOrThrow()

        assertTrue(channelRepo.saveFavouriteOrder(source.id, listOf(plexChannelId("ch-2"), plexChannelId("ch-1"))))

        assertEquals(listOf("ch-2", "ch-1", "ch-1"), fake.favouriteChannels)
    }

    @Test
    fun `a move Plex doesn't take is put back as it was`() = runBlocking {
        fake.channels = (1..2).map { FakePlex.Channel("$it", "Channel $it") }
        fake.favouriteChannels = listOf("ch-1", "ch-2")
        sync.refreshNow(source.id).getOrThrow()
        val original = channelRepo.favouriteOrder(source.id)
        val order = original.reversed()
        favourites.setOrder(source.id, CAT_LIVE, order, before = original)
        fake.favouritesFail = true

        favourites.moveEnded(source.id, CAT_LIVE, original, order)?.join()

        assertEquals(original, channelRepo.favouriteOrder(source.id))
        assertNotNull(notices.current.value)
    }

    @Test
    fun `with Plex out of reach a favourite isn't changed here either, and a notice says so`() = runBlocking {
        fake.favouriteChannels = listOf("ch-1")
        sync.refreshNow(source.id).getOrThrow()
        fake.favouritesFail = true

        assertFalse(channelRepo.setFavourite(source.id, plexChannelId("ch-2"), true))

        assertEquals(listOf(plexChannelId("ch-1")), channelRepo.favouriteOrder(source.id))
        assertNotNull(notices.current.value)
    }

    @Test
    fun `a channel missing from one sync comes back to its place in Plex's order`() = runBlocking {
        fake.channels = (1..3).map { FakePlex.Channel("$it", "Channel $it") }
        fake.favouriteChannels = listOf("ch-2", "ch-1")
        sync.refreshNow(source.id).getOrThrow()
        val all = fake.channels

        fake.channels = all - all[1] // ch-2 left out of one lineup
        sync.refreshNow(source.id).getOrThrow()
        fake.channels = all
        sync.refreshNow(source.id).getOrThrow()

        assertEquals(listOf(plexChannelId("ch-2"), plexChannelId("ch-1")), channelRepo.favouriteOrder(source.id))
    }

    @Test
    fun `favourites that can't be read change nothing, and the sync still works`() = runBlocking {
        fake.favouriteChannels = listOf("ch-1")
        sync.refreshNow(source.id).getOrThrow()
        fake.favouritesFail = true
        fake.favouriteChannels = emptyList()

        sync.refreshNow(source.id).getOrThrow()

        assertEquals(listOf(plexChannelId("ch-1")), channelRepo.favouriteOrder(source.id))
    }

    @Test
    fun `a server that won't share its live tv still brings its films`() = runBlocking {
        fake.liveTvRefused = true
        sync.refreshNow(source.id).getOrThrow()
        assertEquals(emptyList<Long>(), channelRepo.ids(source.id, ChannelFilter.All))
        assertTrue(db.vod().observeMovieCount(source.id).first() > 0)
    }

    @Test
    fun `a server that stops sharing its live tv loses its guide too`() = runBlocking {
        sync.refreshNow(source.id).getOrThrow()
        fake.guideFails = true
        sync.refreshNow(source.id).getOrThrow()
        fake.liveTvRefused = true

        // No guide error is left to report either.
        assertNull(sync.refreshNow(source.id).getOrThrow())

        assertNull(db.epg().providerSource(source.id))
        assertEquals(emptyMap<Long, String>(), epg.observeGuideErrors().first())
    }

    @Test
    fun `a sync drops programmes over hours ago`() = runBlocking {
        sync.refreshNow(source.id).getOrThrow()
        val guide = db.epg().providerSource(source.id)!!.id
        val now = System.currentTimeMillis()
        val old = ProgrammeEntity(guide, "ch-1", now - 5 * 3_600_000, now - 4 * 3_600_000, "Old", null, null)
        db.epg().insertProgrammes(listOf(old))
        fake.guideFails = true // a guide that no longer loads would keep it

        sync.refreshNow(source.id).getOrThrow()

        val one = epg.programmes(source.id, listOf(plexChannelId("ch-1")), now - 6 * 3_600_000, now)
        assertEquals(emptyList<String>(), one[plexChannelId("ch-1")].orEmpty().filter { it.title == "Old" }.map { it.title })
    }

    @Test
    fun `a guide out of reach says so, not an older failure`() = runBlocking {
        fake.guideFails = true
        sync.refreshNow(source.id).getOrThrow()
        fake.guideFails = false
        fake.guideUnreachable = true

        val error = sync.refreshNow(source.id).getOrThrow()

        assertEquals(GuideError.saved(GuideError.unreachable()), db.epg().providerSource(source.id)!!.lastError)
        assertEquals("Couldn't reach the Plex server", error)
    }

    @Test
    fun `a server failing for now keeps its channels`() = runBlocking {
        sync.refreshNow(source.id).getOrThrow()
        fake.liveTvFails = true

        sync.refreshNow(source.id)

        assertEquals(2, channelRepo.ids(source.id, ChannelFilter.All).size)
    }

    @Test
    fun `a server without live tv has no channels`() = runBlocking {
        sync.refreshNow(source.id).getOrThrow()
        fake.liveTv = false

        sync.refreshNow(source.id).getOrThrow()

        assertEquals(emptyList<Long>(), channelRepo.ids(source.id, ChannelFilter.All))
    }

    @Test
    fun `a guide that fails keeps the last one, and says why with the guides`() = runBlocking {
        sync.refreshNow(source.id).getOrThrow()
        val guide = db.epg().providerSource(source.id)!!
        assertNull(guide.lastError)
        assertEquals(2, guide.channelCount)
        val now = System.currentTimeMillis()
        val before = epg.programmes(source.id, channels().map { it.streamId }, now, now + 3_600_000)

        fake.guideFails = true
        sync.refreshNow(source.id).getOrThrow() // the channels still came

        assertEquals(before, epg.programmes(source.id, channels().map { it.streamId }, now, now + 3_600_000))
        // As the code, not words: shown in the language of the moment.
        assertEquals(GuideError.saved(GuideError.server(500)), db.epg().providerSource(source.id)!!.lastError)

        fake.guideFails = false
        sync.refreshNow(source.id).getOrThrow()
        assertNull(db.epg().providerSource(source.id)!!.lastError)
    }

    @Test
    fun `a guide that can't be read at all says so, in words of the moment`() = runBlocking {
        sync.refreshNow(source.id).getOrThrow()
        fake.guideUnreadable = true

        sync.refreshNow(source.id).getOrThrow()

        val error = db.epg().providerSource(source.id)!!.lastError!!
        assertEquals(GuideError.saved(GuideError.unreadable()), error)
        assertEquals("Couldn't read the guide", GuideError.text(error))
    }
}
