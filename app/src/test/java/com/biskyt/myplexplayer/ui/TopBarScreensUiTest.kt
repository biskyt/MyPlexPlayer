package com.biskyt.myplexplayer.ui

import androidx.compose.runtime.remember
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import com.biskyt.myplexplayer.data.db.CAT_MOVIE
import com.biskyt.myplexplayer.testutil.FakePlex
import com.biskyt.myplexplayer.testutil.Navigators
import com.biskyt.myplexplayer.testutil.TestApp
import com.biskyt.myplexplayer.testutil.addSyncedHomePlex
import com.biskyt.myplexplayer.testutil.createUiComposeRule
import com.biskyt.myplexplayer.testutil.settle
import com.biskyt.myplexplayer.testutil.waitAdvancingClock
import com.biskyt.myplexplayer.testutil.waitForText
import com.biskyt.myplexplayer.ui.player.VodPlayerViewModel
import com.biskyt.myplexplayer.ui.theme.MyPlexTheme
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

/** The top bar (tabs, search, settings) is on every screen but playback and first-run set-up. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestApp::class, qualifiers = "+w960dp-h540dp-tvdpi")
class TopBarScreensUiTest {
    @get:Rule val compose = createUiComposeRule()

    @get:Rule(order = Navigators.ORDER)
    val navigators = Navigators()

    private val plex = FakePlex()
    private val app = ApplicationProvider.getApplicationContext<TestApp>()
    private var playlistId = 0L

    @Before
    fun setUp() = runBlocking {
        // With art, which goes behind the top bar.
        plex.movies = listOf(FakePlex.film("11", "Big Buck Bunny", 2008, art = "/library/metadata/11/art/1"))
        playlistId = app.container.addSyncedHomePlex(plex)
    }

    @After
    fun tearDown() = plex.close()

    private fun show(screen: Screen, hasPlaylist: Boolean = true): Navigator {
        val navigator = navigators.new(screen)
        compose.setContent { MyPlexTheme { NavHost(remember { navigator }) { ScreenContent(it, hasPlaylist) } } }
        return navigator
    }

    private fun topBars() = compose.onAllNodes(hasContentDescription("Search")).fetchSemanticsNodes().size

    private fun hasTopBar(screen: Screen, text: String) {
        show(screen)
        compose.waitForText(text)
        compose.waitAdvancingClock { topBars() == 1 }
    }

    @Test
    fun `a film's page has the top bar`() = hasTopBar(Screen.MovieDetails(playlistId, 11), "Big Buck Bunny")

    @Test
    fun `a series' page has the top bar`() = hasTopBar(Screen.SeriesDetails(playlistId, 21), "Test Show")

    @Test
    fun `a film's art goes behind the top bar, its details below it`() {
        hasTopBar(Screen.MovieDetails(playlistId, 11), "Big Buck Bunny")
        compose.waitAdvancingClock { compose.onAllNodes(hasTestTag("backdrop")).fetchSemanticsNodes().isNotEmpty() }
        val art = compose.onNode(hasTestTag("backdrop")).fetchSemanticsNode().boundsInRoot
        val bar = compose.onNode(hasContentDescription("Search")).fetchSemanticsNode().boundsInRoot
        val title = compose.onNode(hasText("Big Buck Bunny")).fetchSemanticsNode().boundsInRoot
        assertEquals("the art starts at the top", 0f, art.top)
        assertTrue("the details start below the bar", title.top > bar.bottom)
    }

    @Test
    fun `more info has the top bar`() = hasTopBar(Screen.MoreInfo(playlistId, CAT_MOVIE, 11), "Big Buck Bunny")

    @Test
    fun `settings has the top bar`() = hasTopBar(Screen.Settings, "Playlists")

    @Test
    fun `the servers' page has the top bar`() = hasTopBar(Screen.Playlists, "Update now")

    @Test
    fun `languages and categories have the top bar`() = hasTopBar(Screen.Categories(playlistId), "Languages & categories")

    @Test
    fun `appearance has the top bar`() = hasTopBar(Screen.Appearance, "Appearance")

    @Test
    fun `backup has the top bar`() = hasTopBar(Screen.Backup, "Back up now")

    @Test
    fun `first-run set-up has no top bar`() {
        show(Screen.PlexSignIn(firstRun = true))
        compose.settle()
        assertEquals(0, topBars())
    }

    @Test
    fun `before there is a playlist there is no top bar`() {
        show(Screen.Backup, hasPlaylist = false)
        compose.settle()
        assertEquals(0, topBars())
    }

    @Test
    fun `playback has no top bar`() {
        val navigator = show(Screen.VodPlayer(playlistId, 11))
        val vm = ViewModelProvider(navigator.current)[VodPlayerViewModel::class.java]
        compose.waitAdvancingClock { vm.engine?.player?.currentMediaItem != null }
        compose.settle()
        assertEquals(0, topBars())
    }
}
