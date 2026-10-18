package com.biskyt.mytvplayer.ui

import android.view.KeyEvent.ACTION_DOWN
import android.view.KeyEvent.ACTION_UP
import android.view.KeyEvent.KEYCODE_DPAD_CENTER
import android.view.KeyEvent.KEYCODE_INFO
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performKeyPress
import androidx.tv.material3.Text
import com.biskyt.mytvplayer.testutil.TestApp
import com.biskyt.mytvplayer.ui.common.SidePanel
import com.biskyt.mytvplayer.ui.common.TvCard
import com.biskyt.mytvplayer.ui.common.rememberFirstFocus
import com.biskyt.mytvplayer.ui.theme.MyTvTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestApp::class)
class RemoteKeysUiTest {
    @get:Rule val compose = createComposeRule()

    private fun key(action: Int, code: Int, repeat: Int = 0) = KeyEvent(android.view.KeyEvent(0L, 0L, action, code, repeat))

    @Test
    fun `info button opens the options and a held OK doesn't pick from them`() {
        var options by mutableStateOf(false)
        var watched by mutableIntStateOf(0)
        compose.setContent {
            MyTvTheme {
                TvCard(onClick = {}, onLongClick = { options = true }, modifier = rememberFirstFocus()) { Text("Channel") }
                if (options) {
                    SidePanel(onDismiss = { options = false }) {
                        TvCard(onClick = { watched++ }, modifier = rememberFirstFocus()) { Text("Watch") }
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.onNode(hasText("Channel")).performKeyPress(key(ACTION_DOWN, KEYCODE_INFO))
        compose.onNode(hasText("Channel")).performKeyPress(key(ACTION_UP, KEYCODE_INFO))
        compose.waitForIdle()
        assertEquals(true, options)

        // The rest of a long OK press (repeats, then the release) arrives at the menu: nothing is picked.
        val watch = compose.onNode(hasText("Watch"))
        watch.performKeyPress(key(ACTION_DOWN, KEYCODE_DPAD_CENTER, repeat = 3))
        watch.performKeyPress(key(ACTION_UP, KEYCODE_DPAD_CENTER))
        compose.waitForIdle()
        assertEquals(0, watched)

        // A fresh press does pick.
        watch.performKeyPress(key(ACTION_DOWN, KEYCODE_DPAD_CENTER))
        watch.performKeyPress(key(ACTION_UP, KEYCODE_DPAD_CENTER))
        compose.waitForIdle()
        assertEquals(1, watched)
    }
}
