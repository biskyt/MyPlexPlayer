package com.biskyt.myplexplayer.ui.settings

import androidx.compose.runtime.remember
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import com.biskyt.myplexplayer.data.db.CAT_MOVIE
import com.biskyt.myplexplayer.testutil.FakePlex
import com.biskyt.myplexplayer.testutil.Navigators
import com.biskyt.myplexplayer.testutil.TestApp
import com.biskyt.myplexplayer.testutil.addSyncedHomePlex
import com.biskyt.myplexplayer.testutil.createUiComposeRule
import com.biskyt.myplexplayer.testutil.waitAdvancingClock
import com.biskyt.myplexplayer.testutil.waitForText
import com.biskyt.myplexplayer.ui.NavHost
import com.biskyt.myplexplayer.ui.Screen
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

/** Settings › Languages & categories: a server's film libraries, grouped by the language or country their names say. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestApp::class, qualifiers = "+w960dp-h540dp-tvdpi")
class CategoriesUiTest {
    @get:Rule val compose = createUiComposeRule()

    @get:Rule(order = Navigators.ORDER)
    val navigators = Navigators()

    private val plex = FakePlex()
    private val app = ApplicationProvider.getApplicationContext<TestApp>()
    private var source = 0L

    @Before
    fun setUp() {
        plex.libraries = listOf(
            FakePlex.Library("1", "movie", "UK | FILMS"),
            FakePlex.Library("2", "show", "TV Programmes"),
            FakePlex.Library("3", "movie", "UK | DOCUMENTARIES"),
            FakePlex.Library("4", "movie", "FR: CINEMA"),
            FakePlex.Library("5", "movie", "|AR| NEWS"),
        )
        source = runBlocking { app.container.addSyncedHomePlex(plex) }
        val navigator = navigators.new(Screen.Categories(source))
        compose.setContent { MyPlexTheme { NavHost(remember { navigator }) { CategoriesScreen(source, firstRun = false) } } }
        waitFor("No category") // the Live TV list (one, of no language), held just after switching
        click("Movies")
        // The languages are in alphabetical order: the first one's categories show, not those of the Live TV list's
        // language, which the movies don't have (MyTVPlayer 1.3.4).
        waitFor("Arabic")
        waitFor("|AR| NEWS")
    }

    @After
    fun tearDown() = plex.close()

    private fun waitFor(text: String) = compose.waitForText(text, stepMs = 50)

    private fun click(text: String) = compose.onNode(hasText(text) and hasClickAction()).performSemanticsAction(SemanticsActions.OnClick)

    private fun included() = runBlocking { app.container.db.categories().included(source, CAT_MOVIE) }

    /** The filter box opens for typing when pressed (OK on the remote), then takes [text]. */
    private fun filter(text: String) {
        click("Filter countries and categories")
        compose.waitAdvancingClock { compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(hasSetTextAction()).performTextInput(text)
    }

    @Test
    fun `languages and categories can be filtered`() {
        filter("cine")
        waitFor("FR: CINEMA")
        compose.waitAdvancingClock { compose.onAllNodes(hasText("|AR| NEWS")).fetchSemanticsNodes().isEmpty() }
    }

    @Test
    fun `each list has its own select all and none, the languages' while filtering only the matching ones`() {
        // The languages' None (the first): nothing at all.
        compose.onAllNodes(hasText("None") and hasClickAction())[0].performSemanticsAction(SemanticsActions.OnClick)
        compose.waitAdvancingClock { included().isEmpty() }

        // The categories' All: every category in the language shown, and no other language.
        waitFor("All in ")
        compose.onNode(hasText("All in ", substring = true) and hasClickAction()).performSemanticsAction(SemanticsActions.OnClick)
        compose.waitAdvancingClock { included().isNotEmpty() }
        val langs = included().map { it.lang }.toSet()
        assertEquals(1, langs.size)
        assertEquals(runBlocking { app.container.db.categories().all(source, CAT_MOVIE) }.count { it.lang in langs }, included().size)

        compose.onAllNodes(hasText("None") and hasClickAction())[0].performSemanticsAction(SemanticsActions.OnClick)
        compose.waitAdvancingClock { included().isEmpty() }
        filter("UK")
        waitFor("All matching")
        click("All matching")
        compose.waitAdvancingClock { included().map { it.name }.toSet() == setOf("UK | FILMS", "UK | DOCUMENTARIES") }

        compose.onNode(hasSetTextAction()).performTextClearance()
        waitFor("All")
        click("All")
        compose.waitAdvancingClock { included().size == 4 }
    }
}
