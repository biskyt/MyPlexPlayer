package com.biskyt.myplexplayer.ui

import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.test.core.app.ApplicationProvider
import com.biskyt.myplexplayer.data.db.CAT_MOVIE
import com.biskyt.myplexplayer.data.plexChannelId
import com.biskyt.myplexplayer.testutil.FakePlex
import com.biskyt.myplexplayer.testutil.Navigators
import com.biskyt.myplexplayer.testutil.TestApp
import com.biskyt.myplexplayer.testutil.addHomePlex
import com.biskyt.myplexplayer.testutil.createUiComposeRule
import com.biskyt.myplexplayer.testutil.focusedText
import com.biskyt.myplexplayer.testutil.waitAdvancingClock
import com.biskyt.myplexplayer.testutil.waitForText
import com.biskyt.myplexplayer.ui.theme.MyPlexTheme
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A Plex library comes first and Live TV last: Home's rows (what's new, then favourites, then channels), the menu
 * and search results. Tall, so all of Home is laid out at once.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestApp::class, qualifiers = "+w960dp-h2400dp-tvdpi")
class LiveTvLastUiTest {
    @get:Rule val compose = createUiComposeRule()

    @get:Rule(order = Navigators.ORDER)
    val navigators = Navigators()

    private val plex = FakePlex()
    private val app = ApplicationProvider.getApplicationContext<TestApp>()
    private var source = 0L

    @Before
    fun setUp() {
        runBlocking {
            source = app.container.addHomePlex(plex)
            app.container.sync.refreshNow(source).getOrThrow()
            app.container.channels.setFavourite(source, plexChannelId("ch-1"), true)
            app.container.vod.setFavourite(source, CAT_MOVIE, 11, true)
        }
    }

    @After
    fun tearDown() = plex.close()

    private fun show(screen: Screen) {
        val navigator = navigators.new(screen)
        compose.setContent { MyPlexTheme { NavHost(navigator) { ScreenContent(it) } } }
    }

    private fun top(text: String) = compose.onAllNodes(hasText(text)).fetchSemanticsNodes().first().boundsInRoot.top

    private fun left(text: String) = compose.onAllNodes(hasText(text) and hasClickAction()).fetchSemanticsNodes().first().boundsInRoot.left

    @Test
    fun `Home has what's new first, then favourites, and Live TV last, with focus on the newest series`() {
        show(Screen.Home)
        compose.waitForText("Your channels, on now", stepMs = 50)
        val rows = listOf("Recently added series", "Recently added movies", "Favourite movies", "Your channels, on now")
        rows.forEach { compose.waitForText(it, stepMs = 50) }
        assertTrue(rows.map(::top).zipWithNext().all { (a, b) -> a < b })
        // The series with the newest episode (from the server's order), and focus on it as Home opens.
        compose.waitAdvancingClock(stepMs = 50) { "Test Show" in compose.focusedText() }
    }

    @Test
    fun `the menu has Live TV last`() {
        show(Screen.Home)
        compose.waitForText("Live TV", stepMs = 50)
        val tabs = listOf("Home", "TV Shows", "Movies", "Live TV").map(::left)
        assertTrue(tabs.toString(), tabs.zipWithNext().all { (a, b) -> a < b })
    }

    @Test
    fun `search results have series before channels`() {
        // A channel found by the same words as the series.
        app.container.db.openHelper.writableDatabase.execSQL("UPDATE channel SET name = 'Test One' WHERE name = 'One'")
        show(Screen.Search("test"))
        compose.waitForText("Test One", stepMs = 50)
        compose.waitForText("Test Show", stepMs = 50)
        assertTrue(top("Test Show") < top("Test One"))
    }

    @Test
    fun `a library that can't say what's new still syncs, without the row`() {
        plex.newestFails = true
        runBlocking { app.container.sync.refreshNow(source).getOrThrow() } // didn't fail
        show(Screen.Home)
        compose.waitForText("Recently added movies", stepMs = 50)
        assertTrue(compose.onAllNodes(hasText("Recently added series")).fetchSemanticsNodes().isEmpty())
    }
}
