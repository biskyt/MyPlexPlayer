package com.biskyt.myplexplayer.ui.phone

import android.content.pm.PackageManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import com.biskyt.myplexplayer.testutil.BoardTitles
import com.biskyt.myplexplayer.testutil.FakePlex
import com.biskyt.myplexplayer.testutil.FakeTmdb
import com.biskyt.myplexplayer.testutil.Navigators
import com.biskyt.myplexplayer.testutil.TestApp
import com.biskyt.myplexplayer.testutil.addSyncedHomePlex
import com.biskyt.myplexplayer.testutil.createUiComposeRule
import com.biskyt.myplexplayer.testutil.waitAdvancingClock
import com.biskyt.myplexplayer.testutil.waitForText
import com.biskyt.myplexplayer.ui.NavHost
import com.biskyt.myplexplayer.ui.Navigator
import com.biskyt.myplexplayer.ui.Screen
import com.biskyt.myplexplayer.ui.ScreenContent
import com.biskyt.myplexplayer.ui.common.Tab
import com.biskyt.myplexplayer.ui.theme.MyPlexTheme
import com.biskyt.myplexplayer.ui.theme.Palette
import com.biskyt.myplexplayer.ui.theme.autoFindsTv
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Search and the pages fed from TMDB on a phone (the owner, 2026-11-26: every screen has a phone layout), MyTVPlayer's
 * PhoneScreensUiTest on FakePlex: a tap plays or opens, and the pages under a tab have a back button and the tab bar.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestApp::class, qualifiers = "w390dp-h844dp-xxhdpi")
class PhoneScreensUiTest {
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
        shadowOf(app.packageManager).setSystemFeature(PackageManager.FEATURE_TOUCHSCREEN, true)
        autoFindsTv = { false } // the phone layout (TestApp sets the TV's)
        plex.movies = listOf(BoardTitles.film(), FakePlex.film("12", "Sintel", 2010))
        plex.shows = listOf(BoardTitles.show())
        plex.episodes = BoardTitles.episodes()
        plex.tmdbIds = mapOf(BoardTitles.FILM to FakeTmdb.FILM)
        source = app.container.addSyncedHomePlex(plex)
    }

    @After
    fun tearDown() {
        plex.close()
        tmdb.close()
    }

    private fun show(vararg screens: Screen) {
        navigator = navigators.new(screens.first())
        screens.drop(1).forEach(navigator::push)
        compose.setContent {
            MyPlexTheme {
                Box(Modifier.fillMaxSize().background(Palette.background)) { NavHost(remember { navigator }) { ScreenContent(it) } }
            }
        }
    }

    private fun tap(text: String) = compose.onAllNodes(hasText(text) and hasClickAction())[0].performClick()

    private val screen get() = compose.runOnIdle { navigator.current.screen }

    @Test
    fun `a search finds films, and a tap opens one`() {
        show(Screen.Search("harbour"))
        compose.waitForText("The Long Harbour", stepMs = 50)
        tap("The Long Harbour")
        compose.waitAdvancingClock(stepMs = 50) { screen is Screen.MovieDetails }
        assertEquals(Screen.MovieDetails(source, BoardTitles.FILM.toLong()), screen)
    }

    @Test
    fun `a search finds channels, and a tap plays one`() {
        show(Screen.Search("two"))
        compose.waitForText("Two", stepMs = 50)
        tap("Two")
        compose.waitAdvancingClock(stepMs = 50) { screen is Screen.Player }
    }

    @Test
    fun `a long press on a programme found opens its options, with Record`() {
        show(Screen.Search("film 1"))
        compose.waitForText("Film 1", stepMs = 50)
        compose.onAllNodes(hasText("Film 1") and hasClickAction())[0].performTouchInput { longClick() }
        compose.waitAdvancingClock(stepMs = 50) { compose.onAllNodes(isRoot()).fetchSemanticsNodes().size > 1 }
        compose.waitForText("Record", stepMs = 50)
    }

    @Test
    fun `Search has the tab bar`() {
        show(Screen.Home, Screen.Search())
        compose.waitForText("Search", stepMs = 50)
        compose.onNode(hasText("Home") and hasClickAction()).assertExists()
    }

    @Test
    fun `More like this opens a title's TMDB page, which finds it on the server`() {
        tmdb.use(app.container)
        show(Screen.MoreLike(source, FakeTmdb.FILM, series = false, "The Long Harbour", Tab.MOVIES))
        compose.waitForText("More like The Long Harbour", stepMs = 50)
        compose.waitForText("Paper Boats", stepMs = 50)
        // Under the tab it was opened from, with a back button.
        compose.onNode(hasContentDescription("Back") and hasClickAction()).assertExists()
        compose.onNode(hasText("Movies") and hasClickAction()).assertExists()
        tap("Paper Boats")
        compose.waitAdvancingClock(stepMs = 50) { screen is Screen.TmdbTitle }
        compose.waitForText("Two sisters sail home.", stepMs = 50)
        tap("Find in your library")
        compose.waitAdvancingClock(stepMs = 50) { screen is Screen.Search }
        assertEquals(Screen.Search("Paper Boats"), screen)
    }

    @Test
    fun `a suggestion on the server opens its page there`() {
        tmdb.use(app.container)
        show(Screen.MoreLike(source, FakeTmdb.FILM, series = false, "The Long Harbour", Tab.MOVIES))
        compose.waitForText("2010 · In your library", stepMs = 50)
        tap("Sintel")
        compose.waitAdvancingClock(stepMs = 50) { screen is Screen.MovieDetails }
        assertEquals(Screen.MovieDetails(source, 12), screen)
    }

    @Test
    fun `a TMDB series page shows its seasons as chips, and a tap shows that season's episodes`() {
        tmdb.use(app.container)
        show(Screen.TmdbTitle(source, FakeTmdb.SIGNAL_HILL, series = true, "Signal Hill", null, Tab.SERIES))
        // The newest season shows first.
        compose.waitForText("The Second Run", stepMs = 50)
        compose.waitForText("Static", stepMs = 50)
        tap("Season 1")
        compose.waitForText("Episode One", stepMs = 50)
    }
}
