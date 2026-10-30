package com.biskyt.myplexplayer.ui

import androidx.compose.runtime.remember
import androidx.compose.ui.test.hasContentDescription
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
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
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The top bar (tabs, search, settings) is on a film's or series' page too, and not while something plays. */
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
        playlistId = app.container.addSyncedHomePlex(plex)
    }

    @After
    fun tearDown() = plex.close()

    private fun show(screen: Screen): Navigator {
        val navigator = navigators.new(screen)
        compose.setContent { MyPlexTheme { NavHost(remember { navigator }) { ScreenContent(it) } } }
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
    fun `playback has no top bar`() {
        val navigator = show(Screen.VodPlayer(playlistId, 11))
        val vm = ViewModelProvider(navigator.current)[VodPlayerViewModel::class.java]
        compose.waitAdvancingClock { vm.engine?.player?.currentMediaItem != null }
        compose.settle()
        assertEquals(0, topBars())
    }
}
