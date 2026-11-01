package com.biskyt.myplexplayer.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.biskyt.myplexplayer.data.db.AppDatabase
import com.biskyt.myplexplayer.data.db.CAT_LIVE
import com.biskyt.myplexplayer.data.db.CAT_MOVIE
import com.biskyt.myplexplayer.data.db.FavouriteEntity
import com.biskyt.myplexplayer.data.db.VodFavouriteEntity
import com.biskyt.myplexplayer.testutil.FakePlex
import com.biskyt.myplexplayer.testutil.PlainApp
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The user's order of favourites: kept from before 1.2.0, new ones at the end, moved, and in backups. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = PlainApp::class)
class FavouriteOrderTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val plex = FakePlex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var db: AppDatabase
    private lateinit var settings: SettingsRepository
    private lateinit var epg: EpgRepository
    private lateinit var playlists: PlaylistRepository
    private lateinit var channels: ChannelRepository
    private lateinit var vod: VodRepository
    private lateinit var favourites: Favourites
    private var pid = 0L

    @Before
    fun setUp() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        val http = OkHttpClient()
        settings = SettingsRepository(context)
        epg = EpgRepository(db)
        val categoryStore = CategoryStore(db)
        val sources = PlexSources(db, http, settings).apply {
            plexTv = plex.url
            plexGuide = plex.url
        }
        playlists = PlaylistRepository(db, settings, epg, categoryStore, sources)
        channels = ChannelRepository(db, sources, Notices())
        vod = VodRepository(db, playlists, categoryStore, sources, Notices())
        favourites = Favourites(scope, settings, channels, vod)
        plex.channels = listOf(FakePlex.Channel("1", "One"), FakePlex.Channel("2", "Two"), FakePlex.Channel("3", "Three"))
        plex.movies = listOf(FakePlex.film("11", "Big Buck Bunny"), FakePlex.film("12", "Sintel"), FakePlex.film("13", "Cosmos"))
        pid = sources.signInHome(db)
        playlists.syncChannels(pid)
        vod.sync(playlists.get(pid)!!)
    }

    @After
    fun tearDown() {
        scope.cancel()
        db.close()
        plex.close()
    }

    @Test
    fun `channels saved before the order keep channel-number order, and new favourites go at the end`() = runBlocking {
        // As 1.1 saved them (position 0), added in the opposite order.
        db.favourites().add(FavouriteEntity(pid, THREE, addedAt = 1))
        db.favourites().add(FavouriteEntity(pid, ONE, addedAt = 2))
        assertEquals(listOf(ONE, THREE), favourites.order(pid, CAT_LIVE))
        channels.setFavourite(pid, TWO, true)
        assertEquals(listOf(ONE, THREE, TWO), favourites.order(pid, CAT_LIVE))
        // Every list of favourite channels follows it: Home's row, the paged list and zapping.
        assertEquals(listOf(ONE, THREE, TWO), channels.observeFavourites(pid).first().map { it.streamId })
        assertEquals(listOf(ONE, THREE, TWO), channels.ids(pid, ChannelFilter.Favourites))
    }

    @Test
    fun `movies saved before the order keep newest first, and new favourites go at the end`() = runBlocking {
        db.vod().addFavourite(VodFavouriteEntity(pid, CAT_MOVIE, 11, addedAt = 1))
        db.vod().addFavourite(VodFavouriteEntity(pid, CAT_MOVIE, 12, addedAt = 2))
        assertEquals(listOf(12L, 11L), favourites.order(pid, CAT_MOVIE))
        vod.setFavourite(pid, CAT_MOVIE, 13, true)
        assertEquals(listOf(12L, 11L, 13L), favourites.order(pid, CAT_MOVIE))
        assertEquals(listOf(12L, 11L, 13L), vod.observeFavouriteCards(pid, CAT_MOVIE, 10).first().map { it.id })
    }

    @Test
    fun `moving one stores the order, as far as the ends`() = runBlocking {
        listOf(ONE, TWO, THREE).forEach { channels.setFavourite(pid, it, true) }
        val order = favourites.order(pid, CAT_LIVE)
        favourites.setOrder(pid, CAT_LIVE, Favourites.moved(order, THREE, -1))
        assertEquals(listOf(ONE, THREE, TWO), favourites.order(pid, CAT_LIVE))
        favourites.setOrder(pid, CAT_LIVE, Favourites.moved(favourites.order(pid, CAT_LIVE), ONE, 5))
        assertEquals(listOf(THREE, TWO, ONE), favourites.order(pid, CAT_LIVE))
        assertEquals(listOf(THREE, TWO, ONE), Favourites.moved(listOf(THREE, TWO, ONE), THREE, -1))
        assertEquals(listOf(1L, 2L), Favourites.moved(listOf(1L, 2L), 9, 1)) // not in the list: left as it is
        // Home shows the first few: one moved along its row stays in it.
        assertEquals(listOf(1L, 3L, 2L, 4L), Favourites.moved(listOf(1L, 2L, 3L, 4L), 2, 5, shown = 3))
        assertEquals(listOf(1L, 2L, 3L, 4L), Favourites.moved(listOf(1L, 2L, 3L, 4L), 3, 1, shown = 3))
    }

    @Test
    fun `restored from a backup, channels come back in Plex's order, whatever order the backup had`() = runBlocking {
        val backup = BackupRepository(db, settings, SyncManager(scope, playlists, vod, epg, settings), epg)
        listOf(ONE, TWO, THREE).forEach { channels.setFavourite(pid, it, true) }
        // Moved, as a move ends: saved to Plex.
        favourites.setOrder(pid, CAT_LIVE, listOf(THREE, ONE, TWO))
        favourites.moveEnded(pid, CAT_LIVE, listOf(ONE, TWO, THREE), listOf(THREE, ONE, TWO))?.join()
        val file = backup.export().decodeToString()

        playlists.delete(pid)
        assertTrue(backup.apply(backup.read(file.byteInputStream())))
        playlists.syncChannels(pid)
        assertEquals(listOf(THREE, ONE, TWO), favourites.order(pid, CAT_LIVE))

        // A file from 1.1 has no positions (channel-number order), but the order is Plex's, saved there when moved.
        val old = Regex(""","position":\d+""").replace(file, "")
        assertTrue(old != file)
        playlists.delete(pid)
        assertTrue(backup.apply(backup.read(old.byteInputStream())))
        playlists.syncChannels(pid)
        assertEquals(listOf(THREE, ONE, TWO), favourites.order(pid, CAT_LIVE))
    }

    private companion object {
        val ONE = plexChannelId("ch-1")
        val TWO = plexChannelId("ch-2")
        val THREE = plexChannelId("ch-3")
    }
}
