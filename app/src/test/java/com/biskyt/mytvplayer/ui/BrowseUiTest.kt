package com.biskyt.mytvplayer.ui

import androidx.compose.runtime.remember
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.printToString
import androidx.test.core.app.ApplicationProvider
import com.biskyt.mytvplayer.data.ChannelFilter
import com.biskyt.mytvplayer.testutil.FakeProvider
import com.biskyt.mytvplayer.testutil.Navigators
import com.biskyt.mytvplayer.testutil.TestApp
import com.biskyt.mytvplayer.testutil.createUiComposeRule
import com.biskyt.mytvplayer.testutil.focusedText
import com.biskyt.mytvplayer.testutil.waitAdvancingClock
import com.biskyt.mytvplayer.ui.live.LiveScreen
import com.biskyt.mytvplayer.ui.search.SearchScreen
import com.biskyt.mytvplayer.ui.theme.MyTvTheme
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

    private val provider = FakeProvider()
    private val app = ApplicationProvider.getApplicationContext<TestApp>()

    @Before
    fun setUp() = runBlocking {
        val c = app.container
        provider.nowProgramme = mapOf("one.uk" to "<title>Paddington Bear Live</title>")
        val id = c.playlists.add("Home TV", provider.url, "me", "secret")
        c.playlists.syncChannels(id)
        c.vod.sync(c.playlists.get(id)!!)
        c.playlists.get(id)?.let { c.epg.ensureProviderSource(it, c.playlists.client(it).xmltvUrl()) }
        c.epg.importAll(listOf(id))
        c.settings.update { it.copy(lastLiveFilter = "all") }
        c.settings.current()
        Unit
    }

    @After
    fun tearDown() = provider.close()

    private fun show(start: Screen) {
        val navigator = navigators.new(start)
        compose.setContent {
            MyTvTheme {
                NavHost(remember { navigator }) {
                    when (it) {
                        is Screen.Live -> LiveScreen(it.filter, it.guide)
                        is Screen.Search -> SearchScreen(it.query)
                        else -> Unit
                    }
                }
            }
        }
    }

    private fun shown(text: String) = compose.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty()

    private fun waitFor(text: String) = try {
        compose.waitAdvancingClock(50) { shown(text) }
    } catch (e: androidx.compose.ui.test.ComposeTimeoutException) {
        throw AssertionError("'$text' not shown. Screen:\n" + compose.onRoot(useUnmergedTree = true).printToString(), e)
    }

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
        show(Screen.Live(ChannelFilter.All))
        waitFor("UK | ENTERTAINMENT")
        waitFor("UK | SPORT")
        // Once the channel list has taken focus (as it does on opening), the filter box is picked.
        compose.waitAdvancingClock { compose.focusedText().contains("CHANNEL ONE") }
        filterCategories("sport")
        compose.waitAdvancingClock { !shown("UK | ENTERTAINMENT") }
        assertTrue(shown("UK | SPORT"))
        // The lists that aren't categories stay, so there's always a way back to them.
        assertTrue(shown("All channels") && shown("Favourites"))
    }

    @Test
    fun `the category being watched stays in the column whatever the filter says`() {
        show(Screen.Live(ChannelFilter.Category("1"))) // UK | ENTERTAINMENT
        waitFor("UK | SPORT")
        compose.waitAdvancingClock { compose.focusedText().contains("CHANNEL") }
        filterCategories("sport")
        compose.waitAdvancingClock { inColumn("UK | SPORT") }
        // Focus coming back into the column has the chosen one to go to, also once another is chosen.
        assertTrue(inColumn("UK | ENTERTAINMENT"))
        compose.onNode(hasText("UK | SPORT") and hasClickAction()).performSemanticsAction(SemanticsActions.OnClick)
        compose.waitAdvancingClock(50) { shown("ARENA SPRTS") && !shown("KITE KIDZ") } // the list is SPORT's
        assertTrue(inColumn("UK | ENTERTAINMENT"))
        // Typing on: the one chosen while filtering stays too.
        compose.onNode(hasSetTextAction()).performTextReplacement("nothing like it")
        compose.waitAdvancingClock { shown("nothing like it") }
        assertTrue(inColumn("UK | SPORT") && inColumn("UK | ENTERTAINMENT"))
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
