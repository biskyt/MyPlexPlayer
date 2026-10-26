package com.biskyt.myplexplayer.ui

import androidx.compose.runtime.remember
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.test.core.app.ApplicationProvider
import com.biskyt.myplexplayer.data.ChannelFilter
import com.biskyt.myplexplayer.testutil.FakePlex
import com.biskyt.myplexplayer.testutil.Navigators
import com.biskyt.myplexplayer.testutil.TestApp
import com.biskyt.myplexplayer.testutil.addSyncedHomePlex
import com.biskyt.myplexplayer.testutil.createUiComposeRule
import com.biskyt.myplexplayer.testutil.focusedText
import com.biskyt.myplexplayer.testutil.settle
import com.biskyt.myplexplayer.testutil.waitAdvancingClock
import com.biskyt.myplexplayer.ui.theme.MyPlexTheme
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Right and left across the TV guide keep the remote in it, scrolling the time along, and never go up to the top bar. */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestApp::class, qualifiers = "+w960dp-h540dp-tvdpi")
class GuideScrollUiTest {
    @get:Rule val compose = createUiComposeRule()

    @get:Rule(order = Navigators.ORDER)
    val navigators = Navigators()

    private val plex = FakePlex()
    private val app = ApplicationProvider.getApplicationContext<TestApp>()

    @Before
    fun setUp() = runBlocking {
        plex.channels = listOf(FakePlex.Channel("1", "One"), FakePlex.Channel("2", "Two"), FakePlex.Channel("3", "Three"))
        // Live TV opens on the favourites, as it does for the owner (Plex's, since 0.9.0).
        plex.favouriteChannels = listOf("1", "2")
        app.container.addSyncedHomePlex(plex)
        Unit
    }

    @After
    fun tearDown() = plex.close()

    private fun press(key: Key) {
        compose.onRoot().performKeyInput { pressKey(key) }
        compose.settle()
    }

    @Test
    fun `right past the last programme scrolls the guide on, with the remote still in it`() {
        compose.setContent {
            MyPlexTheme { NavHost(remember { navigators.new(Screen.Live(ChannelFilter.All)) }) { ScreenContent(it) } }
        }
        compose.waitAdvancingClock { compose.focusedText().contains("One") }
        press(Key.Guide)
        compose.waitAdvancingClock(50) { compose.focusedText().isNotEmpty() && !compose.focusedText().contains("One") }
        repeat(6) {
            press(Key.DirectionRight)
            assertFalse("pressed Right ${it + 1} times: ${compose.focusedText()}", compose.focusedText().contains("Home Plex"))
        }
        repeat(6) {
            press(Key.DirectionLeft)
            assertFalse("pressed Left ${it + 1} times: ${compose.focusedText()}", compose.focusedText().contains("Home Plex"))
        }
    }

    /** A held Right sends repeats before the guide has caught up with the scroll: they're the same press. */
    @Test
    fun `a held right across the guide's edge keeps the remote in it`() {
        compose.setContent {
            MyPlexTheme { NavHost(remember { navigators.new(Screen.Live(ChannelFilter.All)) }) { ScreenContent(it) } }
        }
        compose.waitAdvancingClock { compose.focusedText().contains("One") }
        press(Key.Guide)
        compose.waitAdvancingClock(50) { compose.focusedText().isNotEmpty() && !compose.focusedText().contains("One") }
        press(Key.DirectionRight) // the last programme in the window
        compose.onRoot().performKeyInput {
            keyDown(Key.DirectionRight)
            advanceEventTime(800) // held: repeats follow, before a frame has drawn
            keyUp(Key.DirectionRight)
        }
        compose.settle()
        val focus = compose.focusedText()
        assertFalse("after a held Right: $focus", focus.contains("Home Plex") || focus.isEmpty())
        press(Key.DirectionRight)
        assertFalse("then Right again: ${compose.focusedText()}", compose.focusedText().contains("Home Plex"))
    }
}
