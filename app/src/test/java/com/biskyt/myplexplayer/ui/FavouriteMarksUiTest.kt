package com.biskyt.myplexplayer.ui

import androidx.compose.runtime.remember
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import com.biskyt.myplexplayer.BuildConfig
import com.biskyt.myplexplayer.data.ChannelFilter
import com.biskyt.myplexplayer.data.db.CAT_MOVIE
import com.biskyt.myplexplayer.data.db.CAT_SERIES
import com.biskyt.myplexplayer.data.plexChannelId
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Favourites marked with a heart on their logo or poster wherever they're listed; series before movies; the version. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestApp::class, qualifiers = "+w960dp-h540dp-tvdpi")
class FavouriteMarksUiTest {
    @get:Rule val compose = createUiComposeRule()

    @get:Rule(order = Navigators.ORDER)
    val navigators = Navigators()

    private val plex = FakePlex()
    private val app = ApplicationProvider.getApplicationContext<TestApp>()

    @Before
    fun setUp() = runBlocking {
        plex.channels = listOf(FakePlex.Channel("1", "One"), FakePlex.Channel("2", "Two"), FakePlex.Channel("3", "Kite Kids"))
        plex.movies = listOf(FakePlex.film("51", "Dune", 2021), FakePlex.film("52", "Paddington 2", 2017))
        plex.shows = listOf(FakePlex.show("77", "Line of Duty"))
        plex.episodes = listOf(FakePlex.episode("77", "Line of Duty", "771", 1, 1, "Episode 1"))
        val pid = app.container.addSyncedHomePlex(plex)
        // One and Kite Kids, Paddington 2 and Line of Duty.
        listOf("ch-1", "ch-3").forEach { app.container.channels.setFavourite(pid, plexChannelId(it), true) }
        app.container.vod.setFavourite(pid, CAT_MOVIE, 52, true)
        app.container.vod.setFavourite(pid, CAT_SERIES, 77, true)
    }

    @After
    fun tearDown() = plex.close()

    private fun show(start: Screen) {
        val navigator = navigators.new(start)
        compose.setContent { MyPlexTheme { NavHost(remember { navigator }) { ScreenContent(it) } } }
    }

    private fun waitFor(text: String) = compose.waitForText(text, stepMs = 50)

    private fun hearts() = compose.onAllNodes(hasContentDescription("Favourite")).fetchSemanticsNodes().size

    /** Waits for [count] hearts (favourites are read after the list itself). */
    private fun waitForHearts(count: Int) = compose.waitAdvancingClock(stepMs = 50) { hearts() == count }

    private fun left(text: String) = compose.onNode(hasText(text) and hasClickAction()).fetchSemanticsNode().boundsInRoot.left

    private fun top(text: String) = compose.onNode(hasText(text)).fetchSemanticsNode().boundsInRoot.top

    @Test
    fun `the tv guide marks favourite channels`() {
        show(Screen.Live(ChannelFilter.All, guide = true))
        waitFor("Kite Kids")
        waitForHearts(2)
    }

    @Test
    fun `the channel list marks favourite channels`() {
        show(Screen.Live(ChannelFilter.All))
        waitFor("Kite Kids")
        waitForHearts(2)
    }

    @Test
    fun `the movie grid marks favourite movies`() {
        show(Screen.Movies())
        waitFor("Paddington 2")
        waitForHearts(1)
    }

    @Test
    fun `search marks favourite series and movies, and lists series before movies`() {
        // "du": Dune (a movie) and Line of Duty (a favourite series).
        show(Screen.Search("du"))
        waitFor("Dune")
        waitFor("Line of Duty")
        waitForHearts(1)
        assertTrue(top("Line of Duty") < top("Dune"))
    }

    @Test
    fun `search marks favourite channels`() {
        show(Screen.Search("kite"))
        waitFor("Kite Kids")
        waitForHearts(1)
    }

    @Test
    fun `the top bar lists series before movies`() {
        show(Screen.Home)
        waitFor("TV Shows")
        assertTrue(left("TV Shows") < left("Movies"))
    }

    @Test
    fun `settings shows the app's version on the About row`() {
        show(Screen.Settings)
        waitFor("Playlists")
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("About"))
        waitFor("Version ${BuildConfig.VERSION_NAME},")
    }
}
