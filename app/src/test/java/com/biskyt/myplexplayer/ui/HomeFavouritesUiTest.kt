package com.biskyt.myplexplayer.ui

import androidx.compose.runtime.remember
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.core.app.ApplicationProvider
import com.biskyt.myplexplayer.R
import com.biskyt.myplexplayer.data.db.CAT_MOVIE
import com.biskyt.myplexplayer.data.db.CAT_SERIES
import com.biskyt.myplexplayer.testutil.FakePlex
import com.biskyt.myplexplayer.testutil.Navigators
import com.biskyt.myplexplayer.testutil.TestApp
import com.biskyt.myplexplayer.testutil.addSyncedHomePlex
import com.biskyt.myplexplayer.testutil.createUiComposeRule
import com.biskyt.myplexplayer.testutil.focusedText
import com.biskyt.myplexplayer.testutil.scrollToHomeRow
import com.biskyt.myplexplayer.testutil.waitAdvancingClock
import com.biskyt.myplexplayer.testutil.waitForText
import com.biskyt.myplexplayer.ui.theme.MyPlexTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Home's rows of favourite series and movies, below the recently added ones and above Live TV's. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestApp::class, qualifiers = "+w960dp-h540dp-tvdpi")
class HomeFavouritesUiTest {
    @get:Rule val compose = createUiComposeRule()

    @get:Rule(order = Navigators.ORDER)
    val navigators = Navigators()

    private val plex = FakePlex()
    private val app = ApplicationProvider.getApplicationContext<TestApp>()
    private var playlistId = 0L
    private lateinit var navigator: Navigator

    @Before
    fun setUp() = runBlocking {
        plex.movies = listOf(
            FakePlex.film("51", "Dune", 2021, addedAt = 1_700_000_051),
            FakePlex.film("52", "Paddington 2", 2017, addedAt = 1_700_000_052),
            FakePlex.film("53", "Heat", 1995, addedAt = 1_700_000_053),
        )
        // Lupin has the newest episode: first on Home's recently added series.
        plex.shows = listOf(FakePlex.show("78", "Lupin"), FakePlex.show("77", "Line of Duty"))
        plex.episodes = listOf(
            FakePlex.episode("78", "Lupin", "781", 1, 1, "Episode 1"),
            FakePlex.episode("77", "Line of Duty", "771", 1, 1, "Episode 1"),
        )
        playlistId = app.container.addSyncedHomePlex(plex)
        // Line of Duty; Paddington 2 and Heat.
        app.container.vod.setFavourite(playlistId, CAT_SERIES, 77, true)
        listOf(52L, 53L).forEach { app.container.vod.setFavourite(playlistId, CAT_MOVIE, it, true) }
    }

    @After
    fun tearDown() = plex.close()

    private fun show() {
        navigator = navigators.new(Screen.Home)
        compose.setContent { MyPlexTheme { NavHost(remember { navigator }) { ScreenContent(it) } } }
    }

    private fun waitFor(text: String) = compose.waitForText(text, stepMs = 50)

    /** Where the last [text] is (a favourite is also among the recently added, above). */
    private fun top(text: String) = compose.onAllNodes(hasText(text)).onLast().fetchSemanticsNode().boundsInRoot.top

    private fun shown(text: String) = compose.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun `favourite series and movies have rows of their own, series first, opening their page`() {
        show()
        compose.scrollToHomeRow(R.string.home_favourite_movies)
        waitFor("Paddington 2")
        waitFor("Heat")
        assertTrue(top("Favourite series") < top("Favourite movies"))
        assertTrue(top("Line of Duty") < top("Favourite movies"))

        // The favourites' card (it's also among the recently added, above).
        compose.onAllNodes(hasText("Paddington 2") and hasClickAction()).onLast().performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
        assertEquals(Screen.MovieDetails(playlistId, 52), navigator.current.screen)
    }

    @Test
    fun `a favourite's options take it off favourites, and its row with it when it was the last`() {
        show()
        compose.scrollToHomeRow(R.string.home_favourite_series)
        waitFor("Line of Duty")
        // The favourites' card (it's also among the recently added, above).
        val card = compose.onAllNodes(hasText("Line of Duty") and hasClickAction()).onLast()
        card.performSemanticsAction(SemanticsActions.RequestFocus)
        card.performSemanticsAction(SemanticsActions.OnLongClick)
        waitFor("Open series")
        waitFor("Remove from favourites")
        compose.onNode(hasText("Remove from favourites")).performSemanticsAction(SemanticsActions.OnClick)
        compose.waitAdvancingClock(stepMs = 50) { !shown("Favourite series") }
        // Focus doesn't go with the card: it goes back to the first one on Home (the newest series').
        compose.waitAdvancingClock(stepMs = 50) { "Lupin" in compose.focusedText() }
        runBlocking { assertFalse(77L in app.container.vod.observeFavouriteIds(playlistId, CAT_SERIES).first()) }
        assertTrue(shown("Favourite movies"))
    }
}
