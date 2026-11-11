package com.biskyt.myplexplayer.ui

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.test.core.app.ApplicationProvider
import com.biskyt.myplexplayer.testutil.FakePlex
import com.biskyt.myplexplayer.testutil.Navigators
import com.biskyt.myplexplayer.testutil.TestApp
import com.biskyt.myplexplayer.testutil.addHomePlex
import com.biskyt.myplexplayer.testutil.createUiComposeRule
import com.biskyt.myplexplayer.testutil.focusedText
import com.biskyt.myplexplayer.testutil.screen
import com.biskyt.myplexplayer.testutil.waitAdvancingClock
import com.biskyt.myplexplayer.ui.theme.MyPlexTheme
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Home open before the first sync: as it fills, focus moves from the hint (which goes) to the new row on top. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestApp::class, qualifiers = "+w960dp-h540dp-tvdpi")
class HomeFillsUiTest {
    @get:Rule val compose = createUiComposeRule()

    @get:Rule(order = Navigators.ORDER)
    val navigators = Navigators()

    private val plex = FakePlex()
    private val app = ApplicationProvider.getApplicationContext<TestApp>()
    private var source = 0L

    @Before
    fun setUp() {
        runBlocking { source = app.container.addHomePlex(plex) }
    }

    @After
    fun tearDown() = plex.close()

    @Test
    fun `focus moves to the newest series once the first sync fills Home`() {
        val navigator = navigators.new(Screen.Home)
        compose.setContent { MyPlexTheme { NavHost(navigator) { ScreenContent(it) } } }
        // Nothing yet: the hint to add favourite channels, focused.
        compose.waitAdvancingClock(stepMs = 50) {
            compose.onAllNodes(hasText("Browse channels") and isFocused()).fetchSemanticsNodes().isNotEmpty()
        }

        runBlocking { app.container.vod.sync(app.container.db.playlists().get(source)!!) }

        focusReaches("Test Show")
    }

    @Test
    fun `focus moves up to the newest series when they come after the films`() {
        // The sync writes the films, then the series: Home can show the films' row on its own between the two (the
        // owner, 2026-11-11: "sometimes it starts half-way down the page, e.g. on recent movies").
        val shows = plex.shows
        plex.shows = emptyList()
        val navigator = navigators.new(Screen.Home)
        compose.setContent { MyPlexTheme { NavHost(navigator) { ScreenContent(it) } } }
        runBlocking { app.container.vod.sync(app.container.db.playlists().get(source)!!) }
        focusReaches("Sintel")

        plex.shows = shows
        runBlocking { app.container.vod.sync(app.container.db.playlists().get(source)!!) }

        focusReaches("Test Show")
    }

    @Test
    fun `focus moves up from a channel to the newest series when the sync fills the rows above`() {
        // The owner, 2026-11-11: Home started half-way down, on the channels, when rows above came after it.
        plex.favouriteChannels = listOf("ch-1")
        runBlocking { app.container.playlists.syncChannels(source) }
        val navigator = navigators.new(Screen.Home)
        compose.setContent { MyPlexTheme { NavHost(navigator) { ScreenContent(it) } } }
        compose.waitAdvancingClock(stepMs = 50) { "One" in compose.focusedText() }

        runBlocking { app.container.vod.sync(app.container.db.playlists().get(source)!!) }

        focusReaches("Test Show")
    }

    @OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
    @Test
    fun `once a key is pressed on Home, rows filled above leave focus where it is`() {
        val shows = plex.shows
        plex.shows = emptyList()
        val navigator = navigators.new(Screen.Home)
        compose.setContent { MyPlexTheme { NavHost(navigator) { ScreenContent(it) } } }
        runBlocking { app.container.vod.sync(app.container.db.playlists().get(source)!!) }
        focusReaches("Sintel")
        compose.onRoot().performKeyInput { pressKey(Key.DirectionRight) }
        compose.waitAdvancingClock(stepMs = 50) { true }
        val chosen = compose.focusedText()

        plex.shows = shows
        runBlocking { app.container.vod.sync(app.container.db.playlists().get(source)!!) }
        compose.waitAdvancingClock(stepMs = 50) { compose.onAllNodes(hasText("Test Show")).fetchSemanticsNodes().isNotEmpty() }
        compose.waitAdvancingClock(stepMs = 50) { true }

        assertEquals(chosen, compose.focusedText())
    }

    /** Waits for [text] in what has focus; on a timeout, says what has it and what is on screen. */
    private fun focusReaches(text: String) = try {
        compose.waitAdvancingClock(stepMs = 50) { text in compose.focusedText() }
    } catch (e: androidx.compose.ui.test.ComposeTimeoutException) {
        throw AssertionError("Focus not on '$text' but on '${compose.focusedText()}'. Screen:\n${compose.screen()}", e)
    }
}
