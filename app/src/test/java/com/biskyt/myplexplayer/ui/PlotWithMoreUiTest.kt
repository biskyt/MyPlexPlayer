package com.biskyt.myplexplayer.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.biskyt.myplexplayer.testutil.TestApp
import com.biskyt.myplexplayer.testutil.createUiComposeRule
import com.biskyt.myplexplayer.testutil.idleFrames
import com.biskyt.myplexplayer.testutil.waitAdvancingClock
import com.biskyt.myplexplayer.ui.theme.MyPlexTheme
import com.biskyt.myplexplayer.ui.vod.PlotWithMore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A plot cut short shows Read more, which opens the whole of it in a panel; one that fits shows no Read more (2026-11-06). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestApp::class, qualifiers = "+w960dp-h540dp-tvdpi")
class PlotWithMoreUiTest {
    @get:Rule val compose = createUiComposeRule()

    private fun show(plot: String) {
        compose.setContent {
            MyPlexTheme {
                Column(Modifier.width(300.dp)) { PlotWithMore("Kitchen Masters", plot, TextStyle(fontSize = 14.sp), maxLines = 2) }
            }
        }
        compose.idleFrames()
    }

    private fun more() = compose.onAllNodes(hasText("Read more") and hasClickAction()).fetchSemanticsNodes()

    @Test
    fun `a plot cut short opens in full from Read more`() {
        show(LONG)
        assertEquals(1, more().size)
        compose.onNode(hasText("Read more") and hasClickAction()).performSemanticsAction(SemanticsActions.OnClick)
        // The panel is a window of its own, with the title and the whole plot.
        compose.waitAdvancingClock(stepMs = 50) { compose.onAllNodes(isRoot()).fetchSemanticsNodes().size == 2 }
        val panel = compose.onAllNodes(isRoot())[1]
        assertTrue(compose.onAllNodes(hasText("Kitchen Masters"), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())
        panel.assertExists()
        assertTrue(compose.onAllNodes(hasText(LONG), useUnmergedTree = true).fetchSemanticsNodes().size >= 2)
    }

    @Test
    fun `a plot that fits has no Read more`() {
        show("A short plot.")
        assertEquals(0, more().size)
    }

    private companion object {
        val LONG = "Two detectives on the north coast work the cases nobody else wants. ".repeat(6).trim()
    }
}
