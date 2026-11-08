package com.biskyt.myplexplayer.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.biskyt.myplexplayer.data.db.AppDatabase
import com.biskyt.myplexplayer.data.db.CAT_MOVIE
import com.biskyt.myplexplayer.data.db.CAT_SERIES
import com.biskyt.myplexplayer.data.db.PlaylistEntity
import com.biskyt.myplexplayer.data.db.VOD_MOVIE
import com.biskyt.myplexplayer.testutil.FakePlex
import java.util.concurrent.CountDownLatch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The owner, 2026-11-08: Home's Continue watching and recently added rows do not wait for the Live TV channels and
 * guide, which are slow on a low-powered server.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = com.biskyt.myplexplayer.testutil.PlainApp::class)
class SyncOrderTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val fake = FakePlex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var db: AppDatabase
    private lateinit var epg: EpgRepository
    private lateinit var vod: VodRepository
    private lateinit var source: PlaylistEntity
    private lateinit var sync: SyncManager

    @Before
    fun setUp() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        val settings = SettingsRepository(context)
        val categoryStore = CategoryStore(db)
        epg = EpgRepository(db)
        val plex = PlexSources(db, OkHttpClient(), settings).apply {
            plexTv = fake.url
            plexGuide = fake.url
        }
        val playlists = PlaylistRepository(db, settings, epg, categoryStore, plex)
        vod = VodRepository(db, playlists, categoryStore, plex, Notices())
        sync = SyncManager(scope, playlists, vod, epg, settings)
        plex.signIn(FakePlex.ACCOUNT_TOKEN)
        source = db.playlists().all().single { it.machineId == FakePlex.MACHINE_ID }
        settings.update { it.copy(activePlaylistId = source.id) }
    }

    @After
    fun tearDown() {
        scope.cancel()
        fake.guideHeld?.countDown() // else its request holds up the shutdown
        fake.close()
        db.close()
    }

    private suspend fun newest(type: String) = vod.observeNewestCards(source.id, type, 10).first().map { it.id }

    @Test
    fun `Continue watching and recently added come in while the guide is still loading`() = runBlocking {
        assertTrue(sync.refreshNow(source.id).isSuccess)
        // Since then: a film added and played elsewhere, and a new series (the server lists the newest first).
        fake.movies = fake.movies + FakePlex.film("13", "Tears of Steel", 2012, addedAt = 1_700_000_900)
        fake.shows = listOf(FakePlex.show("22", "New Show", 2024)) + fake.shows
        fake.viewOffset["13"] = 120_000
        fake.lastViewedAt["13"] = System.currentTimeMillis() / 1000 - 60
        fake.continueWatching = listOf("13")
        fake.guideHeld = CountDownLatch(1)

        val refresh = sync.refresh(source.id)
        withTimeout(10_000) {
            while (vod.observeCarryOn(source.id).first().none { it.kind == VOD_MOVIE && it.itemId == 13L }) delay(10)
        }
        assertEquals(13L, newest(CAT_MOVIE).first())
        assertEquals(22L, newest(CAT_SERIES).first())
        assertTrue("the guide is still loading", refresh.isActive)

        fake.guideHeld!!.countDown()
        withTimeout(10_000) { refresh.join() }
        assertEquals("the guide came too", SyncState.Idle, sync.state.value)
        assertEquals(null, epg.plexGuideError(source.id))
    }

    @Test
    fun `when the films fail, the channels and guide still come`() = runBlocking {
        fake.libraryUp = false
        assertTrue(sync.refreshNow(source.id).isFailure)
        assertEquals(2, db.channels().idsAll(source.id).size)
        assertTrue(sync.state.value is SyncState.Failed)
        val guide = db.epg().providerSource(source.id)!!
        assertTrue("the guide is saved", guide.lastImportAt > 0 && guide.lastError == null)
    }
}
