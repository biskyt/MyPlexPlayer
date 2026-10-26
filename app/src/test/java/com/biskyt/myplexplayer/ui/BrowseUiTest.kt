package com.biskyt.myplexplayer.ui

import androidx.compose.runtime.remember
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
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

/** Finding things in long lists: the grid's pills, and the order of search results. */
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
                        is Screen.Movies -> VodBrowseScreen(CAT_MOVIE, it.view)
                        is Screen.Search -> SearchScreen(it.query)
                        else -> Unit
                    }
                }
            }
        }
    }

    private fun shown(text: String) = compose.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty()

    private fun waitFor(text: String) = compose.waitForText(text, stepMs = 50)

    private fun click(text: String) =
        compose.onNode(hasText(text, substring = true) and hasClickAction()).performSemanticsAction(SemanticsActions.OnClick)

    @Test
    fun `the category pill shows one library's films, the filter pill those unwatched, and all again`() {
        plex.watched += "12"
        runBlocking { app.container.vod.sync(app.container.db.playlists().all().single()) }
        show(Screen.Movies())
        waitFor("Kick Off")
        click("Category: All")
        waitFor("Sport Films")
        click("Family Films")
        compose.waitAdvancingClock(50) { shown("Category: Family Films") && !shown("Kick Off") && shown("Paddington 2") }

        click("Filter: All")
        waitFor("Unwatched")
        click("Unwatched")
        compose.waitAdvancingClock(50) { !shown("Paddington 2") && shown("Big Buck Bunny") }
        // The view is the one asked for: both choices stay.
        assertTrue(shown("Category: Family Films") && shown("Filter: Unwatched"))

        click("Category: Family Films")
        waitFor("✓ Family Films")
        click("All")
        compose.waitAdvancingClock(50) { shown("Kick Off") && shown("Big Buck Bunny") && !shown("Paddington 2") }
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
