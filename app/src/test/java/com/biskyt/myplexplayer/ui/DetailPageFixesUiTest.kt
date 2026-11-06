package com.biskyt.myplexplayer.ui

import android.view.KeyEvent
import androidx.compose.runtime.remember
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.core.app.ApplicationProvider
import com.biskyt.myplexplayer.core.plex.PlexAudioTrack
import com.biskyt.myplexplayer.core.plex.PlexMediaInfo
import com.biskyt.myplexplayer.core.vod.VodDetails
import com.biskyt.myplexplayer.testutil.BoardTitles
import com.biskyt.myplexplayer.testutil.FakePlex
import com.biskyt.myplexplayer.testutil.Navigators
import com.biskyt.myplexplayer.testutil.OnAppBackground
import com.biskyt.myplexplayer.testutil.TestApp
import com.biskyt.myplexplayer.testutil.addSyncedHomePlex
import com.biskyt.myplexplayer.testutil.createUiComposeRule
import com.biskyt.myplexplayer.testutil.focusedText
import com.biskyt.myplexplayer.testutil.sendKey
import com.biskyt.myplexplayer.testutil.waitAdvancingClock
import com.biskyt.myplexplayer.testutil.waitForText
import com.biskyt.myplexplayer.ui.theme.MyPlexTheme
import com.biskyt.myplexplayer.ui.vod.TechnicalInfoPanel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The film and series pages' keys and focus, and their panels' failures: the Menu button on an episode, holding OK on
 * Continue, the remote back on its episode after playing it, Resume without a length, a long Technical info, and a
 * panel's load failing in a way the server's errors don't.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestApp::class, qualifiers = "+w960dp-h540dp-tvdpi")
class DetailPageFixesUiTest {
    @get:Rule val compose = createUiComposeRule()

    @get:Rule(order = Navigators.ORDER)
    val navigators = Navigators()

    private val plex = FakePlex()
    private val app = ApplicationProvider.getApplicationContext<TestApp>()

    @After
    fun tearDown() = plex.close()

    /** The board's titles synced, then [screen] shown: its navigator. */
    private fun show(screen: (Long) -> Screen, film: JsonObject = BoardTitles.film()): Navigator {
        plex.movies = listOf(film)
        plex.shows = listOf(BoardTitles.show())
        plex.episodes = BoardTitles.episodes()
        BoardTitles.watch(plex)
        val source = runBlocking { app.container.addSyncedHomePlex(plex) }
        val navigator = navigators.new(screen(source))
        compose.setContent { MyPlexTheme { OnAppBackground { NavHost(remember { navigator }) { ScreenContent(it) } } } }
        return navigator
    }

    private fun series() = show({ Screen.SeriesDetails(it, BoardTitles.SHOW.toLong()) }).also {
        compose.waitForText("Continue S2 E4 · 21 min left", stepMs = 50)
    }

    private fun press(code: Int) {
        compose.sendKey(code, KeyEvent.ACTION_DOWN)
        compose.sendKey(code, KeyEvent.ACTION_UP)
    }

    @Test
    fun `Menu on an episode opens that episode's options, not the one to carry on with`() {
        series()
        compose.onNode(hasText("Undertow") and hasClickAction()).performSemanticsAction(SemanticsActions.RequestFocus)
        compose.waitAdvancingClock(stepMs = 50) { "Undertow" in compose.focusedText() }
        press(KeyEvent.KEYCODE_MENU)
        compose.waitForText("Coastline · S2 E5 Undertow", stepMs = 50)
    }

    @Test
    fun `holding OK on Continue opens its episode's options`() {
        series()
        compose.onNode(hasText("Continue S2 E4 · 21 min left") and hasClickAction()).performSemanticsAction(SemanticsActions.OnLongClick)
        compose.waitForText("Coastline · S2 E4 The Lighthouse Keeper", stepMs = 50)
    }

    @Test
    fun `back from an episode played from the list, the remote is on it again`() {
        val navigator = series()
        compose.onNode(hasText("Low Water") and hasClickAction()).performSemanticsAction(SemanticsActions.RequestFocus)
        compose.waitAdvancingClock(stepMs = 50) { "Low Water" in compose.focusedText() }
        compose.onNode(hasText("Low Water") and hasClickAction()).performSemanticsAction(SemanticsActions.OnClick)
        compose.waitAdvancingClock(stepMs = 50) { navigator.current.screen is Screen.VodPlayer }
        compose.runOnIdle { navigator.pop() }
        compose.waitAdvancingClock(stepMs = 50) { "Low Water" in compose.focusedText() }
    }

    @Test
    fun `a film part-watched with no known length offers Resume from and Restart`() {
        show({ Screen.MovieDetails(it, BoardTitles.FILM.toLong()) }, JsonObject(BoardTitles.film() + ("duration" to JsonPrimitive(0))))
        compose.waitForText("Resume from 1:14:00", stepMs = 50)
        compose.waitForText("Restart", stepMs = 50)
    }

    @Test
    fun `a long Technical info scrolls with the remote down to its last track`() {
        val tracks = (1..30).map { PlexAudioTrack("Language $it", "Dolby Digital", "5.1", 640) }
        val media = PlexMediaInfo(emptyList(), null, tracks, emptyList(), null, null, "MKV")
        val d = VodDetails(null, null, null, null, null, null, media = media)
        compose.setContent { MyPlexTheme { OnAppBackground { TechnicalInfoPanel(d, {}) } } }
        compose.waitForText("Language 1", stepMs = 50)
        assertTrue("the last track starts below the panel", !compose.onNode(hasText("Language 30")).isDisplayed())
        repeat(tracks.size + 1) { press(KeyEvent.KEYCODE_DPAD_DOWN) }
        compose.waitAdvancingClock(stepMs = 50) { compose.onNode(hasText("Language 30")).isDisplayed() }
    }

    @Test
    fun `a panel whose load fails in any way shows the error with Retry`() {
        compose.setContent {
            MyPlexTheme { OnAppBackground { TechnicalInfoPanel(null, {}) { throw IllegalStateException("No file in the reply") } } }
        }
        compose.waitForText("No file in the reply", stepMs = 50)
        compose.waitForText("Try again", stepMs = 50)
    }

    @Test
    fun `a file's size has its own line, also when its container is not known`() {
        val media = PlexMediaInfo(emptyList(), null, emptyList(), emptyList(), 18_600_000_000, null, null)
        val d = VodDetails(null, null, null, null, null, null, media = media)
        compose.setContent { MyPlexTheme { OnAppBackground { TechnicalInfoPanel(d, {}) } } }
        compose.waitForText("Size", stepMs = 50)
        assertTrue("no Container line", compose.onAllNodes(hasText("Container")).fetchSemanticsNodes().isEmpty())
    }
}
