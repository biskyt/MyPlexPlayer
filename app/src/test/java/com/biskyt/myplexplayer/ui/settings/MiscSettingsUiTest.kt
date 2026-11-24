package com.biskyt.myplexplayer.ui.settings

import androidx.compose.runtime.remember
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performScrollToNode
import com.biskyt.myplexplayer.testutil.Navigators
import com.biskyt.myplexplayer.testutil.TestApp
import com.biskyt.myplexplayer.testutil.createUiComposeRule
import com.biskyt.myplexplayer.testutil.focusedText
import com.biskyt.myplexplayer.testutil.waitAdvancingClock
import com.biskyt.myplexplayer.testutil.waitForText
import com.biskyt.myplexplayer.ui.NavHost
import com.biskyt.myplexplayer.ui.Screen
import com.biskyt.myplexplayer.ui.ScreenContent
import com.biskyt.myplexplayer.ui.theme.MyPlexTheme
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The TV's home screen and the VPN warning are in Misc settings, not in the main list (MyTVPlayer 1.35.3). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestApp::class, qualifiers = "+w960dp-h540dp-tvdpi")
class MiscSettingsUiTest {
    @get:Rule val compose = createUiComposeRule()

    @get:Rule(order = Navigators.ORDER)
    val navigators = Navigators()

    @Test
    fun `misc settings hold the VPN warning, which takes the focus away from a TV`() {
        val navigator = navigators.new(Screen.MiscSettings)
        compose.setContent { MyPlexTheme { NavHost(remember { navigator }) { ScreenContent(it) } } }
        compose.waitForText("Warn if VPN is not connected")
        // Not a TV here: the home screen row is not there, so the first row is the VPN warning.
        compose.waitAdvancingClock { "Warn if VPN is not connected" in compose.focusedText() }
    }

    @Test
    fun `the main settings list opens Misc in place of the VPN warning`() {
        val navigator = navigators.new(Screen.Settings)
        compose.setContent { MyPlexTheme { NavHost(remember { navigator }) { ScreenContent(it) } } }
        compose.waitForText("Playlists")
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Misc"))
        // Scrolling looks through the whole list.
        assertThrows(AssertionError::class.java) {
            compose.onNode(hasScrollAction()).performScrollToNode(hasText("Warn if VPN is not connected"))
        }
    }
}
