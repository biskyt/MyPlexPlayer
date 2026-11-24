package com.biskyt.myplexplayer.ui

import androidx.compose.runtime.remember
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.core.app.ApplicationProvider
import com.biskyt.myplexplayer.testutil.BoardTitles
import com.biskyt.myplexplayer.testutil.FakeCredit
import com.biskyt.myplexplayer.testutil.FakePerson
import com.biskyt.myplexplayer.testutil.FakePlex
import com.biskyt.myplexplayer.testutil.FakeTmdb
import com.biskyt.myplexplayer.testutil.Navigators
import com.biskyt.myplexplayer.testutil.OnAppBackground
import com.biskyt.myplexplayer.testutil.TestApp
import com.biskyt.myplexplayer.testutil.addSyncedHomePlex
import com.biskyt.myplexplayer.testutil.click
import com.biskyt.myplexplayer.testutil.createUiComposeRule
import com.biskyt.myplexplayer.testutil.focusedText
import com.biskyt.myplexplayer.testutil.settle
import com.biskyt.myplexplayer.testutil.waitAdvancingClock
import com.biskyt.myplexplayer.testutil.waitForFilmPage
import com.biskyt.myplexplayer.testutil.waitForText
import com.biskyt.myplexplayer.ui.common.Tab
import com.biskyt.myplexplayer.ui.theme.MyPlexTheme
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
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * "More like this" (the owner, 2026-11-17): a film's More… and a show's options open the titles TMDB suggests, those
 * on the server (which open) and the others. The TMDB id is the server's `tmdb://` guid. Without a TMDB key, or
 * without that guid, the option is not there. With a key, "Also appears in" on a person's page opens TMDB's page.
 * MyTVPlayer's MoreLikeUiTest, on FakePlex.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestApp::class, qualifiers = "+w960dp-h540dp-tvdpi")
class MoreLikeUiTest {
    @get:Rule val compose = createUiComposeRule()

    @get:Rule(order = Navigators.ORDER)
    val navigators = Navigators()

    private val plex = FakePlex()
    private val tmdb = FakeTmdb()
    private val app = ApplicationProvider.getApplicationContext<TestApp>()
    private var source = 0L
    private lateinit var navigator: Navigator

    @Before
    fun setUp() = runBlocking {
        plex.movies = listOf(BoardTitles.film(), FakePlex.film("12", "Sintel", 2010))
        plex.shows = listOf(BoardTitles.show())
        plex.episodes = BoardTitles.episodes()
        // Sintel has no TMDB id on the server.
        plex.tmdbIds = mapOf(BoardTitles.FILM to FakeTmdb.FILM, BoardTitles.SHOW to FakeTmdb.SHOW)
        plex.people = listOf(MIRA)
        plex.discoverTmdbIds = mapOf("d-4" to FakeTmdb.PAPER_BOATS)
        BoardTitles.watch(plex)
        source = app.container.addSyncedHomePlex(plex)
    }

    @After
    fun tearDown() {
        plex.close()
        tmdb.close()
    }

    private fun show(screen: Screen) {
        navigator = navigators.new(screen)
        compose.setContent { MyPlexTheme { OnAppBackground { NavHost(remember { navigator }) { ScreenContent(it) } } } }
    }

    private fun shown(text: String) = compose.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty()

    private val screen get() = compose.runOnIdle { navigator.current.screen }

    /** The film page, with its More… open. */
    private fun filmOptions(id: String = BoardTitles.FILM) {
        show(Screen.MovieDetails(source, id.toLong()))
        if (id == BoardTitles.FILM) compose.waitForFilmPage() else compose.waitForText("Sintel", stepMs = 50)
        compose.click("More…")
        compose.waitForText("Technical info", stepMs = 50)
    }

    /** Coastline's page, with an episode's options open. */
    private fun showOptions() {
        show(Screen.SeriesDetails(source, BoardTitles.SHOW.toLong()))
        compose.waitForText("Continue S2 E4 · 21 min left", stepMs = 50)
        compose.onNode(hasText("The Lighthouse Keeper") and hasClickAction()).performSemanticsAction(SemanticsActions.RequestFocus)
        compose.waitForText("More…", stepMs = 50)
        compose.click("More…")
        compose.waitForText("THE SHOW", stepMs = 50)
    }

    private val longHarbour get() = Screen.MoreLike(source, FakeTmdb.FILM, series = false, "The Long Harbour", Tab.MOVIES)

    @Test
    fun `a film's More… opens More like this, by the TMDB id from the server's guid`() {
        tmdb.use(app.container)
        filmOptions()
        compose.waitForText("More like this", stepMs = 50)
        compose.click("More like this")
        compose.waitAdvancingClock(stepMs = 50) { screen is Screen.MoreLike }
        assertEquals(longHarbour, screen)
    }

    @Test
    fun `a show's options offer More like this under The show`() {
        tmdb.use(app.container)
        showOptions()
        compose.waitForText("More like this", stepMs = 50)
        compose.click("More like this")
        compose.waitAdvancingClock(stepMs = 50) { screen is Screen.MoreLike }
        assertEquals(Screen.MoreLike(source, FakeTmdb.SHOW, series = true, "Coastline", Tab.SERIES), screen)
    }

    @Test
    fun `the page shows the suggestions in TMDB's order, and OK opens one on the server on its page there`() {
        tmdb.use(app.container)
        show(longHarbour)
        compose.waitForText("More like The Long Harbour", stepMs = 50)
        compose.waitForText("Suggested by TMDB", stepMs = 50)
        compose.waitForText("Paper Boats", stepMs = 50)
        assertTrue(shown("2018 · TMDB 76%"))
        assertTrue("the server has it", shown("2010 · In your library"))
        assertTrue("movie/${FakeTmdb.FILM}/recommendations" in tmdb.requests)
        compose.click("Sintel")
        compose.waitAdvancingClock(stepMs = 50) { screen is Screen.MovieDetails }
        assertEquals(Screen.MovieDetails(source, 12), screen)
    }

    @Test
    fun `a suggestion not on the server opens its TMDB page, where Find in your library searches for it`() {
        tmdb.use(app.container)
        show(longHarbour)
        compose.waitForText("Paper Boats", stepMs = 50)
        compose.click("Paper Boats")
        compose.waitAdvancingClock(stepMs = 50) { screen is Screen.TmdbTitle }
        assertEquals(Screen.TmdbTitle(source, FakeTmdb.PAPER_BOATS, series = false, "Paper Boats", null, Tab.MOVIES), screen)
        compose.waitForText("Two sisters sail home.", stepMs = 50)
        compose.waitForText("2018 · Drama · TMDB 76%", stepMs = 50)
        compose.waitForText("Directed by Ana Ruiz", stepMs = 50)
        compose.waitForText("Play trailer", stepMs = 50)
        // The original title, which the server may use: searched for with its own button.
        compose.click("Find “Bateaux de Papier”")
        compose.waitAdvancingClock(stepMs = 50) { screen is Screen.Search }
        assertEquals(Screen.Search("Bateaux de Papier"), screen)
        compose.runOnUiThread { navigator.pop() }
        compose.waitForText("Find in your library", stepMs = 50)
        compose.click("Find in your library")
        compose.waitAdvancingClock(stepMs = 50) { screen is Screen.Search }
        assertEquals(Screen.Search("Paper Boats"), screen)
    }

    @Test
    fun `a TMDB page's Other trailers lists TMDB's trailers, with their country, and opens the one chosen`() {
        tmdb.use(app.container)
        show(Screen.TmdbTitle(source, FakeTmdb.PAPER_BOATS, series = false, "Paper Boats", null, Tab.MOVIES))
        compose.waitForText("Other trailers", stepMs = 50)
        compose.click("Other trailers")
        compose.waitForText("Teaser · GB", stepMs = 50)
        compose.click("Teaser · GB")
        compose.waitAdvancingClock { shadowOf(app).peekNextStartedActivity() != null }
        assertEquals("https://www.youtube.com/watch?v=${FakeTmdb.PAPER_BOATS_TEASER}", shadowOf(app).nextStartedActivity.dataString)
    }

    @Test
    fun `a season that TMDB could not give offers Try again, which shows its episodes`() {
        tmdb.use(app.container)
        tmdb.seasonsFail = true
        show(Screen.TmdbTitle(source, FakeTmdb.SIGNAL_HILL, series = true, "Signal Hill", null, Tab.SERIES))
        compose.waitForText("Couldn't load these episodes from TMDB", stepMs = 50)
        tmdb.seasonsFail = false
        compose.waitAdvancingClock { compose.focusedText() == "Try again" }
        compose.click("Try again")
        compose.waitForText("Static", stepMs = 50)
        assertFalse(shown("Couldn't load these episodes from TMDB"))
        // The button went: the remote moved up to its season, not back to the top of the page.
        compose.settle()
        assertEquals("The Second Run", compose.focusedText())
    }

    @Test
    fun `a title with no suggestions says so`() {
        tmdb.use(app.container)
        show(Screen.MoreLike(source, 1, series = true, "Sintel", Tab.SERIES))
        compose.waitForText("TMDB has no suggestions for this", stepMs = 50)
        assertFalse(shown("In your library"))
    }

    @Test
    fun `a film with no TMDB guid on the server offers no More like this`() {
        tmdb.use(app.container)
        filmOptions("12")
        compose.settle()
        assertFalse(shown("More like this"))
    }

    @Test
    fun `without a TMDB key, a film's More… has no More like this and TMDB is not asked`() {
        tmdb.use(app.container, key = null)
        filmOptions()
        compose.settle()
        assertFalse(shown("More like this"))
        assertEquals(emptyList<String>(), tmdb.requests)
    }

    @Test
    fun `without a TMDB key, a show's options have no More like this`() {
        tmdb.use(app.container, key = null)
        showOptions()
        compose.settle()
        assertFalse(shown("More like this"))
        assertEquals(emptyList<String>(), tmdb.requests)
    }

    private val miraPage get() = Screen.Person(source, MIRA.id, MIRA.tagKey, MIRA.name, null, null, "The Long Harbour", Tab.MOVIES)

    @Test
    fun `with a TMDB key, a title in Also appears in opens its TMDB page, by Discover's TMDB id`() {
        tmdb.use(app.container)
        show(miraPage)
        compose.waitForText("Paper Boats", stepMs = 50)
        compose.click("Paper Boats")
        compose.waitAdvancingClock(stepMs = 50) { screen is Screen.TmdbTitle }
        val opened = screen as Screen.TmdbTitle
        assertEquals(
            Screen.TmdbTitle(source, FakeTmdb.PAPER_BOATS, series = false, "Paper Boats", null, Tab.MOVIES),
            opened.copy(posterUrl = null)
        )
        assertTrue("/library/metadata/d-4" in plex.discovered)
        compose.waitForText("Two sisters sail home.", stepMs = 50)
    }

    @Test
    fun `a title Discover gives no TMDB id opens nothing, with a notice`() {
        tmdb.use(app.container)
        show(miraPage)
        compose.waitForText("Glass Coast", stepMs = 50)
        compose.click("Glass Coast")
        compose.waitAdvancingClock(stepMs = 50) { app.container.notices.current.value != null }
        assertEquals("Couldn't find this title on TMDB", app.container.notices.current.value?.title)
        assertTrue(screen is Screen.Person)
    }

    @Test
    fun `without a TMDB key, a title in Also appears in opens nothing, and Discover is not asked for it`() {
        tmdb.use(app.container, key = null)
        show(miraPage)
        compose.waitForText("Paper Boats", stepMs = 50)
        compose.click("Paper Boats")
        compose.settle()
        assertTrue(screen is Screen.Person)
        assertFalse(plex.discovered.any { it.startsWith("/library/metadata/") })
    }

    private companion object {
        /** In the film; on Discover with two other films. No biography, so the first card has the remote. */
        val MIRA = FakePerson(
            BoardTitles.CAST,
            BoardTitles.tagKey(BoardTitles.CAST),
            "Mira Kell",
            creditTypes = mapOf("actor" to 3),
            credits = mapOf(
                "actor" to listOf(
                    FakeCredit(FakePlex.guidId(BoardTitles.FILM), "The Long Harbour", year = 2021, role = "Elias"),
                    FakeCredit("d-1", "Glass Coast", year = 2022, role = "Iona"),
                    FakeCredit("d-4", "Paper Boats", year = 2018, role = "Anna"),
                ),
            ),
            media = listOf(BoardTitles.FILM),
        )
    }
}
