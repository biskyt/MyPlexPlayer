package com.biskyt.myplexplayer.ui.phone

import android.content.pm.PackageManager
import androidx.compose.runtime.remember
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.biskyt.myplexplayer.testutil.BoardTitles
import com.biskyt.myplexplayer.testutil.FakePlex
import com.biskyt.myplexplayer.testutil.Navigators
import com.biskyt.myplexplayer.testutil.OnAppBackground
import com.biskyt.myplexplayer.testutil.TestApp
import com.biskyt.myplexplayer.testutil.addSyncedHomePlex
import com.biskyt.myplexplayer.testutil.createUiComposeRule
import com.biskyt.myplexplayer.testutil.waitAdvancingClock
import com.biskyt.myplexplayer.testutil.waitForText
import com.biskyt.myplexplayer.ui.NavHost
import com.biskyt.myplexplayer.ui.Screen
import com.biskyt.myplexplayer.ui.ScreenContent
import com.biskyt.myplexplayer.ui.theme.MyPlexTheme
import com.biskyt.myplexplayer.ui.theme.autoFindsTv
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * A film's and a series' page on a phone (MyTVPlayer 1.39.0, the owner, 2026-11-25): the tabs, and no empty space for
 * a backdrop that does not load.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestApp::class, qualifiers = "w390dp-h844dp-xxhdpi")
class PhoneDetailFrameUiTest {
    @get:Rule val compose = createUiComposeRule()

    @get:Rule(order = Navigators.ORDER)
    val navigators = Navigators()

    private val plex = FakePlex()
    private val app = ApplicationProvider.getApplicationContext<TestApp>()
    private var source = 0L

    @Before
    fun setUp() {
        shadowOf(app.packageManager).setSystemFeature(PackageManager.FEATURE_TOUCHSCREEN, true)
        autoFindsTv = { false } // the phone layout (TestApp sets the TV's)
        // No pictures: the server sends an empty reply for each, so the backdrop does not load.
        plex.movies = listOf(BoardTitles.film())
        plex.shows = listOf(BoardTitles.show())
        plex.episodes = BoardTitles.episodes()
        source = runBlocking { app.container.addSyncedHomePlex(plex) }
    }

    @After
    fun tearDown() = plex.close()

    private fun show(screen: Screen) {
        val navigator = navigators.new(screen)
        compose.setContent { MyPlexTheme { OnAppBackground { NavHost(remember { navigator }) { ScreenContent(it) } } } }
    }

    @Test
    fun `a film's page has the tabs`() {
        show(Screen.MovieDetails(source, BoardTitles.FILM.toLong()))
        compose.waitForText("TV Shows", stepMs = 50)
    }

    @Test
    fun `a series' page has the tabs`() {
        show(Screen.SeriesDetails(source, BoardTitles.SHOW.toLong()))
        compose.waitForText("Movies", stepMs = 50)
    }

    @Test
    fun `when its backdrop does not load, the poster and title come up under the back button`() {
        // The server's backdrop fails, and TMDB is off: with no backdrop that loads, the space goes once the page's
        // reads are done.
        show(Screen.MovieDetails(source, BoardTitles.FILM.toLong()))
        compose.waitForText(TITLE, stepMs = 50)
        compose.waitAdvancingClock(stepMs = 50) { titleBottom() < px(240) }
        assertTrue(compose.onAllNodes(hasTestTag("backdrop")).fetchSemanticsNodes().isEmpty())
    }

    private fun px(dp: Int) = with(compose.density) { dp.dp.toPx() }

    /** Where the title ends, down the page: under the back button (72 high), or under a 230 high backdrop. */
    private fun titleBottom() = compose.onAllNodes(hasText(TITLE))[0].fetchSemanticsNode().boundsInRoot.bottom

    private companion object {
        const val TITLE = "The Long Harbour"
    }
}
