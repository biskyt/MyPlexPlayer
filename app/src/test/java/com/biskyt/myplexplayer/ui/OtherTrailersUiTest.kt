package com.biskyt.myplexplayer.ui

import androidx.compose.runtime.remember
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.core.app.ApplicationProvider
import androidx.tv.material3.Text
import com.biskyt.myplexplayer.testutil.BoardTitles
import com.biskyt.myplexplayer.testutil.FakePlex
import com.biskyt.myplexplayer.testutil.FakeTmdb
import com.biskyt.myplexplayer.testutil.Navigators
import com.biskyt.myplexplayer.testutil.OnAppBackground
import com.biskyt.myplexplayer.testutil.TestApp
import com.biskyt.myplexplayer.testutil.addSyncedHomePlex
import com.biskyt.myplexplayer.testutil.click
import com.biskyt.myplexplayer.testutil.createUiComposeRule
import com.biskyt.myplexplayer.testutil.settle
import com.biskyt.myplexplayer.testutil.waitAdvancingClock
import com.biskyt.myplexplayer.testutil.waitForFilmPage
import com.biskyt.myplexplayer.testutil.waitForText
import com.biskyt.myplexplayer.ui.theme.MyPlexTheme
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * "Other trailers" (MyTVPlayer 1.27.0, from Plex first): offered when a title has more than one trailer in all. It lists
 * the server's own trailers and teasers first (its extras, sent with the title's details: docs/plex-api.md row 30),
 * which play in the app, then TMDB's, with a TMDB key.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestApp::class, qualifiers = "+w960dp-h540dp-tvdpi")
class OtherTrailersUiTest {
    @get:Rule val compose = createUiComposeRule()

    @get:Rule(order = Navigators.ORDER)
    val navigators = Navigators()

    private val plex = FakePlex()
    private val tmdb = FakeTmdb()
    private val app = ApplicationProvider.getApplicationContext<TestApp>()
    private var source = 0L
    private lateinit var navigator: Navigator

    @After
    fun tearDown() {
        plex.close()
        tmdb.close()
    }

    /**
     * The board's film and show, each with the server's trailer, and the extras [extras] (keys of [EXTRAS]) for the film;
     * with a TMDB key when [tmdbKey]. TMDB knows the film's id.
     */
    private fun setUp(extras: List<String>, tmdbKey: Boolean) = runBlocking {
        plex.movies = listOf(FakePlex.withTrailer(BoardTitles.film(), MAIN))
        plex.shows = listOf(FakePlex.withTrailer(BoardTitles.show(), SHOW_MAIN))
        plex.episodes = BoardTitles.episodes()
        plex.extras = EXTRAS
        plex.extrasOf = mapOf(BoardTitles.FILM to extras, BoardTitles.SHOW to listOf(SHOW_MAIN, TEASER))
        plex.tmdbIds = mapOf(BoardTitles.FILM to FakeTmdb.FILM)
        BoardTitles.watch(plex)
        source = app.container.addSyncedHomePlex(plex)
        tmdb.use(app.container, key = if (tmdbKey) FakeTmdb.TEST_KEY else null)
    }

    /** [screen], with a marker in place of the player (it needs video hardware), to see what it was opened with. */
    private fun show(screen: Screen) {
        navigator = navigators.new(screen)
        compose.setContent {
            MyPlexTheme {
                OnAppBackground {
                    NavHost(remember { navigator }) { if (it is Screen.VodPlayer) Text("PLAYER $it") else ScreenContent(it) }
                }
            }
        }
    }

    /** The film's page, with its More… open, once every trailer is read. */
    private fun filmOptions(tmdbKey: Boolean) {
        show(Screen.MovieDetails(source, BoardTitles.FILM.toLong()))
        compose.waitForFilmPage()
        compose.waitAdvancingClock(stepMs = 50) {
            BoardTitles.FILM in plex.extrasAsked && (!tmdbKey || "movie/${FakeTmdb.FILM}" in tmdb.requests)
        }
        compose.settle()
        // The extras come with the film's details: no request of their own.
        assertTrue(plex.paths.none { it.endsWith("/extras") })
        compose.click("More…")
        compose.waitForText("About this film", stepMs = 50)
    }

    private fun shown(text: String) = compose.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty()

    /** How far down [text] is (its panel's own window). */
    private fun top(text: String) = compose.onAllNodes(hasText(text)).fetchSemanticsNodes().single().boundsInRoot.top

    private fun otherTrailers() {
        compose.waitForText("Other trailers", stepMs = 50)
        compose.click("Other trailers")
        compose.waitForText("Spot · Teaser", stepMs = 50)
    }

    @Test
    fun `Other trailers lists the server's trailers first, then TMDB's`() {
        setUp(extras = listOf(MAKING_OF, TEASER, MAIN), tmdbKey = true)
        filmOptions(tmdbKey = true)
        otherTrailers()
        compose.waitForText("Teaser · GB", stepMs = 50)
        // The server's main trailer, its teaser (named with its kind), then TMDB's trailer and teaser; no other extras.
        val order = listOf("Official Trailer", "Spot · Teaser", "Trailer", "Teaser · GB").map(::top)
        assertEquals(order.sorted(), order)
        assertFalse(shown("Making of"))
    }

    @Test
    fun `a server's trailer in Other trailers plays in the app`() {
        setUp(extras = listOf(MAIN, TEASER), tmdbKey = true)
        filmOptions(tmdbKey = true)
        otherTrailers()
        compose.click("Spot · Teaser")
        compose.waitAdvancingClock(stepMs = 50) { navigator.current.screen is Screen.VodPlayer }
        assertEquals(Screen.VodPlayer(source, BoardTitles.FILM.toLong(), trailer = TEASER.toLong()), navigator.current.screen)
    }

    @Test
    fun `without a TMDB key, Other trailers lists only the server's`() {
        setUp(extras = listOf(MAIN, TEASER), tmdbKey = false)
        filmOptions(tmdbKey = false)
        otherTrailers()
        assertTrue(shown("Official Trailer"))
        assertFalse(shown("Teaser · GB"))
        assertEquals(emptyList<String>(), tmdb.requests)
    }

    @Test
    fun `a title with a single trailer in all offers no Other trailers`() {
        setUp(extras = listOf(MAIN, MAKING_OF), tmdbKey = false)
        filmOptions(tmdbKey = false)
        assertTrue(shown("Play trailer"))
        assertFalse(shown("Other trailers"))
    }

    @Test
    fun `a show's options offer Other trailers under The show`() {
        setUp(extras = emptyList(), tmdbKey = false)
        show(Screen.SeriesDetails(source, BoardTitles.SHOW.toLong()))
        compose.waitForText("Continue S2 E4 · 21 min left", stepMs = 50)
        compose.waitAdvancingClock(stepMs = 50) { BoardTitles.SHOW in plex.extrasAsked }
        compose.onNode(hasText("The Lighthouse Keeper") and hasClickAction()).performSemanticsAction(SemanticsActions.RequestFocus)
        compose.waitForText("More…", stepMs = 50)
        compose.click("More…")
        compose.waitForText("THE SHOW", stepMs = 50)
        otherTrailers()
        compose.click("Spot · Teaser")
        compose.waitAdvancingClock(stepMs = 50) { navigator.current.screen is Screen.VodPlayer }
        val show = BoardTitles.SHOW.toLong()
        assertEquals(Screen.VodPlayer(source, show, show, trailer = TEASER.toLong()), navigator.current.screen)
    }

    private companion object {
        /** The film's own trailer ("Play trailer" plays it), and the show's. */
        const val MAIN = "900"
        const val SHOW_MAIN = "901"

        /** A teaser, whose title does not say it is one. */
        const val TEASER = "902"

        /** An extra that is not a trailer. */
        const val MAKING_OF = "903"

        val EXTRAS = listOf(
            FakePlex.trailer(MAIN, "Official Trailer"),
            FakePlex.trailer(SHOW_MAIN),
            FakePlex.trailer(TEASER, "Spot", subtype = "teaser"),
            FakePlex.trailer(MAKING_OF, "Making of", subtype = "behindTheScenes"),
        )
    }
}
