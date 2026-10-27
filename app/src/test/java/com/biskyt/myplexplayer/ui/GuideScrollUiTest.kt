package com.biskyt.myplexplayer.ui

import android.os.Handler
import android.os.Looper
import androidx.arch.core.executor.ArchTaskExecutor
import androidx.arch.core.executor.TaskExecutor
import androidx.compose.runtime.remember
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
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
import java.util.concurrent.LinkedBlockingQueue
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

    /** The owner's steps: click the TV guide button, scroll the guide, go back to the categories, and into the guide again. */
    @Test
    fun `from the categories, right goes back into the guide, not to the buttons above it`() {
        compose.setContent {
            MyPlexTheme { NavHost(remember { navigators.new(Screen.Live(ChannelFilter.All)) }) { ScreenContent(it) } }
        }
        compose.waitAdvancingClock { compose.focusedText().contains("One") }
        compose.onNode(hasText("TV guide") and hasClickAction()).performSemanticsAction(SemanticsActions.RequestFocus)
        compose.waitAdvancingClock { compose.focusedText().contains("TV guide") }
        press(Key.DirectionCenter)
        // The button keeps focus (the guide is below it, a press away).
        compose.waitAdvancingClock(50) { compose.onAllNodes(hasText("Show 0")).fetchSemanticsNodes().isNotEmpty() }
        assertTrue("on the button after the click: ${compose.focusedText()}", compose.focusedText().contains("TV guide"))
        // (Past the touch screen's Earlier and Later buttons, which the test app shows.)
        repeat(3) { if (!inGuide(compose.focusedText())) press(Key.DirectionDown) }
        assertTrue("in the guide: ${compose.focusedText()}", inGuide(compose.focusedText()))
        repeat(3) { press(Key.DirectionRight) }
        // Over to the categories (Left at the guide's start stays in it).
        compose.onNode(hasText("All channels") and hasClickAction()).performSemanticsAction(SemanticsActions.RequestFocus)
        compose.settle()
        assertTrue("in the categories: ${compose.focusedText()}", compose.focusedText().contains("All channels"))
        repeat(8) {
            press(Key.DirectionRight)
            val focus = compose.focusedText()
            assertTrue("Right ${it + 1} from the categories: $focus", inGuide(focus))
        }
    }

    /** A programme in the guide (or a row without one). */
    private fun inGuide(focus: String) = "Show" in focus || "Film" in focus || "No guide" in focus

    /**
     * The Guide button from the list takes focus into the guide, which keeps it while its programmes load and arrive.
     * (Before 0.11.0 a placeholder cell held focus and was replaced when they arrived; some TVs then handed focus to
     * the Home tab. The guide is now one focus stop that stays put, whatever its rows do.)
     */
    @Test
    fun `the guide keeps focus while its programmes load and arrive`() {
        compose.setContent {
            MyPlexTheme { NavHost(remember { navigators.new(Screen.Live(ChannelFilter.All)) }) { ScreenContent(it) } }
        }
        compose.waitAdvancingClock { compose.focusedText().contains("One") }
        val held = LinkedBlockingQueue<Runnable>()
        ArchTaskExecutor.getInstance().setDelegate(
            object : TaskExecutor() {
                override fun executeOnDiskIO(runnable: Runnable) {
                    held += runnable
                }

                override fun postToMainThread(runnable: Runnable) {
                    Handler(Looper.getMainLooper()).post(runnable)
                }

                override fun isMainThread() = Looper.getMainLooper().isCurrentThread
            }
        )
        try {
            press(Key.Guide)
            compose.settle()
            assertTrue(
                "in the guide while loading: ${compose.focusedText()}",
                inGuide(compose.focusedText()) || compose.focusedText() == ""
            )
            assertFalse(compose.focusedText(), compose.focusedText().contains("One"))
        } finally {
            ArchTaskExecutor.getInstance().setDelegate(null)
            generateSequence { held.poll() }.forEach(ArchTaskExecutor.getInstance()::executeOnDiskIO)
        }
        compose.waitAdvancingClock(50) { compose.focusedText().contains("Show 0") }
    }

    /** Opened on the guide with no filter given, the guide waits on the database: it isn't there to take focus yet. */
    @Test
    fun `right from the categories before the guide is there doesn't fail`() {
        val held = LinkedBlockingQueue<Runnable>()
        ArchTaskExecutor.getInstance().setDelegate(
            object : TaskExecutor() {
                override fun executeOnDiskIO(runnable: Runnable) {
                    held += runnable
                }

                override fun postToMainThread(runnable: Runnable) {
                    Handler(Looper.getMainLooper()).post(runnable)
                }

                override fun isMainThread() = Looper.getMainLooper().isCurrentThread
            }
        )
        try {
            compose.setContent {
                MyPlexTheme { NavHost(remember { navigators.new(Screen.Live(guide = true)) }) { ScreenContent(it) } }
            }
            compose.waitAdvancingClock { compose.onAllNodes(hasText("All channels")).fetchSemanticsNodes().isNotEmpty() }
            compose.onNode(hasText("All channels") and hasClickAction()).performSemanticsAction(SemanticsActions.RequestFocus)
            compose.settle()
            press(Key.DirectionRight)
        } finally {
            ArchTaskExecutor.getInstance().setDelegate(null)
            generateSequence { held.poll() }.forEach(ArchTaskExecutor.getInstance()::executeOnDiskIO)
        }
        compose.waitAdvancingClock(50) { compose.onAllNodes(hasText("Film 0")).fetchSemanticsNodes().isNotEmpty() }
    }
}
