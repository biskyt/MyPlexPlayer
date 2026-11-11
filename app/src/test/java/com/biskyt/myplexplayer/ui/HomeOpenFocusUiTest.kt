package com.biskyt.myplexplayer.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ApplicationProvider
import com.biskyt.myplexplayer.testutil.FakePlex
import com.biskyt.myplexplayer.testutil.Navigators
import com.biskyt.myplexplayer.testutil.TestApp
import com.biskyt.myplexplayer.testutil.addSyncedHomePlex
import com.biskyt.myplexplayer.testutil.createUiComposeRule
import com.biskyt.myplexplayer.testutil.focusedText
import com.biskyt.myplexplayer.testutil.waitAdvancingClock
import com.biskyt.myplexplayer.ui.theme.MyPlexTheme
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Each time the app comes back to the front, Home is at its top, on its first card (the owner, 2026-11-11). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestApp::class, qualifiers = "+w960dp-h540dp-tvdpi")
class HomeOpenFocusUiTest {
    @get:Rule val compose = createUiComposeRule<ComponentActivity>()

    @get:Rule(order = Navigators.ORDER)
    val navigators = Navigators()

    private val plex = FakePlex()
    private val app = ApplicationProvider.getApplicationContext<TestApp>()

    @Before
    fun setUp() {
        runBlocking { app.container.addSyncedHomePlex(plex) }
    }

    @After
    fun tearDown() = plex.close()

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `back from the background, Home is on its first card again`() {
        val navigator = navigators.new(Screen.Home)
        compose.setContent { MyPlexTheme { NavHost(navigator) { ScreenContent(it) } } }
        compose.waitAdvancingClock(stepMs = 50) { "Test Show" in compose.focusedText() }
        // Down a row: the remote has been used, and the first card no longer has focus.
        compose.onRoot().performKeyInput { pressKey(Key.DirectionDown) }
        compose.waitAdvancingClock(stepMs = 50) { "Test Show" !in compose.focusedText() }
        assertNotEquals("", compose.focusedText())

        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)

        compose.waitAdvancingClock(stepMs = 50) { "Test Show" in compose.focusedText() }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `back from the background after moving far along the top row, Home is on its first card again`() {
        // Enough films that the top row's first card scrolls off the screen (it is then no longer made).
        plex.movies = (1..FILMS).map { FakePlex.film("${100 + it}", "Film $it", addedAt = 1_700_000_000L + it) }
        plex.shows = emptyList()
        plex.episodes = emptyList()
        runBlocking { app.container.vod.sync(app.container.db.playlists().all().single()) }
        val navigator = navigators.new(Screen.Home)
        compose.setContent { MyPlexTheme { NavHost(navigator) { ScreenContent(it) } } }
        compose.waitAdvancingClock(stepMs = 50) { "Film" in compose.focusedText() }
        val first = compose.focusedText()
        repeat(FILMS - 2) {
            val before = compose.focusedText()
            compose.onRoot().performKeyInput { pressKey(Key.DirectionRight) }
            compose.waitAdvancingClock(stepMs = 50) { compose.focusedText() != before }
        }
        // The first card (the newest film) is off the screen, so no longer made.
        assertTrue(compose.onAllNodes(hasText("Film $FILMS")).fetchSemanticsNodes().isEmpty())

        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)

        compose.waitAdvancingClock(stepMs = 50) { compose.focusedText() == first }
    }

    private companion object {
        const val FILMS = 15
    }
}
