package com.biskyt.myplexplayer.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.core.app.ApplicationProvider
import androidx.tv.material3.Text
import com.biskyt.myplexplayer.core.plex.DiscoverTitle
import com.biskyt.myplexplayer.core.tmdb.SeriesStatus
import com.biskyt.myplexplayer.core.tmdb.TmdbEpisode
import com.biskyt.myplexplayer.core.tmdb.TmdbTitle
import com.biskyt.myplexplayer.testutil.BoardTitles
import com.biskyt.myplexplayer.testutil.FakePlex
import com.biskyt.myplexplayer.testutil.FakeShowFacts
import com.biskyt.myplexplayer.testutil.FakeTmdb
import com.biskyt.myplexplayer.testutil.Navigators
import com.biskyt.myplexplayer.testutil.OnAppBackground
import com.biskyt.myplexplayer.testutil.TestApp
import com.biskyt.myplexplayer.testutil.addSyncedHomePlex
import com.biskyt.myplexplayer.testutil.click
import com.biskyt.myplexplayer.testutil.createUiComposeRule
import com.biskyt.myplexplayer.testutil.settle
import com.biskyt.myplexplayer.testutil.waitForText
import com.biskyt.myplexplayer.ui.common.Tab
import com.biskyt.myplexplayer.ui.common.formatDate
import com.biskyt.myplexplayer.ui.theme.MyPlexTheme
import com.biskyt.myplexplayer.ui.vod.nextEpisodeLine
import com.biskyt.myplexplayer.ui.vod.seasonCountText
import com.biskyt.myplexplayer.ui.vod.seriesStatusLine
import com.biskyt.myplexplayer.ui.vod.seriesStatusOf
import java.time.LocalDate
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

/**
 * A series as a whole (MyTVPlayer 1.26.0, from Plex first: the owner, 2026-11-17). The series page shows the whole
 * show's season count with those on the server, Discover's status and next episode's date (TMDB's finer status and the
 * episode's code with a TMDB key), and TMDB's words for an episode the server says nothing about. A TMDB page shows a
 * series' seasons and their episodes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestApp::class, qualifiers = "+w960dp-h540dp-tvdpi")
class SeriesStatusUiTest {
    @get:Rule val compose = createUiComposeRule()

    @get:Rule(order = Navigators.ORDER)
    val navigators = Navigators()

    private val plex = FakePlex()
    private val tmdb = FakeTmdb()
    private val app = ApplicationProvider.getApplicationContext<TestApp>()
    private var source = 0L

    @Before
    fun setUp() = runBlocking {
        plex.movies = listOf(BoardTitles.film())
        plex.shows = listOf(BoardTitles.show())
        plex.episodes = BoardTitles.episodes()
        plex.tmdbIds = mapOf(BoardTitles.FILM to FakeTmdb.FILM, BoardTitles.SHOW to FakeTmdb.SHOW)
        plex.discoverShows = mapOf(FakePlex.guidId(BoardTitles.SHOW) to FakeShowFacts(true, seasons = 6, episodes = 22, next = NEXT))
        BoardTitles.watch(plex)
        source = app.container.addSyncedHomePlex(plex)
    }

    @After
    fun tearDown() {
        plex.close()
        tmdb.close()
    }

    private lateinit var navigator: Navigator

    private fun show(screen: Screen) {
        navigator = navigators.new(screen)
        compose.setContent { MyPlexTheme { OnAppBackground { NavHost(remember { navigator }) { ScreenContent(it) } } } }
    }

    private fun shown(text: String) = compose.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty()

    /** Each of [lines] (null shown as "-"), one under another. */
    private fun lines(vararg lines: @Composable () -> String?) {
        compose.setContent { MyPlexTheme { Column { lines.forEach { Text(it() ?: "-") } } } }
        compose.settle()
    }

    /** The lines shown, in order. */
    private fun texts(): List<String> = compose.onAllNodes(hasText("", substring = true)).fetchSemanticsNodes()
        .map { it.config[SemanticsProperties.Text].joinToString() }

    @Test
    fun `the season count is the whole show's, with those on the server when it has fewer`() {
        lines(
            { seasonCountText(total = 6, onServer = 6, listed = 6) },
            { seasonCountText(total = 6, onServer = 3, listed = 4) },
            { seasonCountText(total = null, onServer = 3, listed = 4) },
            { seasonCountText(total = 1, onServer = 1, listed = 1) },
        )
        // The same number; fewer on the server; not known (every season the server lists, as before); one season (its
        // name says it).
        assertEquals(listOf("6 series", "6 series (3 available)", "4 series", "-"), texts())
    }

    @Test
    fun `the status word is Discover's, and TMDB's finer word where there is a TMDB key`() {
        val discover = DiscoverTitle(tmdbId = null, continuing = false, seasonCount = 3, episodeCount = 18)
        val cancelled = tmdbTitle().copy(status = SeriesStatus.CANCELLED, episodeCount = 20)
        lines(
            { seriesStatusLine(seriesStatusOf(discover, null)) },
            { seriesStatusLine(seriesStatusOf(discover, cancelled), seasons = false) },
            { seriesStatusLine(seriesStatusOf(DiscoverTitle(null, continuing = true), null)) },
            { seriesStatusLine(seriesStatusOf(DiscoverTitle(null), null)) },
        )
        // Discover alone; Discover's counts with TMDB's word; Discover's word alone; nothing known.
        assertEquals(listOf("Ended · 3 series · 18 episodes", "Cancelled · 18 episodes", "Continuing", "-"), texts())
    }

    @Test
    fun `the next episode has Discover's date, and TMDB's code with a TMDB key`() {
        val discover = DiscoverTitle(tmdbId = null, nextEpisode = LocalDate.parse(NEXT))
        val tmdb = tmdbTitle().copy(nextEpisode = TmdbEpisode(4, 2, null, null, null, LocalDate.parse(NEXT)))
        val otherDay = tmdbTitle().copy(nextEpisode = TmdbEpisode(4, 1, null, null, null, LocalDate.parse(NEXT).minusDays(7)))
        lines(
            { nextEpisodeLine(seriesStatusOf(discover, null)) },
            { nextEpisodeLine(seriesStatusOf(discover, tmdb)) },
            { nextEpisodeLine(seriesStatusOf(null, tmdb)) },
            { nextEpisodeLine(seriesStatusOf(discover, otherDay)) },
        )
        // TMDB's code only with the same date: on another day it can be another episode.
        val date = formatDate(LocalDate.parse(NEXT))
        assertEquals(
            listOf("Next episode: $date", "Next episode: $date · S4E2", "Next episode: $date · S4E2", "Next episode: $date"),
            texts()
        )
    }

    @Test
    fun `no next episode for a series that has ended, nor one with a date already past`() {
        val next = LocalDate.parse(NEXT)
        val ended = DiscoverTitle(tmdbId = null, continuing = false, nextEpisode = next)
        val past = DiscoverTitle(tmdbId = null, continuing = true, nextEpisode = next)
        lines(
            { nextEpisodeLine(seriesStatusOf(ended, null)) },
            { nextEpisodeLine(seriesStatusOf(past, null, today = next.plusDays(1))) },
            { nextEpisodeLine(seriesStatusOf(past, null, today = next)) },
        )
        assertEquals(listOf("-", "-", "Next episode: ${formatDate(next)}"), texts())
    }

    @Test
    fun `without a TMDB key, the series page shows Discover's status, counts and next episode`() {
        tmdb.use(app.container, key = null)
        show(Screen.SeriesDetails(source, BoardTitles.SHOW.toLong()))
        compose.waitForText("Continuing · 22 episodes", stepMs = 50)
        compose.waitForText("Next episode: ${formatDate(LocalDate.parse(NEXT))}", stepMs = 50)
        // The server has series 1 to 3 of the show's 6: one count only.
        assertTrue(shown("6 series (3 available)"))
        assertFalse(shown("· 3 series"))
        assertFalse(shown("S4E2"))
        assertTrue("/library/metadata/${FakePlex.guidId(BoardTitles.SHOW)}" in plex.discovered)
        assertEquals(emptyList<String>(), tmdb.requests)
    }

    @Test
    fun `with a TMDB key, the series page has TMDB's finer status and the next episode's code`() {
        tmdb.use(app.container)
        show(Screen.SeriesDetails(source, BoardTitles.SHOW.toLong()))
        compose.waitForText("In production · 22 episodes", stepMs = 50)
        compose.waitForText("Next episode: ${formatDate(LocalDate.parse(NEXT))} · S4E2", stepMs = 50)
        assertTrue(shown("6 series (3 available)"))
    }

    @Test
    fun `when Discover fails, the page leaves its parts out and keeps the server's count`() {
        tmdb.use(app.container, key = null)
        plex.discoverShows = emptyMap()
        show(Screen.SeriesDetails(source, BoardTitles.SHOW.toLong()))
        compose.waitForText("Continue S2 E4 · 21 min left", stepMs = 50)
        compose.settle()
        assertTrue(shown("3 series"))
        assertFalse(shown("Next episode"))
        assertFalse(shown("available"))
    }

    @Test
    fun `with a TMDB key, an episode with no description gets TMDB's, and one with a description keeps the server's`() {
        tmdb.use(app.container)
        show(Screen.SeriesDetails(source, BoardTitles.SHOW.toLong()))
        compose.waitForText("Continue S2 E4 · 21 min left", stepMs = 50)
        focus("Low Water")
        compose.waitForText(FakeTmdb.LOW_WATER, stepMs = 50)
        focus("The Lighthouse Keeper")
        compose.waitForText("A body on the rocks", stepMs = 50)
        assertFalse(shown(FakeTmdb.KEEPER))
        assertTrue("tv/${FakeTmdb.SHOW}/season/2" in tmdb.requests)
    }

    @Test
    fun `without a TMDB key, an episode with no description stays without one, and TMDB is not asked`() {
        tmdb.use(app.container, key = null)
        show(Screen.SeriesDetails(source, BoardTitles.SHOW.toLong()))
        compose.waitForText("Continue S2 E4 · 21 min left", stepMs = 50)
        focus("Low Water")
        compose.settle()
        assertFalse(shown(FakeTmdb.LOW_WATER))
        assertEquals(emptyList<String>(), tmdb.requests)
    }

    @Test
    fun `a series' TMDB page shows its status, its seasons without the specials, and each season's episodes`() {
        tmdb.use(app.container)
        show(Screen.TmdbTitle(source, FakeTmdb.SIGNAL_HILL, series = true, "Signal Hill", null, Tab.SERIES))
        compose.waitForText("Ended · 2 series · 12 episodes", stepMs = 50)
        // It opens on the newest season.
        compose.waitForText("The Second Run", stepMs = 50)
        compose.waitForText("Static", stepMs = 50)
        compose.waitForText(formatDate(LocalDate.of(2023, 1, 1)), stepMs = 50)
        compose.waitForText("Dead Air", stepMs = 50)
        assertFalse(shown("Specials"))
        compose.click("Series 1")
        compose.waitForText("Episode One", stepMs = 50)
    }

    private fun focus(episode: String) {
        compose.onNode(hasText(episode) and hasClickAction()).performSemanticsAction(SemanticsActions.RequestFocus)
    }

    private fun tmdbTitle() = TmdbTitle(
        1, true, null, null, null, emptyList(), emptyList(), emptyList(), emptyList(), null, null, emptyList(), null, null,
        emptyList(), emptyList()
    )

    private companion object {
        /** When Coastline's next episode is shown, as Discover says. */
        const val NEXT = FakeTmdb.NEXT_EPISODE
    }
}
