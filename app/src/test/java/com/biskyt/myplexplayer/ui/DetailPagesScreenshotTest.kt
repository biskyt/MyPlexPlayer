package com.biskyt.myplexplayer.ui

import androidx.compose.runtime.remember
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.core.app.ApplicationProvider
import com.biskyt.myplexplayer.testutil.BoardTitles
import com.biskyt.myplexplayer.testutil.FakePlex
import com.biskyt.myplexplayer.testutil.Navigators
import com.biskyt.myplexplayer.testutil.OnAppBackground
import com.biskyt.myplexplayer.testutil.TestApp
import com.biskyt.myplexplayer.testutil.addSyncedHomePlex
import com.biskyt.myplexplayer.testutil.click
import com.biskyt.myplexplayer.testutil.createUiComposeRule
import com.biskyt.myplexplayer.testutil.focusedText
import com.biskyt.myplexplayer.testutil.idleFrames
import com.biskyt.myplexplayer.testutil.waitAdvancingClock
import com.biskyt.myplexplayer.testutil.waitForFilmPage
import com.biskyt.myplexplayer.testutil.waitForText
import com.biskyt.myplexplayer.ui.theme.MyPlexTheme
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.captureScreenRoboImage
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The film and series pages and their panels as the design boards draw them (2026-11-05), recorded to compare with
 * them: "PlexFilm", "PlexFilmMore", "PlexFilmTech", "PlexFilmAbout", "PlexSeries" and "PlexSeriesMore". Two more show
 * long titles: a film's on two lines, an episode's with focus on two.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestApp::class, qualifiers = "+w960dp-h540dp-tvdpi")
class DetailPagesScreenshotTest {
    @get:Rule val compose = createUiComposeRule()

    @get:Rule(order = Navigators.ORDER)
    val navigators = Navigators()

    private val plex = FakePlex()
    private val app = ApplicationProvider.getApplicationContext<TestApp>()

    @After
    fun tearDown() = plex.close()

    private fun start(screen: (Long) -> Screen, film: String = "The Long Harbour", show: String = "Coastline", long: Boolean = false) {
        plex.images = BoardTitles::art
        plex.movies = listOf(BoardTitles.film(film))
        plex.shows = listOf(BoardTitles.show(show))
        plex.episodes = BoardTitles.episodes(show, long)
        BoardTitles.watch(plex)
        val source = runBlocking { app.container.addSyncedHomePlex(plex) }
        val navigator = navigators.new(screen(source))
        compose.setContent { MyPlexTheme { OnAppBackground { NavHost(remember { navigator }) { ScreenContent(it) } } } }
    }

    /** The film page, once its art, scores and cast have loaded and Resume has the remote. */
    private fun filmPage(title: String = "The Long Harbour") {
        start({ Screen.MovieDetails(it, BoardTitles.FILM.toLong()) }, film = title)
        compose.waitForFilmPage()
        compose.idleFrames()
    }

    /** The whole screen: the panel's window over the page's. */
    @OptIn(ExperimentalRoborazziApi::class)
    private fun capture(file: String) = captureScreenRoboImage(file)

    @Test
    fun `the film page`() {
        filmPage()
        compose.onRoot().captureRoboImage("screenshots/redesign-plex-film.png")
    }

    @Test
    fun `the film page with a long title`() {
        filmPage("The Long Harbour: A Winter on the Island of Lost Boats, and the Crew Nobody Sent")
        compose.onRoot().captureRoboImage("screenshots/redesign-plex-film-long-title.png")
    }

    @Test
    fun `the film's More… panel`() {
        filmPage()
        compose.click("More…")
        compose.waitForText("Technical info", stepMs = 50)
        compose.waitForText("Delete…", stepMs = 50)
        compose.waitAdvancingClock(stepMs = 50) { "Mark as watched" in compose.focusedText() }
        capture("screenshots/redesign-plex-film-more.png")
    }

    @Test
    fun `the film's technical info`() {
        filmPage()
        compose.click("More…")
        compose.waitForText("Technical info", stepMs = 50)
        compose.click("Technical info")
        compose.waitForText("HEVC Main 10", stepMs = 50)
        capture("screenshots/redesign-plex-film-tech.png")
    }

    @Test
    fun `about the film`() {
        filmPage()
        compose.click("More…")
        compose.waitForText("About this film", stepMs = 50)
        compose.click("About this film")
        compose.waitForText("North Light Films", stepMs = 50)
        capture("screenshots/redesign-plex-film-about.png")
    }

    /** The series page with episode 4 open, its picture loaded. */
    private fun seriesPage(show: String = "Coastline", long: Boolean = false) {
        start({ Screen.SeriesDetails(it, BoardTitles.SHOW.toLong()) }, show = show, long = long)
        compose.waitForText("Continue S2 E4 · 21 min left", stepMs = 50)
        val episode = if (long) "The Lighthouse Keeper Who" else "The Lighthouse Keeper"
        compose.onNode(hasText(episode, substring = true) and hasClickAction()).performSemanticsAction(SemanticsActions.RequestFocus)
        compose.waitForText("More…", stepMs = 50)
        compose.waitForText("54 min · 21 min left · First shown 14 Mar 2023", stepMs = 50)
        compose.idleFrames()
    }

    @Test
    fun `the series page with episode pictures`() {
        seriesPage()
        compose.onRoot().captureRoboImage("screenshots/redesign-plex-series.png")
    }

    @Test
    fun `the series page with a long episode title`() {
        seriesPage("Coastline: The North Shore Detectives and the Case of the Winter Storm", long = true)
        compose.onRoot().captureRoboImage("screenshots/redesign-plex-series-long-title.png")
    }

    @Test
    fun `an episode's More… panel`() {
        seriesPage()
        compose.click("More…")
        compose.waitForText("THE SHOW", stepMs = 50)
        compose.waitForText("Delete the series…", stepMs = 50)
        compose.waitAdvancingClock(stepMs = 50) { "Resume from" in compose.focusedText() }
        capture("screenshots/redesign-plex-series-more.png")
    }
}
