package com.biskyt.myplexplayer.ui

import androidx.compose.runtime.remember
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ApplicationProvider
import com.biskyt.myplexplayer.data.VodFilter
import com.biskyt.myplexplayer.data.db.CAT_MOVIE
import com.biskyt.myplexplayer.testutil.FakePlex
import com.biskyt.myplexplayer.testutil.Navigators
import com.biskyt.myplexplayer.testutil.TestApp
import com.biskyt.myplexplayer.testutil.addSyncedHomePlex
import com.biskyt.myplexplayer.testutil.createUiComposeRule
import com.biskyt.myplexplayer.testutil.waitAdvancingClock
import com.biskyt.myplexplayer.testutil.waitForText
import com.biskyt.myplexplayer.ui.search.SearchScreen
import com.biskyt.myplexplayer.ui.theme.MyPlexTheme
import com.biskyt.myplexplayer.ui.vod.VodBrowseScreen
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Finding things in long lists: the category filter, and the order of search results. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestApp::class, qualifiers = "+w960dp-h540dp-tvdpi")
class BrowseUiTest {
    @get:Rule val compose = createUiComposeRule()

    @get:Rule(order = Navigators.ORDER)
    val navigators = Navigators()

    private val plex = FakePlex()
    private val app = ApplicationProvider.getApplicationContext<TestApp>()

    @Before
    fun setUp() = runBlocking {
        // Several film libraries (a Plex server's Live TV has one category), a film named like a programme.
        plex.libraries = listOf(
            FakePlex.Library("1", "movie", "Family Films"),
            FakePlex.Library("3", "movie", "Sport Films"),
            FakePlex.Library("2", "show", "TV Programmes"),
        )
        plex.movies = listOf(
            FakePlex.film("11", "Big Buck Bunny", library = "1"),
            FakePlex.film("12", "Paddington 2", library = "1"),
            FakePlex.film("13", "Kick Off", library = "3"),
        )
        val programme = plex.programme
        plex.programme = { i, n -> if (i == 0 && n == 0) FakePlex.Programme("Paddington Bear Live") else programme(i, n) }
        app.container.addSyncedHomePlex(plex)
        Unit
    }

    @After
    fun tearDown() = plex.close()

    private fun show(start: Screen) {
        val navigator = navigators.new(start)
        compose.setContent {
            MyPlexTheme {
                NavHost(remember { navigator }) {
                    when (it) {
                        is Screen.Movies -> VodBrowseScreen(CAT_MOVIE, it.filter)
                        is Screen.Search -> SearchScreen(it.query)
                        else -> Unit
                    }
                }
            }
        }
    }

    private fun shown(text: String) = compose.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty()

    private fun waitFor(text: String) = compose.waitForText(text, stepMs = 50)

    // The list's heading names its category too: only the column's entries can be clicked.
    private fun inColumn(text: String) = compose.onAllNodes(hasText(text) and hasClickAction()).fetchSemanticsNodes().isNotEmpty()

    /** Types [text] in the box above the categories. */
    private fun filterCategories(text: String) {
        compose.onNode(hasText("Filter categories") and hasClickAction()).performSemanticsAction(SemanticsActions.OnClick)
        compose.waitAdvancingClock { compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(hasSetTextAction()).performTextInput(text)
    }

    @Test
    fun `the category filter narrows the categories to those named like it, keeping All and Favourites`() {
        show(Screen.Movies(VodFilter.All))
        waitFor("Family Films")
        waitFor("Sport Films")
        // Once the list has loaded, the filter box is picked.
        waitFor("Big Buck Bunny")
        filterCategories("sport")
        compose.waitAdvancingClock { !shown("Family Films") }
        assertTrue(shown("Sport Films"))
        // The lists that aren't categories stay, so there's always a way back to them.
        assertTrue(shown("All movies") && shown("Favourites"))
    }

    @Test
    fun `the category being watched stays in the column whatever the filter says`() {
        show(Screen.Movies(VodFilter.Category("1"))) // Family Films
        waitFor("Sport Films")
        waitFor("Big Buck Bunny")
        filterCategories("sport")
        compose.waitAdvancingClock { inColumn("Sport Films") }
        // Focus coming back into the column has the chosen one to go to, also once another is chosen.
        assertTrue(inColumn("Family Films"))
        compose.onNode(hasText("Sport Films") and hasClickAction()).performSemanticsAction(SemanticsActions.OnClick)
        compose.waitAdvancingClock(50) { shown("Kick Off") && !shown("Big Buck Bunny") } // the list is Sport Films'
        assertTrue(inColumn("Family Films"))
        // Typing on: the one chosen while filtering stays too.
        compose.onNode(hasSetTextAction()).performTextReplacement("nothing like it")
        compose.waitAdvancingClock { shown("nothing like it") }
        assertTrue(inColumn("Sport Films") && inColumn("Family Films"))
    }

    @Test
    fun `search lists movies and series before TV programmes`() {
        show(Screen.Search("padd"))
        waitFor("Paddington 2") // a movie
        waitFor("Paddington Bear Live") // a programme
        fun top(text: String) = compose.onNode(hasText(text)).fetchSemanticsNode().boundsInRoot.top
        assertTrue(top("Paddington 2") < top("Paddington Bear Live"))
    }
}
