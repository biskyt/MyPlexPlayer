package com.biskyt.myplexplayer.ui

import androidx.compose.runtime.remember
import androidx.compose.ui.test.hasText
import androidx.test.core.app.ApplicationProvider
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
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The Live TV and DVR tabs show only when the server in use has Live TV (the owner, 2026-11-11). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestApp::class, qualifiers = "+w960dp-h540dp-tvdpi")
class LiveTvTabsUiTest {
    @get:Rule val compose = createUiComposeRule()

    @get:Rule(order = Navigators.ORDER)
    val navigators = Navigators()

    private val plex = FakePlex()
    private val app = ApplicationProvider.getApplicationContext<TestApp>()

    @After
    fun tearDown() = plex.close()

    /** Home, once [shownFirst] is on it. */
    private fun home(shownFirst: String = "Big Buck Bunny") {
        runBlocking { app.container.addSyncedHomePlex(plex) }
        val navigator = navigators.new(Screen.Home)
        compose.setContent { MyPlexTheme { NavHost(remember { navigator }) { ScreenContent(it) } } }
        compose.waitForText(shownFirst, stepMs = 50)
    }

    private fun shown(text: String) = compose.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun `a server with Live TV has the Live TV and DVR tabs`() {
        home()
        compose.waitAdvancingClock(stepMs = 50) { shown("Live TV") && shown("DVR") }
    }

    @Test
    fun `a server without Live TV has neither tab`() {
        plex.liveTv = false
        home()
        compose.waitAdvancingClock(stepMs = 50) { !shown("Live TV") }
        assertFalse(shown("DVR"))
    }

    @Test
    fun `with no Live TV and nothing else on Home, no channels row asks for channels`() {
        plex.liveTv = false
        plex.movies = emptyList()
        plex.shows = emptyList()
        plex.episodes = emptyList()
        home(shownFirst = "Home")
        compose.waitAdvancingClock(stepMs = 50) { !shown("Live TV") }
        assertFalse(shown("Your channels, on now"))
    }

    @Test
    fun `a server that refuses Live TV has neither tab`() {
        plex.liveTvRefused = true
        home()
        compose.waitAdvancingClock(stepMs = 50) { !shown("Live TV") }
        assertFalse(shown("DVR"))
    }
}
