package com.biskyt.myplexplayer.ui.settings

import android.view.KeyEvent as NativeKeyEvent
import androidx.compose.runtime.remember
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performKeyPress
import androidx.compose.ui.test.pressKey
import androidx.test.core.app.ApplicationProvider
import com.biskyt.myplexplayer.testutil.Navigators
import com.biskyt.myplexplayer.testutil.TestApp
import com.biskyt.myplexplayer.testutil.createUiComposeRule
import com.biskyt.myplexplayer.testutil.idleFrames
import com.biskyt.myplexplayer.testutil.waitAdvancingClock
import com.biskyt.myplexplayer.testutil.waitForText
import com.biskyt.myplexplayer.ui.NavHost
import com.biskyt.myplexplayer.ui.Screen
import com.biskyt.myplexplayer.ui.ScreenContent
import com.biskyt.myplexplayer.ui.theme.MyPlexTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Settings shown as "◀ value ▶" step back and forward with left and right; OK moves on to the next, as before. */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestApp::class, qualifiers = "+w960dp-h540dp-tvdpi")
class SettingsKeysUiTest {
    @get:Rule val compose = createUiComposeRule()

    @get:Rule(order = Navigators.ORDER)
    val navigators = Navigators()

    private val app = ApplicationProvider.getApplicationContext<TestApp>()

    private fun skipBack() = runBlocking { app.container.settings.current().skipBackSec }

    private fun press(key: Key) {
        compose.onRoot().performKeyInput { pressKey(key) }
        compose.waitForIdle()
    }

    @Test
    fun `left and right step a value back and forward, and ok moves it on`() {
        val navigator = navigators.new(Screen.PlaybackSettings)
        compose.setContent { MyPlexTheme { NavHost(remember { navigator }) { ScreenContent(it) } } }
        compose.waitForText("Skip back")
        assertEquals(10, skipBack()) // focus starts on it
        press(Key.DirectionLeft)
        compose.waitAdvancingClock { skipBack() == 5 }
        press(Key.DirectionLeft) // the first wraps round to the last
        compose.waitAdvancingClock { skipBack() == 120 }
        press(Key.DirectionRight)
        compose.waitAdvancingClock { skipBack() == 5 }
        press(Key.DirectionCenter)
        compose.waitAdvancingClock { skipBack() == 10 }
        compose.waitForText("10 s")
    }

    @Test
    fun `holding left steps once`() {
        val navigator = navigators.new(Screen.PlaybackSettings)
        compose.setContent { MyPlexTheme { NavHost(remember { navigator }) { ScreenContent(it) } } }
        compose.waitForText("Skip back")
        fun left(action: Int, repeat: Int) = compose.onRoot().performKeyPress(
            KeyEvent(NativeKeyEvent(0, 0, action, NativeKeyEvent.KEYCODE_DPAD_LEFT, repeat))
        )
        for (i in 0..4) left(NativeKeyEvent.ACTION_DOWN, repeat = i)
        left(NativeKeyEvent.ACTION_UP, repeat = 0)
        compose.waitAdvancingClock { skipBack() != 10 }
        // Settings are saved off the main thread: give any further steps time to arrive.
        repeat(5) {
            Thread.sleep(100)
            compose.idleFrames()
        }
        assertEquals(5, skipBack())
    }
}
