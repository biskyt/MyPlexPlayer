package com.biskyt.myplexplayer.ui

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
import com.biskyt.myplexplayer.testutil.idleFrames
import com.biskyt.myplexplayer.testutil.waitAdvancingClock
import com.biskyt.myplexplayer.testutil.waitForText
import com.biskyt.myplexplayer.ui.theme.MyPlexTheme
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Deleting a film or an episode from its options (asked first), and a series' "Delete watched episodes". */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestApp::class, qualifiers = "+w960dp-h540dp-tvdpi")
class DeleteUiTest {
    @get:Rule val compose = createUiComposeRule()

    @get:Rule(order = Navigators.ORDER)
    val navigators = Navigators()

    private val plex = FakePlex()
    private val app = ApplicationProvider.getApplicationContext<TestApp>()
    private var source = 0L

    @Before
    fun setUp() = runBlocking {
        source = app.container.addHomePlex(plex)
        app.container.vod.sync(app.container.db.playlists().get(source)!!)
    }

    @After
    fun tearDown() = plex.close()

    private fun show(screen: Screen) {
        val navigator = navigators.new(screen)
        compose.setContent { MyPlexTheme { NavHost(navigator) { ScreenContent(it) } } }
    }

    /** Holds OK on [title] (a poster, or an episode) until its options show [shown]. */
    private fun options(title: String, shown: String) {
        compose.waitForText(title, stepMs = 50)
        compose.onAllNodes(hasText(title) and hasClickAction())[0].performSemanticsAction(SemanticsActions.OnLongClick)
        compose.waitForText(shown, stepMs = 50)
    }

    private fun click(text: String) = compose.onNode(hasText(text) and hasClickAction()).performSemanticsAction(SemanticsActions.OnClick)

    @Test
    fun `a film is deleted once confirmed, and not when kept`() {
        show(Screen.Movies())
        options("Big Buck Bunny", "Delete…")
        click("Delete…")
        compose.waitForText("Delete “Big Buck Bunny” from the Plex server?", stepMs = 50)
        // The safe answer has the remote.
        compose.waitAdvancingClock { "Keep it" in compose.focusedText() }
        click("Keep it")
        compose.waitForText("Delete…", stepMs = 50)
        assertTrue(plex.deleted.isEmpty())

        click("Delete…")
        compose.waitForText("Keep it", stepMs = 50)
        click("Delete")
        compose.waitAdvancingClock(stepMs = 50) {
            plex.deleted == listOf("11") && runBlocking { app.container.db.vod().movie(source, 11) } == null
        }
    }

    @Test
    fun `no delete where the server doesn't allow it`() {
        plex.allowsDeletion = false
        show(Screen.Movies())
        options("Big Buck Bunny", "Mark as watched")
        compose.waitAdvancingClock(stepMs = 50) { plex.deletionAsked > 0 }
        compose.idleFrames()
        assertEquals(0, compose.onAllNodes(hasText("Delete…")).fetchSemanticsNodes().size)
    }

    @Test
    fun `an episode is deleted from its series' page, and the series' delete watched episodes is set there`() {
        show(Screen.SeriesDetails(source, 21))
        options("Second", "◀ Never ▶")
        // Stepped twice: saved once the remote rests on it.
        click("◀ Never ▶")
        click("◀ After a day ▶")
        compose.waitForText("◀ After a week ▶", stepMs = 50)
        compose.waitAdvancingClock(stepMs = 100) { plex.autoDelete["21"] == 7 }

        click("Delete…")
        compose.waitForText("Keep it", stepMs = 50)
        click("Delete")
        compose.waitAdvancingClock(stepMs = 50) { plex.deleted == listOf("212") }
        compose.waitAdvancingClock(stepMs = 50) { compose.onAllNodes(hasText("Second")).fetchSemanticsNodes().isEmpty() }
        // The series' counts follow: one episode, unwatched.
        compose.waitAdvancingClock(stepMs = 50) { series()?.let { it.episodes == 1 && it.unwatched == 1 } == true }
    }

    private fun series() = runBlocking { app.container.db.vod().series(source, 21) }

    /** The TV Shows grid, then the series' page over it. */
    private fun seriesPage() {
        val navigator = navigators.new(Screen.Series())
        compose.setContent { MyPlexTheme { NavHost(navigator) { ScreenContent(it) } } }
        compose.waitForText("Test Show", stepMs = 50)
        compose.runOnIdle { navigator.push(Screen.SeriesDetails(source, 21)) }
    }

    @Test
    fun `deleting a series' last episode deletes the series, here too, and its page closes`() {
        plex.episodes = plex.episodes.filter { it["ratingKey"]?.jsonPrimitive?.content == "211" }
        runBlocking { app.container.vod.sync(app.container.db.playlists().get(source)!!) }
        seriesPage()
        options("Pilot", "Delete…")
        click("Delete…")
        compose.waitForText("Keep it", stepMs = 50)
        click("Delete")
        // As a real server does, the series went with its last episode: no "server returned 404", but back to the grid.
        compose.waitAdvancingClock(stepMs = 50) { series() == null }
        compose.waitAdvancingClock(stepMs = 50) { compose.onAllNodes(hasText("Pilot")).fetchSemanticsNodes().isEmpty() }
        compose.waitAdvancingClock(stepMs = 50) { compose.onAllNodes(hasText("Test Show")).fetchSemanticsNodes().isEmpty() }
        assertEquals(listOf("211"), plex.deleted)
    }

    @Test
    fun `a series is deleted from the grid with all its episodes, once confirmed`() {
        show(Screen.Series())
        options("Test Show", "Delete the series…")
        click("Delete the series…")
        compose.waitForText("Delete “Test Show” and all 2 of its episodes from the Plex server?", stepMs = 50)
        click("Delete")
        compose.waitAdvancingClock(stepMs = 50) { plex.deleted == listOf("21") && series() == null }
    }

    @Test
    fun `a series with no episodes offers to delete it`() {
        plex.episodes = emptyList()
        seriesPage()
        compose.waitForText("This series has no episodes. Do you want to delete it from the Plex server?", stepMs = 50)
        click("Delete the series")
        compose.waitAdvancingClock(stepMs = 50) { plex.deleted == listOf("21") && series() == null }
        compose.waitAdvancingClock(stepMs = 50) { compose.onAllNodes(hasText("Test Show")).fetchSemanticsNodes().isEmpty() }
    }

    @Test
    fun `a film deleted from its page goes back to the grid, with the remote on a poster`() {
        show(Screen.Movies())
        compose.waitForText("Big Buck Bunny", stepMs = 50)
        click("Big Buck Bunny")
        compose.waitForText("More info", stepMs = 50)
        compose.onAllNodes(hasText("Play") and hasClickAction())[0].performSemanticsAction(SemanticsActions.OnLongClick)
        compose.waitForText("Delete…", stepMs = 50)
        click("Delete…")
        compose.waitForText("Keep it", stepMs = 50)
        click("Delete")
        compose.waitAdvancingClock(stepMs = 50) { "Sintel" in compose.focusedText() }
        assertEquals(listOf("11"), plex.deleted)
    }

    @Test
    fun `a film gone from the grid after the remote went back to it hands the remote to the next`() {
        val navigator = navigators.new(Screen.Movies())
        compose.setContent { MyPlexTheme { NavHost(navigator) { ScreenContent(it) } } }
        compose.waitForText("Big Buck Bunny", stepMs = 50)
        click("Big Buck Bunny")
        compose.waitForText("More info", stepMs = 50)
        compose.runOnIdle { navigator.pop() }
        compose.waitAdvancingClock(stepMs = 50) { "Big Buck Bunny" in compose.focusedText() }
        // Gone once the remote is back on it, as when the grid catches up with a delete made on the film's page.
        runBlocking { app.container.vod.deleteMovie(source, 11) }
        compose.waitAdvancingClock(stepMs = 50) { "Sintel" in compose.focusedText() }
    }

    @Test
    fun `an episode's description is in its options`() {
        show(Screen.SeriesDetails(source, 21))
        options("Second", "Episode description")
        click("Episode description")
        compose.waitForText("Colour bars, one more time.", stepMs = 50)
    }

    @Test
    fun `an episode without one has no entry for it, and the page no hint about the options`() {
        show(Screen.SeriesDetails(source, 21))
        options("Pilot", "Mark as watched")
        assertEquals(0, compose.onAllNodes(hasText("Episode description")).fetchSemanticsNodes().size)
        assertEquals(0, compose.onAllNodes(hasText("hold OK", substring = true)).fetchSemanticsNodes().size)
    }
}
