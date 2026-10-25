package com.biskyt.myplexplayer.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.biskyt.myplexplayer.data.db.AppDatabase
import com.biskyt.myplexplayer.data.db.PlaylistEntity
import com.biskyt.myplexplayer.testutil.FakePlex
import java.util.Locale
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

    @Before
    fun setUp() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        val http = OkHttpClient()
        val settings = SettingsRepository(context)
        val categoryStore = CategoryStore(db) { Locale.UK }
        epg = EpgRepository(db, http, context.cacheDir.resolve("test-epg"), scope)
        val plex = PlexSources(db, http, settings).apply { plexTv = fake.url }
        val playlists = PlaylistRepository(db, http, settings, epg, categoryStore, plex)
        sync = SyncManager(scope, playlists, VodRepository(db, playlists, categoryStore, plex), epg)
        plex.signIn(FakePlex.ACCOUNT_TOKEN)
        source = db.playlists().all().single { it.machineId == FakePlex.MACHINE_ID }
    }

    @After
    fun tearDown() {
        scope.cancel()
        db.close()
        fake.close()
    }

    private suspend fun channels() = ChannelRepository(db).ids(source.id, ChannelFilter.All).map { db.channels().get(source.id, it)!! }

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
        val channels = ChannelRepository(db)
        channels.setFavourite(source.id, plexChannelId("ch-2"), true)

        sync.refreshNow(source.id).getOrThrow()

        assertEquals(listOf(plexChannelId("ch-2")), channels.observeFavouriteIds(source.id).first())
    }

    @Test
    fun `a server that won't share its live tv still brings its films`() = runBlocking {
        fake.liveTvRefused = true
        sync.refreshNow(source.id).getOrThrow()
        assertEquals(emptyList<Long>(), ChannelRepository(db).ids(source.id, ChannelFilter.All))
        assertTrue(db.vod().observeMovieCount(source.id).first() > 0)
    }

    @Test
    fun `a server failing for now keeps its channels`() = runBlocking {
        sync.refreshNow(source.id).getOrThrow()
        fake.liveTvFails = true

        sync.refreshNow(source.id)

        assertEquals(2, ChannelRepository(db).ids(source.id, ChannelFilter.All).size)
    }

    @Test
    fun `a server without live tv has no channels`() = runBlocking {
        sync.refreshNow(source.id).getOrThrow()
        fake.liveTv = false

        sync.refreshNow(source.id).getOrThrow()

        assertEquals(emptyList<Long>(), ChannelRepository(db).ids(source.id, ChannelFilter.All))
    }
}
