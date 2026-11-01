package com.biskyt.myplexplayer.ui

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.core.app.ApplicationProvider
import com.biskyt.myplexplayer.data.db.CAT_MOVIE
import com.biskyt.myplexplayer.testutil.FakePlex
import com.biskyt.myplexplayer.testutil.Navigators
import com.biskyt.myplexplayer.testutil.TestApp
import com.biskyt.myplexplayer.testutil.addSyncedHomePlex
import com.biskyt.myplexplayer.testutil.createUiComposeRule
import com.biskyt.myplexplayer.testutil.waitAdvancingClock
import com.biskyt.myplexplayer.testutil.waitForText
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

/** Home's background: the picture of the film or series whose card has the remote, as on its page; none for channels. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestApp::class, qualifiers = "+w960dp-h540dp-tvdpi")
class HomeBackdropUiTest {
    @get:Rule val compose = createUiComposeRule()

    @get:Rule(order = Navigators.ORDER)
    val navigators = Navigators()

    private val plex = FakePlex()
    private val app = ApplicationProvider.getApplicationContext<TestApp>()
    private var source = 0L

    @Before
    fun setUp() = runBlocking {
        plex.movies = listOf(FakePlex.film("11", "Big Buck Bunny", 2008, art = "/library/metadata/11/art/1"))
        plex.shows = listOf(FakePlex.show("21", "Test Show", 2020, art = "/library/metadata/21/art/1"))
        plex.favouriteChannels = listOf("ch-1")
        source = app.container.addSyncedHomePlex(plex)
    }

    @After
    fun tearDown() = plex.close()

    private fun backdrops() = compose.onAllNodes(hasTestTag("backdrop"), useUnmergedTree = true).fetchSemanticsNodes().size

    private fun focus(text: String) =
        compose.onAllNodes(hasText(text, substring = true) and hasClickAction())[0].performSemanticsAction(SemanticsActions.RequestFocus)

    @Test
    fun `the card with the remote has its picture behind Home, and a channel's none`() {
        compose.setContent { MyPlexTheme { NavHost(navigators.new(Screen.Home)) { ScreenContent(it) } } }
        compose.waitForText("Big Buck Bunny", stepMs = 50)
        focus("Big Buck Bunny")
        compose.waitAdvancingClock(stepMs = 100) { backdrops() == 1 }
        // Live TV's row is last, below the screen at first.
        compose.onNode(hasScrollToNodeAction() and SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange))
            .performScrollToNode(hasText("Your channels, on now"))
        focus("One")
        compose.waitAdvancingClock(stepMs = 100) { backdrops() == 0 }
        // A series' picture too (none showed just before).
        focus("Test Show")
        compose.waitAdvancingClock(stepMs = 100) { backdrops() == 1 }
    }

    @Test
    fun `the picture is the one its page shows, so the server sizes it once`() = runBlocking {
        val home = app.container.vod.backdropOf(source, CAT_MOVIE, 11)
        val page = app.container.vod.movieDetails(app.container.db.playlists().get(source)!!, 11).backdropUrl
        assertTrue(home != null && "1280" in home)
        assertEquals(page, home)
    }
}
