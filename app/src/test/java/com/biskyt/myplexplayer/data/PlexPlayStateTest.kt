package com.biskyt.myplexplayer.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.biskyt.myplexplayer.core.plex.PlexPlayState
import com.biskyt.myplexplayer.data.db.AppDatabase
import com.biskyt.myplexplayer.data.db.PlaylistEntity
import com.biskyt.myplexplayer.data.db.VOD_EPISODE
import com.biskyt.myplexplayer.data.db.VOD_MOVIE
import com.biskyt.myplexplayer.data.db.WatchProgressEntity
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Play state kept in Plex: brought in by sync and title pages, reported back by playback. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = com.biskyt.myplexplayer.testutil.PlainApp::class)
class PlexPlayStateTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val fake = FakePlex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var db: AppDatabase
    private lateinit var plex: PlexSources
    private lateinit var vod: VodRepository
    private lateinit var source: PlaylistEntity

    /** Minutes ago, in seconds since 1970 (as the server gives times). */
    private fun minutesAgo(minutes: Int) = System.currentTimeMillis() / 1000 - minutes * 60

    @Before
    fun setUp() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        val http = OkHttpClient()
        val settings = SettingsRepository(context)
        val categoryStore = CategoryStore(db) { Locale.UK }
        val playlists =
            PlaylistRepository(db, http, settings, EpgRepository(db, http, context.cacheDir.resolve("test-epg"), scope), categoryStore)
        plex = PlexSources(db, http, settings).apply { plexTv = fake.url }
        vod = VodRepository(db, playlists, categoryStore, plex)
        plex.signIn(FakePlex.ACCOUNT_TOKEN)
        source = db.playlists().all().single { it.machineId == FakePlex.MACHINE_ID }
    }

    @After
    fun tearDown() {
        scope.cancel()
        db.close()
        fake.close()
    }

    private suspend fun progress(kind: String, id: Long) = db.vod().progress(source.id, kind, id)

    private suspend fun carryOn() = vod.observeCarryOn(source.id).first().map { it.kind to it.itemId }

    @Test
    fun `sync brings in how far each film got, what's watched, and the unwatched episodes`() = runBlocking {
        fake.viewOffset["11"] = 120_000
        fake.lastViewedAt["11"] = minutesAgo(60)
        fake.watched += "12"
        fake.watched += "211"
        vod.sync(source)

        val bunny = progress(VOD_MOVIE, 11)!!
        assertEquals(120_000L, bunny.positionMs)
        assertTrue("when it was played", kotlin.math.abs(minutesAgo(60) * 1000 - bunny.updatedAt) < 2000)
        assertTrue(progress(VOD_MOVIE, 12)!!.finished)
        assertEquals(1, db.vod().searchSeries(source.id, "Test", 10).single().unwatched)
    }

    @Test
    fun `carry on watching is the server's continue watching, in its order, with the episode it suggests next`() = runBlocking {
        fake.viewOffset["11"] = 120_000
        fake.lastViewedAt["11"] = minutesAgo(60)
        fake.watched += "211"
        fake.lastViewedAt["211"] = minutesAgo(30)
        fake.continueWatching = listOf("212", "11") // the next episode, then the film
        vod.sync(source)

        assertEquals(listOf(VOD_EPISODE to 212L, VOD_MOVIE to 11L), carryOn())
        val next = progress(VOD_EPISODE, 212)!!
        assertEquals(0L, next.positionMs)
        assertEquals(21L, next.seriesId)
        assertEquals("Test Show", next.title)

        // Taken off Continue Watching elsewhere: it leaves the row here, keeping how far it got.
        fake.continueWatching = listOf("212")
        vod.sync(source)
        assertEquals(listOf(VOD_EPISODE to 212L), carryOn())
        assertEquals(120_000L, progress(VOD_MOVIE, 11)!!.positionMs)
    }

    @Test
    fun `taken off carry on watching here, it comes off the server's too, and keeps how far it got`() = runBlocking {
        fake.viewOffset["11"] = 120_000
        fake.lastViewedAt["11"] = minutesAgo(60)
        fake.continueWatching = listOf("11")
        vod.sync(source)
        vod.forgetProgress(progress(VOD_MOVIE, 11)!!)

        assertEquals(emptyList<Any>(), carryOn())
        assertEquals(emptyList<String>(), fake.continueWatching)
        assertEquals(120_000L, progress(VOD_MOVIE, 11)!!.positionMs)
    }

    @Test
    fun `a series taken off carry on watching comes back when one of its episodes is played`() = runBlocking {
        fake.viewOffset["211"] = 120_000
        fake.lastViewedAt["211"] = minutesAgo(60)
        fake.continueWatching = listOf("211")
        vod.sync(source)
        vod.forgetProgress(progress(VOD_EPISODE, 211)!!)
        assertEquals(emptyList<Any>(), carryOn())

        val stream = vod.episodeStream(source, 212, "mkv")
        val played = row(VOD_EPISODE, 212, updatedAt = System.currentTimeMillis()).copy(seriesId = 21)
        vod.saveProgress(played, stream, playing = true, stopped = false)
        assertEquals("at once, from playing here", listOf(VOD_EPISODE to 212L), carryOn())
        vod.sync(source)
        assertEquals("and from the server's list", listOf(VOD_EPISODE to 212L), carryOn())
    }

    @Test
    fun `marked unwatched elsewhere, a film's point goes`() = runBlocking {
        db.vod().saveProgress(listOf(row(VOD_MOVIE, 11, updatedAt = 1_000)))
        vod.sync(source) // the server hasn't started it
        assertNull(progress(VOD_MOVIE, 11))
    }

    @Test
    fun `progress that didn't reach the server is kept, and sent at the next sync`() = runBlocking {
        vod.sync(source)
        val stream = vod.movieStream(source, db.vod().movie(source.id, 11)!!)
        val played = row(VOD_MOVIE, 11, updatedAt = System.currentTimeMillis(), positionMs = 200_000)
        fake.close() // out of reach
        vod.saveProgress(played, stream, playing = true, stopped = false)
        assertTrue(progress(VOD_MOVIE, 11)!!.pending)

        val back = FakePlex()
        try {
            db.playlists().upsert(source.copy(server = back.url))
            source = db.playlists().get(source.id)!!
            back.viewOffset["11"] = 30_000 // older, from before
            vod.sync(source)
            assertEquals(listOf(listOf("11", "stopped", "200000")), back.reports.map { it.take(3) })
            val now = progress(VOD_MOVIE, 11)!!
            assertFalse(now.pending)
            assertEquals(200_000L, now.positionMs)
        } finally {
            back.close()
        }
    }

    @Test
    fun `a series' page brings in its episodes' state and its badge`() = runBlocking {
        vod.sync(source)
        fake.watched += "211"
        fake.viewOffset["212"] = 90_000
        fake.lastViewedAt["212"] = minutesAgo(5)
        vod.seriesDetails(source, 21)

        assertTrue(progress(VOD_EPISODE, 211)!!.finished)
        val second = progress(VOD_EPISODE, 212)!!
        assertEquals(90_000L, second.positionMs)
        assertEquals(21L, second.seriesId)
        assertNull("only the server's list puts it on Carry on watching", second.carryOnAt)
        assertEquals(1, db.vod().searchSeries(source.id, "Test", 10).single().unwatched)
    }

    @Test
    fun `playback reports to the server in one session, its end once, and the series' badge follows`() = runBlocking {
        vod.sync(source)
        assertEquals(2, db.vod().searchSeries(source.id, "Test", 10).single().unwatched)
        val playback = plex.playback(source, 211, VOD_EPISODE)
        assertTrue(playback.url.startsWith(fake.url + "/library/parts/2101/"))
        playback.report(PlexPlayState.PLAYING, 60_000, takenAt = 1) {}
        playback.report(PlexPlayState.STOPPED, 590_000, takenAt = 2) {}
        playback.report(PlexPlayState.STOPPED, 590_000, takenAt = 3) {} // the player's stop after its end: not sent again
        assertNull("one taken before the last is dropped", playback.report(PlexPlayState.PLAYING, 1, takenAt = 1) {})

        assertEquals(listOf("playing", "stopped"), fake.reports.map { it[1] })
        assertEquals(1, fake.reports.map { it[3] }.distinct().size)
        assertTrue("211" in fake.watched)
        assertEquals(1, db.vod().searchSeries(source.id, "Test", 10).single().unwatched)
    }

    @Test
    fun `starting playback brings in the server's point, for the player to resume from`() = runBlocking {
        fake.viewOffset["12"] = 300_000
        fake.lastViewedAt["12"] = minutesAgo(10)
        db.vod().saveProgress(listOf(row(VOD_MOVIE, 12, updatedAt = 1_000, positionMs = 100_000)))
        plex.playback(source, 12, VOD_MOVIE)
        assertEquals(300_000L, progress(VOD_MOVIE, 12)!!.positionMs)
    }

    @Test
    fun `a playback report that can't reach the server says so, and so do the saves after its end`() = runBlocking {
        val playback = plex.playback(source, 11, VOD_MOVIE)
        fake.close()
        assertEquals(Sent.NOT_YET, playback.report(PlexPlayState.STOPPED, 60_000, takenAt = 1) {})
        assertEquals(Sent.NOT_YET, playback.report(PlexPlayState.PAUSED, 60_000, takenAt = 2) {})
    }

    @Test
    fun `progress the server refuses (the title gone from it) isn't sent again`() = runBlocking {
        vod.sync(source)
        db.vod().saveProgress(listOf(row(VOD_MOVIE, 99, updatedAt = 1_000).copy(pending = true)))
        vod.sync(source) // the server answers 404 for a title it doesn't have
        assertFalse(progress(VOD_MOVIE, 99)!!.pending)
    }

    @Test
    fun `a series' page keeps the episode suggested next on carry on watching, and what follows each`() = runBlocking {
        fake.watched += "211"
        fake.continueWatching = listOf("212")
        vod.sync(source)
        vod.seriesDetails(source, 21)
        assertEquals(listOf(VOD_EPISODE to 212L), carryOn())
        assertTrue(progress(VOD_EPISODE, 211)!!.hasNext)
        plex.playback(source, 211, VOD_EPISODE) // knows nothing of what follows: keeps it
        assertTrue(progress(VOD_EPISODE, 211)!!.hasNext)
    }

    @Test
    fun `continue watching keeps the server's order, an unplayed suggestion just after the one ahead`() {
        assertEquals(listOf(899L, 898L, 500L, 499L), PlexProgress.hubOrder(listOf(null, null, 500L, null), now = 900))
        assertEquals(listOf(900L), PlexProgress.hubOrder(listOf(2_000L), now = 900)) // never ahead of now
    }

    private fun row(kind: String, id: Long, updatedAt: Long, positionMs: Long = 60_000) = WatchProgressEntity(
        playlistId = source.id,
        kind = kind,
        itemId = id,
        positionMs = positionMs,
        durationMs = 600_000,
        updatedAt = updatedAt,
        title = "t",
        imageUrl = null,
    )
}
