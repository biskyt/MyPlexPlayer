package com.biskyt.myplexplayer.ui

import android.view.KeyEvent as NativeKeyEvent
import androidx.compose.runtime.remember
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasAnyChild
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performKeyPress
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.test.core.app.ApplicationProvider
import com.biskyt.myplexplayer.data.ChannelFilter
import com.biskyt.myplexplayer.testutil.FakePlex
import com.biskyt.myplexplayer.testutil.LocalMidday
import com.biskyt.myplexplayer.testutil.Navigators
import com.biskyt.myplexplayer.testutil.TestApp
import com.biskyt.myplexplayer.testutil.addSyncedHomePlex
import com.biskyt.myplexplayer.testutil.createUiComposeRule
import com.biskyt.myplexplayer.testutil.focusedText
import com.biskyt.myplexplayer.testutil.idleFrames
import com.biskyt.myplexplayer.testutil.settle
import com.biskyt.myplexplayer.testutil.topRoot
import com.biskyt.myplexplayer.testutil.waitAdvancingClock
import com.biskyt.myplexplayer.ui.theme.MyPlexTheme
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The TV guide's highlight with a long channel list and a full guide: held keys move it like presses, it reaches any
 * time ahead, and it stays in the grid at the edges; the List and TV guide buttons keep focus.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestApp::class, qualifiers = "+w960dp-h540dp-tvdpi")
class GuideGridKeysUiTest {
    @get:Rule val compose = createUiComposeRule()

    @get:Rule(order = Navigators.ORDER)
    val navigators = Navigators()

    @get:Rule val midday = LocalMidday() // the day test needs tomorrow to be hours away

    // 40 channels, each with twelve hours of programmes after the first two (channel n shows "Show n-1" now).
    private val plex = FakePlex().apply {
        channels = (1..CHANNELS).map { FakePlex.Channel("$it", "Channel %02d".format(it)) }
        laterHours = 12
    }
    private val app = ApplicationProvider.getApplicationContext<TestApp>()

    @After
    fun tearDown() = plex.close()

    private var backDispatcher: androidx.activity.OnBackPressedDispatcher? = null

    private fun guide() {
        runBlocking { app.container.addSyncedHomePlex(plex) }
        compose.setContent {
            backDispatcher = androidx.activity.compose.LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
            MyPlexTheme { NavHost(remember { navigators.new(Screen.Live(ChannelFilter.All, guide = true)) }) { ScreenContent(it) } }
        }
        compose.waitAdvancingClock { compose.focusedText() == "Show 0" }
    }

    private fun press(key: Key) {
        compose.onRoot().performKeyInput { pressKey(key) }
        compose.idleFrames()
    }

    /**
     * Presses [key] until the highlight is on [text]. A press moves it one programme at most (one made while the next
     * stretch of time is loading does nothing), so a press that skipped one would never get there.
     */
    private fun pressUntil(key: Key, text: String) = compose.waitAdvancingClock(50) {
        (compose.focusedText() == text).also { if (!it) press(key) }
    }

    /** The programme [title] is on the screen in full (not cut off at its edge): the highlight on it can be seen. */
    private fun assertOnScreen(title: String) {
        compose.settle()
        val cell = compose.onNode(hasAnyChild(hasText(title)) and hasClickAction()).fetchSemanticsNode()
        val shown = cell.boundsInRoot.width
        assertTrue("$title: $shown of its ${cell.size.width} shown", cell.size.width > 0 && shown >= cell.size.width - 1)
    }

    /** A remote button held down: the key and [repeats] repeats, as fast as a remote sends them (no frame between). */
    private fun hold(code: Int, repeats: Int) {
        for (r in 0..repeats) compose.topRoot().performKeyPress(KeyEvent(NativeKeyEvent(0, 0, NativeKeyEvent.ACTION_DOWN, code, r)))
        compose.topRoot().performKeyPress(KeyEvent(NativeKeyEvent(0, 0, NativeKeyEvent.ACTION_UP, code, 0)))
        compose.settle()
    }

    @Test
    fun `holding down moves one channel per repeat, and stays in the guide`() {
        guide()
        hold(NativeKeyEvent.KEYCODE_DPAD_DOWN, repeats = 29)
        compose.waitAdvancingClock(50) { compose.focusedText() == "Show 30" }
        // And back up again, held for longer: it stops on the first channel (leaving for the filter box takes a press of
        // its own).
        hold(NativeKeyEvent.KEYCODE_DPAD_UP, repeats = 39)
        compose.waitAdvancingClock(50) { compose.focusedText() == "Show 0" }
    }

    @Test
    fun `holding down at the last channel stays on it`() {
        guide()
        hold(NativeKeyEvent.KEYCODE_DPAD_DOWN, repeats = CHANNELS + 10)
        compose.waitAdvancingClock(50) { compose.focusedText() == "Show ${CHANNELS - 1}" }
    }

    @Test
    fun `right keeps going later, through the whole guide, and left comes back on the same channel`() {
        guide()
        press(Key.DirectionDown)
        press(Key.DirectionDown)
        compose.waitAdvancingClock(50) { compose.focusedText() == "Show 2" }
        pressUntil(Key.DirectionRight, "Film 2")
        assertOnScreen("Film 2")
        for (k in 1..12) {
            pressUntil(Key.DirectionRight, "Later $k of 2")
            assertOnScreen("Later $k of 2")
        }
        // Back to the start, one programme at a time, without leaving the row.
        for (k in 11 downTo 1) pressUntil(Key.DirectionLeft, "Later $k of 2")
        pressUntil(Key.DirectionLeft, "Film 2")
        pressUntil(Key.DirectionLeft, "Show 2")
    }

    @Test
    fun `left at the start of the guide stays in it, even held`() {
        guide()
        hold(NativeKeyEvent.KEYCODE_DPAD_LEFT, repeats = 10)
        assertEquals("Show 0", compose.focusedText())
        // Later, past the window's edge, then back to the start (each press once its programmes are in), and on.
        hold(NativeKeyEvent.KEYCODE_DPAD_RIGHT, repeats = 5)
        compose.waitAdvancingClock(50) { compose.focusedText() != "Show 0" }
        pressUntil(Key.DirectionLeft, "Show 0")
        hold(NativeKeyEvent.KEYCODE_DPAD_LEFT, repeats = 20)
        assertEquals("Show 0", compose.focusedText())
    }

    @Test
    fun `up from the first channel, or back, goes to the filter box, and from there left to the categories`() {
        guide()
        press(Key.DirectionDown)
        compose.waitAdvancingClock(50) { compose.focusedText() == "Show 1" }
        compose.runOnUiThread { backDispatcher!!.onBackPressed() } // the remote's Back, as the activity gets it
        compose.idleFrames()
        compose.waitAdvancingClock(50) { compose.focusedText() != "Show 1" }
        // Back down into the guide, on the same channel (past the touch screen's Earlier and Later buttons, which the
        // test app shows).
        repeat(3) { if (!compose.focusedText().startsWith("Show")) press(Key.DirectionDown) }
        assertEquals("Show 1", compose.focusedText())
        press(Key.DirectionUp)
        press(Key.DirectionUp) // from the first channel: out of the guide
        compose.waitAdvancingClock(50) { !compose.focusedText().startsWith("Show") }
        assertEquals("Filter by name or number", compose.focusedText())
        press(Key.DirectionLeft) // to the categories (their own filter box, on top)
        compose.waitAdvancingClock(50) { compose.focusedText() == "Filter categories" }
    }

    @Test
    fun `the time header says which day it is, and when a new one starts`() {
        plex.laterHours = 60 // into tomorrow, from midday
        guide()
        assertTrue(compose.onAllNodes(hasText("Today", substring = true)).fetchSemanticsNodes().isNotEmpty())
        // Later and later, until the header names the day starting.
        compose.waitAdvancingClock(50) {
            compose.onAllNodes(hasText("Tomorrow")).fetchSemanticsNodes().isNotEmpty().also {
                if (!it) press(Key.DirectionRight)
            }
        }
    }

    @Test
    fun `the list and tv guide buttons keep focus`() {
        guide()
        compose.onNode(hasText("List") and hasClickAction()).performSemanticsAction(SemanticsActions.RequestFocus)
        compose.waitAdvancingClock(50) { compose.focusedText() == "List" }
        press(Key.DirectionCenter)
        compose.waitAdvancingClock(50) { compose.onAllNodes(hasText("Channel 01")).fetchSemanticsNodes().isNotEmpty() }
        compose.settle()
        assertEquals("List", compose.focusedText())
        press(Key.DirectionRight)
        compose.waitAdvancingClock(50) { compose.focusedText() == "TV guide" }
        press(Key.DirectionCenter)
        compose.waitAdvancingClock(50) { compose.onAllNodes(hasText("Show 0")).fetchSemanticsNodes().isNotEmpty() }
        compose.settle()
        assertEquals("TV guide", compose.focusedText())
    }

    @Test
    fun `another list shows its first row, wherever the channel on top before is in it`() {
        // Favourites: channels 2 to 30, then channel 1 (on top of All channels).
        plex.favouriteChannels = (2..30).map { "ch-$it" } + "ch-1"
        guide()
        compose.onNode(hasText("Favourites") and hasClickAction()).performSemanticsAction(SemanticsActions.OnClick)
        compose.waitAdvancingClock(50) { compose.onAllNodes(hasText("Channel 02")).fetchSemanticsNodes().isNotEmpty() }
        compose.settle()
        assertTrue(compose.onAllNodes(hasText("Channel 02")).fetchSemanticsNodes().isNotEmpty())
    }

    @Test
    fun `all channels shows how many there are`() {
        plex.favouriteChannels = listOf("ch-1")
        guide()
        compose.waitAdvancingClock(50) { compose.onAllNodes(hasText("$CHANNELS")).fetchSemanticsNodes().isNotEmpty() }
    }

    private companion object {
        const val CHANNELS = 40
    }
}
