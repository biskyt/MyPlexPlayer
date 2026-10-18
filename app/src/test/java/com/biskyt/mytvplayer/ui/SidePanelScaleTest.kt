package com.biskyt.mytvplayer.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.tv.material3.Text
import com.biskyt.mytvplayer.testutil.TestApp
import com.biskyt.mytvplayer.ui.common.SidePanel
import com.biskyt.mytvplayer.ui.theme.MyTvTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** On a 1080p TV (system density 2.0, the app's scaled density 1.5), panels must use the app's scale too. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestApp::class, qualifiers = "w960dp-h540dp-xhdpi")
class SidePanelScaleTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun `text in a side panel is the same size as on screen`() {
        compose.setContent {
            MyTvTheme {
                Column { Text("On screen") }
                SidePanel(onDismiss = {}) { Text("In panel") }
            }
        }
        compose.waitForIdle()
        val screen = compose.onNode(hasText("On screen")).fetchSemanticsNode().size.height
        val panel = compose.onNode(hasText("In panel")).fetchSemanticsNode().size.height
        assertEquals(screen, panel)
    }
}
