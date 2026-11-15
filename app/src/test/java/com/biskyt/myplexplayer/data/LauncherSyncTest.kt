package com.biskyt.myplexplayer.data

import androidx.test.core.app.ApplicationProvider
import com.biskyt.myplexplayer.data.db.CAT_MOVIE
import com.biskyt.myplexplayer.data.db.CAT_SERIES
import com.biskyt.myplexplayer.data.db.ChannelEntity
import com.biskyt.myplexplayer.data.db.FavouriteEntity
import com.biskyt.myplexplayer.data.db.VOD_EPISODE
import com.biskyt.myplexplayer.data.db.VOD_MOVIE
import com.biskyt.myplexplayer.data.db.WatchProgressEntity
import com.biskyt.myplexplayer.launcher.LauncherRows
import com.biskyt.myplexplayer.launcher.LauncherWriter
import com.biskyt.myplexplayer.launcher.Published
import com.biskyt.myplexplayer.launcher.RowCard
import com.biskyt.myplexplayer.testutil.FakePlex
import com.biskyt.myplexplayer.testutil.TestApp
import com.biskyt.myplexplayer.testutil.addHomePlex
import com.biskyt.myplexplayer.testutil.eventually
import com.biskyt.myplexplayer.testutil.plexChannel
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

        /** The rows on the launcher now, by key, as the TV provider keeps them. */
        private val present = mutableSetOf<String>()

        override fun publish(rows: LauncherRows): Published {
            on = true
            published += rows
            val keys = rows.channels.map { it.key }
            val made = keys - present
            present.clear()
            present += keys
            return Published(keys.map(ID::getValue).toSet(), made.map(ID::getValue).toSet())
        }

        override fun clear() {
            on = false
            present.clear()
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
        val channel = rows.channels.single { it.key == LauncherTarget.FAVOURITES }
        assertEquals("no other titles here: no rows for them", 1, rows.channels.size)
        assertEquals(listOf("$pid:102"), channel.cards.map { it.key })
        assertEquals("Favourite channels", channel.name)
        eventually("the row to show") { sync.madeChannels.value == setOf(CHANNELS) }

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
        assertEquals(emptyList<Any>(), writer.published.last().channels)
        assertEquals(3, writer.published.last().playNext.size)
    }

    @Test
    fun `a row made and then taken away is not asked to show`() = runBlocking {
        val sync = LauncherSync(app, scope, c.settings, c.db, writer)
        c.settings.update { it.copy(launcherRows = true) }
        eventually("the row to show") { sync.madeChannels.value == setOf(CHANNELS) }
        // Written again with the row still there (none made now): it is still to be shown.
        sync.republish()
        eventually("written again") { writer.published.size == 2 }
        assertEquals(setOf(CHANNELS), sync.madeChannels.value)
        // The last favourite gone before Settings showed the row: the cards are written again, with no row.
        c.db.favourites().remove(pid, 102)
        sync.republish()
        eventually("no row to show") { sync.madeChannels.value.isEmpty() }
        assertEquals(emptyList<Any>(), writer.published.last().channels)
    }

    @Test
    fun `Home's rows of titles are rows of posters, in Home's order, that open their pages`() = runBlocking {
        // The server's film (Big Buck Bunny, 11) and series (Test Show, 21), both new and both favourites.
        c.sync.refreshNow(pid).getOrThrow()
        c.vod.setFavourite(pid, CAT_MOVIE, 11, true)
        c.vod.setFavourite(pid, CAT_SERIES, 21, true)
        // The sync replaced the channels and their favourites with the server's (none): one of its channels again.
        c.db.favourites().add(FavouriteEntity(pid, plexChannel("1"), System.currentTimeMillis()))
        val sync = LauncherSync(app, scope, c.settings, c.db, writer)
        c.settings.update { it.copy(launcherRows = true) }
        val keys = ID.keys.toList()
        eventually("all five rows") { writer.published.lastOrNull()?.channels?.map { it.key } == keys }
        val rows = writer.published.last().channels.associateBy { it.key }
        assertEquals("Recently added series", rows.getValue(LauncherTarget.NEWEST_SERIES).name)
        assertEquals("Recently added movies", rows.getValue(LauncherTarget.NEWEST_MOVIES).name)
        val newMovie = rows.getValue(LauncherTarget.NEWEST_MOVIES).cards.single { it.key == "$pid:${LauncherTarget.MOVIE_PAGE}:11" }
        assertEquals(RowCard.Kind.MOVIE, newMovie.kind)
        val newSeries = rows.getValue(LauncherTarget.NEWEST_SERIES).cards.single { it.key == "$pid:${LauncherTarget.SERIES_PAGE}:21" }
        assertEquals(RowCard.Kind.SERIES, newSeries.kind)
        eventually("all five rows to show") { sync.madeChannels.value == ID.values.toSet() }

        // A card opens its page, and a row's own link its list, if they still may.
        val page = LauncherSync.target(app, newMovie.open)
        assertEquals(LauncherTarget(LauncherTarget.MOVIE_PAGE, pid, 11), page)
        assertTrue(sync.allows(page!!))
        assertEquals(LauncherTarget(LauncherTarget.SERIES_PAGE, pid, 21), LauncherSync.target(app, newSeries.open))
        assertEquals(
            LauncherTarget(LauncherTarget.FAVOURITE_SERIES, pid),
            LauncherSync.target(app, rows.getValue(LauncherTarget.FAVOURITE_SERIES).open)
        )
        assertFalse("a film no longer here does not open", sync.allows(LauncherTarget(LauncherTarget.MOVIE_PAGE, pid, 777)))

        // The last favourite film gone: its row comes off, and the others stay. (Written again at once: changes are
        // otherwise written once they stop.)
        c.vod.setFavourite(pid, CAT_MOVIE, 11, false)
        sync.republish()
        eventually("the favourite films' row taken off") {
            writer.published.last().channels.map { it.key } == keys - LauncherTarget.FAVOURITE_MOVIES
        }
    }

    private companion object {
        const val CHANNELS = 7L
        val ID = mapOf(
            LauncherTarget.NEWEST_SERIES to 3L,
            LauncherTarget.NEWEST_MOVIES to 4L,
            LauncherTarget.FAVOURITE_SERIES to 5L,
            LauncherTarget.FAVOURITE_MOVIES to 6L,
            LauncherTarget.FAVOURITES to CHANNELS,
        )
    }
}
