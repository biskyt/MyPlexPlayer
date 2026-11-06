package com.biskyt.myplexplayer.ui

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.core.app.ApplicationProvider
import com.biskyt.myplexplayer.testutil.FakePlex
import com.biskyt.myplexplayer.testutil.Navigators
import com.biskyt.myplexplayer.testutil.TestApp
import com.biskyt.myplexplayer.testutil.addHomePlex
import com.biskyt.myplexplayer.testutil.createUiComposeRule
import com.biskyt.myplexplayer.testutil.focusedText
import com.biskyt.myplexplayer.testutil.pressAndRead
import com.biskyt.myplexplayer.testutil.settle
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

/**
 * Focus on the TV Shows screens (the owner, 2026-11-06): the tab opens on a show, back from one is on it again, and
 * Right from a series page's buttons goes into a short episode list, at its first unplayed episode.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestApp::class, qualifiers = "+w960dp-h540dp-tvdpi")
class VodFocusUiTest {
    @get:Rule val compose = createUiComposeRule()

    @get:Rule(order = Navigators.ORDER)
    val navigators = Navigators()

    private val plex = FakePlex()
    private val app = ApplicationProvider.getApplicationContext<TestApp>()
    private var source = 0L

    @Before
    fun setUp() = runBlocking {
        // Two shows; the first episode of Test Show is watched to the end.
        plex.shows = plex.shows + FakePlex.show("22", "Other Show", 2021, addedAt = 1_700_000_300)
        plex.watched += "211"
        plex.lastViewedAt["211"] = 1_700_000_500
        source = app.container.addHomePlex(plex)
    }

    @After
    fun tearDown() = plex.close()

    private fun loadShows() = runBlocking { app.container.vod.sync(app.container.db.playlists().get(source)!!) }

    private fun show(start: Screen): Navigator {
        val navigator = navigators.new(start)
        compose.setContent { MyPlexTheme { NavHost(navigator) { ScreenContent(it) } } }
        return navigator
    }

    private fun onAShow() = "Test Show" in compose.focusedText() || "Other Show" in compose.focusedText()

    @Test
    fun `the TV Shows tab opens on a show, not the menu or the pills`() {
        loadShows()
        show(Screen.Series())
        compose.waitForText("Other Show", stepMs = 50)
        compose.waitAdvancingClock(stepMs = 50) { compose.focusedText().isNotEmpty() }
        assertTrue("Focus is on: ${compose.focusedText()}", onAShow())
    }

    @Test
    fun `shows that arrive after a key press do not take focus`() {
        // The shows not loaded yet: the tab shows its empty grid.
        show(Screen.Series())
        compose.waitForText("Load now", stepMs = 50)
        val moved = compose.pressAndRead(Key.DirectionDown)
        assertTrue("A key press gave focus to something", moved.isNotEmpty())

        loadShows()
        compose.waitForText("Other Show", stepMs = 50)
        compose.settle()
        assertEquals("Focus stays where the remote moved it", moved, compose.focusedText())
    }

    @Test
    fun `after Load now, the first show takes focus`() {
        show(Screen.Series())
        compose.waitForText("Load now", stepMs = 50)
        compose.onNode(hasText("Load now") and hasClickAction()).performSemanticsAction(SemanticsActions.RequestFocus)
        compose.waitAdvancingClock(stepMs = 50) { "Load now" in compose.focusedText() }
        compose.pressAndRead(Key.DirectionCenter)
        compose.waitForText("Other Show", stepMs = 50)
        compose.waitAdvancingClock(stepMs = 50) { onAShow() }
    }

    @Test
    fun `a show's options closed, focus is on that show`() {
        loadShows()
        show(Screen.Series())
        compose.waitForText("Other Show", stepMs = 50)
        compose.waitAdvancingClock(stepMs = 50) { onAShow() }
        repeat(4) { if ("Other Show" !in compose.focusedText()) compose.pressAndRead(Key.DirectionRight) }
        compose.onAllNodes(hasText("Other Show", substring = true) and hasClickAction())[0]
            .performSemanticsAction(SemanticsActions.OnLongClick)
        compose.waitForText("Add to favourites", stepMs = 50)
        compose.onNode(hasText("Add to favourites") and hasClickAction()).performSemanticsAction(SemanticsActions.OnClick)
        compose.waitAdvancingClock(stepMs = 50) { compose.onAllNodes(hasText("Add to favourites")).fetchSemanticsNodes().isEmpty() }
        compose.settle()
        assertTrue("Focus is on: ${compose.focusedText()}", "Other Show" in compose.focusedText())
    }

    @Test
    fun `back from a show, focus is on it again`() {
        loadShows()
        val navigator = show(Screen.Series())
        compose.waitForText("Other Show", stepMs = 50)
        compose.waitAdvancingClock(stepMs = 50) { onAShow() }
        // The second poster: not the one the tab opens on.
        val first = compose.focusedText()
        val second = compose.pressAndRead(Key.DirectionRight)
        assertTrue("Right from $first went to: $second", second != first && onAShow())
        compose.pressAndRead(Key.DirectionCenter)
        compose.waitAdvancingClock(stepMs = 50) { navigator.current.screen is Screen.SeriesDetails }
        compose.waitAdvancingClock(stepMs = 50) { compose.focusedText().isNotEmpty() }

        compose.runOnUiThread { navigator.pop() }
        compose.waitAdvancingClock(stepMs = 50) { onAShow() }
        assertTrue("Back on TV Shows, focus is on: ${compose.focusedText()}", compose.focusedText() == second)
    }

    @Test
    fun `right from the series page's buttons goes to the first unplayed episode`() {
        loadShows()
        show(Screen.SeriesDetails(source, 21))
        compose.waitAdvancingClock(stepMs = 50) { "S1 E2" in compose.focusedText() }
        val right = compose.pressAndRead(Key.DirectionRight)
        assertTrue("Right from Continue went to: $right", "Second" in right)
    }
}
