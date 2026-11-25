package com.biskyt.myplexplayer.ui.phone

import android.content.pm.PackageManager
import androidx.compose.runtime.remember
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.biskyt.myplexplayer.testutil.BoardTitles
import com.biskyt.myplexplayer.testutil.FakePlex
import com.biskyt.myplexplayer.testutil.Navigators
import com.biskyt.myplexplayer.testutil.OnAppBackground
import com.biskyt.myplexplayer.testutil.TestApp
import com.biskyt.myplexplayer.testutil.addSyncedHomePlex
import com.biskyt.myplexplayer.testutil.click
import com.biskyt.myplexplayer.testutil.createUiComposeRule
import com.biskyt.myplexplayer.testutil.idleFrames
import com.biskyt.myplexplayer.testutil.waitAdvancingClock
import com.biskyt.myplexplayer.testutil.waitForText
import com.biskyt.myplexplayer.ui.NavHost
import com.biskyt.myplexplayer.ui.Navigator
import com.biskyt.myplexplayer.ui.Screen
import com.biskyt.myplexplayer.ui.ScreenContent
import com.biskyt.myplexplayer.ui.theme.MyPlexTheme
import com.biskyt.myplexplayer.ui.theme.autoFindsTv
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.captureScreenRoboImage
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The film and series pages on a phone (the owner, 2026-11-25) with the board's titles: what a tap does, More as a
 * sheet, and screenshots (also unfolded) to compare with the mock-ups.
 */
@OptIn(ExperimentalRoborazziApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestApp::class, qualifiers = "w390dp-h844dp-xxhdpi")
class PhoneDetailPagesTest {
    @get:Rule val compose = createUiComposeRule()

    @get:Rule(order = Navigators.ORDER)
    val navigators = Navigators()

    private val plex = FakePlex()
    private val app = ApplicationProvider.getApplicationContext<TestApp>()
    private var source = 0L
    private lateinit var navigator: Navigator

    @Before
    fun setUp() {
        shadowOf(app.packageManager).setSystemFeature(PackageManager.FEATURE_TOUCHSCREEN, true)
        autoFindsTv = { false } // the phone layout (TestApp sets the TV's)
        plex.images = BoardTitles::art
        plex.movies = listOf(BoardTitles.film())
        plex.shows = listOf(BoardTitles.show())
        plex.episodes = BoardTitles.episodes()
        BoardTitles.watch(plex)
        source = runBlocking { app.container.addSyncedHomePlex(plex) }
    }

    @After
    fun tearDown() = plex.close()

    private fun open(screen: Screen) {
        navigator = navigators.new(Screen.Home)
        navigator.push(screen)
        compose.setContent { MyPlexTheme { OnAppBackground { NavHost(remember { navigator }) { ScreenContent(it) } } } }
    }

    private fun filmPage() {
        open(Screen.MovieDetails(source, BoardTitles.FILM.toLong()))
        compose.waitForText("Resume · 38 min left", stepMs = 50)
        compose.waitForText("Tom Hale", stepMs = 50)
    }

    private fun seriesPage() {
        open(Screen.SeriesDetails(source, BoardTitles.SHOW.toLong()))
        compose.waitForText("Continue S2 E4 · 21 min left", stepMs = 50)
        compose.waitForText("Low Water", stepMs = 50)
    }

    @Test
    fun `Resume plays the film from where it got to`() {
        filmPage()
        compose.click("Resume · 38 min left")
        compose.waitAdvancingClock(stepMs = 50) { navigator.current.screen is Screen.VodPlayer }
        val player = navigator.current.screen as Screen.VodPlayer
        assertEquals(BoardTitles.FILM.toLong(), player.itemId)
        assertEquals(false, player.fromStart)
    }

    @Test
    fun `a cast photo opens the person's page, and Back goes back to the film`() {
        filmPage()
        compose.click("Tom Hale")
        compose.waitAdvancingClock(stepMs = 50) { navigator.current.screen is Screen.Person }
        compose.runOnUiThread { navigator.pop() }
        compose.waitForText("Resume · 38 min left", stepMs = 50)
    }

    @Test
    fun `More opens as a sheet from the bottom`() {
        filmPage()
        compose.click("More")
        compose.waitForText("Technical info", stepMs = 50)
        val screen = compose.onAllNodes(isRoot())[0].fetchSemanticsNode().size.height
        // A sheet is 85 % of the screen at most, so its first option is not at the top, as a side panel's would be.
        val option = compose.onAllNodes(hasText("Mark as watched"))[0].fetchSemanticsNode().boundsInWindow
        assertTrue("$option on a screen $screen high", option.top > screen * SHEET_TOP)
        assertEquals(2, compose.onAllNodes(isRoot()).fetchSemanticsNodes().size)
    }

    @Test
    fun `a tap on an episode plays it`() {
        seriesPage()
        compose.click("Low Water")
        compose.waitAdvancingClock(stepMs = 50) { navigator.current.screen is Screen.VodPlayer }
        assertEquals(623L, (navigator.current.screen as Screen.VodPlayer).itemId)
    }

    @Test
    fun `Back on a film page with nothing under it goes to Home`() {
        navigator = navigators.new(Screen.MovieDetails(source, BoardTitles.FILM.toLong()))
        compose.setContent { MyPlexTheme { OnAppBackground { NavHost(remember { navigator }) { ScreenContent(it) } } } }
        compose.waitForText("Resume · 38 min left", stepMs = 50)
        compose.onNode(hasContentDescription("Back") and hasClickAction()).performClick()
        compose.waitAdvancingClock(stepMs = 50) { navigator.current.screen == Screen.Home }
    }

    @Test
    // Narrower than the board film's three labels and three link pills (as a phone with four labels, or large text).
    @Config(qualifiers = "w240dp-h700dp-xxhdpi")
    fun `on a narrow phone, the picture and sound labels and the links go on more lines`() {
        filmPage()
        // Where each is laid out (below the page's fold here), not what shows of it.
        val labels = listOf("4K", "Dolby Atmos 7.1").map { compose.onNode(hasText(it)).fetchSemanticsNode().positionInRoot.y }
        assertTrue("$labels", labels[1] > labels[0])
        compose.click("More")
        compose.waitForText("Technical info", stepMs = 50)
        // The sheet's link pills: the last of each name (the page's IMDb score comes first). Below the sheet's fold, so
        // where each is laid out, not what shows of it.
        val links = listOf("IMDb", "TVDB").map {
            compose.onAllNodes(hasContentDescription(it, ignoreCase = true), useUnmergedTree = true)
                .fetchSemanticsNodes().last().positionInRoot.y
        }
        assertTrue("$links", links[1] > links[0])
    }

    private fun capture(name: String) {
        repeat(SETTLE_STEPS) { compose.waitAdvancingClock(stepMs = 50) { true } }
        compose.idleFrames()
        compose.onRoot().captureRoboImage("screenshots/phone/$name.png")
    }

    private fun film(name: String) {
        filmPage()
        capture(name)
        compose.click("More")
        compose.waitForText("Cast and crew", stepMs = 50)
        repeat(SETTLE_STEPS) { compose.waitAdvancingClock(stepMs = 50) { true } }
        captureScreenRoboImage("screenshots/phone/$name-more.png")
    }

    private fun series(name: String) {
        seriesPage()
        capture(name)
    }

    @Test
    fun `the film page on a phone`() = film("film-phone")

    @Test
    fun `the series page on a phone`() = series("series-phone")

    @Test
    @Config(qualifiers = "w840dp-h720dp-xhdpi")
    fun `the film page unfolded`() = film("film-unfolded")

    @Test
    @Config(qualifiers = "w840dp-h720dp-xhdpi")
    fun `the series page unfolded`() = series("series-unfolded")

    private companion object {
        /** The highest a sheet's top comes: 15 % down the screen. */
        const val SHEET_TOP = 0.15f
        const val SETTLE_STEPS = 10
    }
}
