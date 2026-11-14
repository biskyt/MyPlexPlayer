package com.biskyt.myplexplayer.data

import androidx.test.core.app.ApplicationProvider
import com.biskyt.myplexplayer.data.db.ChannelEntity
import com.biskyt.myplexplayer.data.db.FavouriteEntity
import com.biskyt.myplexplayer.data.db.VOD_EPISODE
import com.biskyt.myplexplayer.data.db.VOD_MOVIE
import com.biskyt.myplexplayer.data.db.WatchProgressEntity
import com.biskyt.myplexplayer.launcher.LauncherRows
import com.biskyt.myplexplayer.launcher.LauncherWriter
import com.biskyt.myplexplayer.testutil.FakePlex
import com.biskyt.myplexplayer.testutil.TestApp
import com.biskyt.myplexplayer.testutil.addHomePlex
import com.biskyt.myplexplayer.testutil.eventually
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Continue watching and the favourite channels on the TV's home screen (the owner, 2026-11-14): off by default, with
 * nothing written while off; on, the active server's cards go to the launcher; off again, they are taken off.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestApp::class)
class LauncherSyncTest {
    private class Writer : LauncherWriter {
        val published = CopyOnWriteArrayList<LauncherRows>()

        @Volatile var cleared = 0

        @Volatile var on = false

        override fun isOn() = on

        override fun publish(rows: LauncherRows): Long? {
            on = true
            published += rows
            return if (published.size == 1) CHANNEL else null
        }

        override fun clear() {
            on = false
            cleared++
        }
    }

    private val plex = FakePlex()
    private val app = ApplicationProvider.getApplicationContext<TestApp>()
    private val c get() = app.container
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val writer = Writer()
    private var pid = 0L

    @Before
    fun setUp() = runBlocking<Unit> {
        pid = c.addHomePlex(plex)
        val now = System.currentTimeMillis()
        // Two films part watched, the second played last: first on Continue watching.
        listOf(501L, 601L).forEachIndexed { i, id ->
            c.vod.saveProgress(
                WatchProgressEntity(pid, VOD_MOVIE, id, 600_000, 6_000_000, now + i, "Film $id", "http://img/$id.jpg", pending = true)
            )
        }
        // Episode 2 of a series watched to its end, the next one not known yet: up next.
        c.vod.saveProgress(
            WatchProgressEntity(
                pid, VOD_EPISODE, 9002, 1_440_000, 1_440_000, now - 1, "Line of Duty", "http://img/s77.jpg",
                seriesId = 77, season = 1, episode = 2, episodeTitle = "Second", hasNext = true, pending = true
            )
        )
        // Two channels, one of them a favourite.
        c.db.channels().upsert(listOf(101L, 102L).map { ChannelEntity(pid, it, it.toInt(), "Channel $it", "1", null, null) })
        c.db.favourites().add(FavouriteEntity(pid, 102, now))
    }

    @After
    fun tearDown() {
        scope.cancel()
        plex.close()
    }

    @Test
    fun `off by default, nothing is written`() = runBlocking {
        LauncherSync(app, scope, c.settings, c.db, writer)
        delay(1_000)
        assertEquals(0, writer.published.size)
        assertEquals("nothing to take off", 0, writer.cleared)
    }

    @Test
    fun `on, the active server's cards go to the launcher, and off they come off`() = runBlocking {
        val sync = LauncherSync(app, scope, c.settings, c.db, writer)
        c.settings.update { it.copy(launcherRows = true) }
        eventually("cards written") { writer.published.isNotEmpty() }
        val rows = writer.published.last()
        assertEquals(listOf("$pid:$VOD_MOVIE:601", "$pid:$VOD_MOVIE:501", "$pid:$VOD_EPISODE:9002"), rows.playNext.map { it.key })
        assertEquals("http://img/601.jpg", rows.playNext.first().posterUrl)
        val upNext = rows.playNext.last()
        assertTrue("a finished episode with another after it is up next", upNext.upNext && upNext.episode == null)
        val channel = checkNotNull(rows.channel) { "the favourites' row" }
        assertEquals(listOf("$pid:102"), channel.cards.map { it.key })
        assertEquals("Favourite channels", channel.name)
        eventually("the channel to show") { sync.madeChannel.value == CHANNEL }

        // A card opens the app on it, if it still may.
        val target = LauncherSync.target(app, rows.playNext.first().open)
        assertEquals(LauncherTarget(VOD_MOVIE, pid, 601), target)
        assertTrue(sync.allows(target!!))
        assertFalse("a film with no place saved does not open", sync.allows(LauncherTarget(VOD_MOVIE, pid, 777)))
        c.vod.forgetProgress(c.vod.progress(pid, VOD_MOVIE, 501)!!)
        assertFalse("a film taken off Continue watching does not open", sync.allows(LauncherTarget(VOD_MOVIE, pid, 501)))
        assertFalse("a channel no longer a favourite does not open", sync.allows(LauncherTarget(LauncherTarget.CHANNEL, pid, 101)))
        assertTrue(sync.allows(LauncherTarget(LauncherTarget.CHANNEL, pid, 102)))
        assertNotNull(LauncherSync.target(app, channel.cards.single().open))

        c.settings.update { it.copy(launcherRows = false) }
        eventually("cards taken off") { writer.cleared == 1 }
    }

    @Test
    fun `with no favourite channels there is no row, not an empty one`() = runBlocking {
        // None left (as on a server with no Live TV: it has no channels).
        c.db.favourites().remove(pid, 102)
        LauncherSync(app, scope, c.settings, c.db, writer)
        c.settings.update { it.copy(launcherRows = true) }
        eventually("cards written") { writer.published.isNotEmpty() }
        assertEquals(null, writer.published.last().channel)
        assertEquals(3, writer.published.last().playNext.size)
    }

    private companion object {
        const val CHANNEL = 7L
    }
}
