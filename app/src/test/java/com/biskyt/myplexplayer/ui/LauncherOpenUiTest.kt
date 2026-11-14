package com.biskyt.myplexplayer.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.test.core.app.ApplicationProvider
import com.biskyt.myplexplayer.data.LauncherTarget
import com.biskyt.myplexplayer.data.db.FavouriteEntity
import com.biskyt.myplexplayer.data.db.VOD_MOVIE
import com.biskyt.myplexplayer.data.db.WatchProgressEntity
import com.biskyt.myplexplayer.testutil.FakePlex
import com.biskyt.myplexplayer.testutil.TestApp
import com.biskyt.myplexplayer.testutil.addSyncedHomePlex
import com.biskyt.myplexplayer.testutil.createUiComposeRule
import com.biskyt.myplexplayer.testutil.plexChannel
import com.biskyt.myplexplayer.testutil.settle
import com.biskyt.myplexplayer.testutil.waitAdvancingClock
import com.biskyt.myplexplayer.testutil.waitForText
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A card on the TV's home screen opens the app on its channel, film or episode, if it still may (see LauncherSync). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestApp::class, qualifiers = "+w960dp-h540dp-tvdpi")
class LauncherOpenUiTest {
    @get:Rule val compose = createUiComposeRule<ComponentActivity>()

    private val plex = FakePlex()
    private val app = ApplicationProvider.getApplicationContext<TestApp>()
    private var pid = 0L

    @Before
    fun setUp() = runBlocking {
        pid = app.container.addSyncedHomePlex(plex)
        app.container.db.favourites().add(FavouriteEntity(pid, plexChannel("1"), System.currentTimeMillis()))
        // Big Buck Bunny (11) part watched.
        app.container.vod.saveProgress(
            WatchProgressEntity(pid, VOD_MOVIE, 11, 600_000, 6_000_000, System.currentTimeMillis(), "Big Buck Bunny", null, pending = true)
        )
    }

    @After
    fun tearDown() = plex.close()

    /** The app, opened from the card set: its player shows, and the card is taken. */
    private fun opensPlayer() {
        compose.setContent { AppRoot() }
        compose.waitAdvancingClock(50) { playerShown() }
        assertEquals("taken", null, app.container.launcherTarget.value)
    }

    private fun playerShown() =
        compose.onAllNodes(hasContentDescription("Skip back"), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun `a film's card opens the app on that film`() {
        app.container.launcherTarget.value = LauncherTarget(VOD_MOVIE, pid, 11)
        opensPlayer()
    }

    @Test
    fun `a card for another server switches to it and opens its film`() {
        // Another server in use (synced just now: no update of its own starts).
        runBlocking {
            val p = app.container.db.playlists().get(pid)!!
            val other = app.container.db.playlists().insert(p.copy(id = 0, name = "Other Plex", lastSyncAt = System.currentTimeMillis()))
            app.container.settings.update { it.copy(activePlaylistId = other) }
        }
        app.container.launcherTarget.value = LauncherTarget(VOD_MOVIE, pid, 11)
        compose.setContent { AppRoot() }
        compose.waitAdvancingClock(50) { playerShown() }
        assertEquals("the card's server is the one in use", pid, runBlocking { app.container.settings.current().activePlaylistId })
    }

    @Test
    fun `a favourite channel's card opens the app on that channel`() {
        app.container.launcherTarget.value = LauncherTarget(LauncherTarget.CHANNEL, pid, plexChannel("1"))
        opensPlayer()
    }

    @Test
    fun `a card for a channel no longer a favourite opens Home`() {
        app.container.launcherTarget.value = LauncherTarget(LauncherTarget.CHANNEL, pid, plexChannel("2"))
        compose.setContent { AppRoot() }
        compose.waitForText("Home", stepMs = 50)
        compose.waitAdvancingClock(50) { app.container.launcherTarget.value == null }
        compose.settle()
        assertTrue("no player", !playerShown())
        assertTrue(compose.onAllNodes(hasText("Home")).fetchSemanticsNodes().isNotEmpty())
    }
}
