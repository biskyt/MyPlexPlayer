package com.biskyt.mytvplayer.ui

import androidx.compose.runtime.remember
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.printToString
import com.biskyt.mytvplayer.testutil.Navigators
import com.biskyt.mytvplayer.testutil.TestApp
import com.biskyt.mytvplayer.testutil.createUiComposeRule
import com.biskyt.mytvplayer.testutil.waitAdvancingClock
import com.biskyt.mytvplayer.ui.theme.MyTvTheme
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** About and its open-source licences page. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestApp::class, qualifiers = "w960dp-h540dp-tvdpi")
class AboutUiTest {
    @get:Rule val compose = createUiComposeRule()

    @get:Rule(order = Navigators.ORDER)
    val navigators = Navigators()

    private fun show(start: Screen) {
        val navigator = navigators.new(start)
        compose.setContent { MyTvTheme { NavHost(remember { navigator }) { ScreenContent(it) } } }
    }

    private fun waitFor(text: String) = try {
        compose.waitAdvancingClock { compose.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty() }
    } catch (e: androidx.compose.ui.test.ComposeTimeoutException) {
        throw AssertionError("'$text' not shown. Screen:\n" + compose.onRoot(useUnmergedTree = true).printToString(), e)
    }

    private fun click(text: String) = compose.onNode(hasText(text) and hasClickAction()).performSemanticsAction(SemanticsActions.OnClick)

    @Test
    fun `about links to the licences page, which lists what the app is built with, each linking to its project`() {
        show(Screen.About)
        waitFor("Open source licences")
        // About ends with the support links too (as Settings does).
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("GitHub Sponsors"))
        click("Open source licences")
        waitFor("AndroidX Media3 1.8.0")
        compose.onRoot().captureRoboImage("screenshots/licences.png")
        click("AndroidX Media3 1.8.0")
        waitFor("github.com/androidx/media")
        waitFor("Licence: Apache 2.0")
        assertTrue(
            compose.onAllNodes(hasContentDescription("QR code for https://github.com/androidx/media")).fetchSemanticsNodes().isNotEmpty()
        )
    }
}
