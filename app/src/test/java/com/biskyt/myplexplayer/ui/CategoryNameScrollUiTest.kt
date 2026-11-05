package com.biskyt.myplexplayer.ui

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import com.biskyt.myplexplayer.testutil.Navigators
import com.biskyt.myplexplayer.testutil.TestApp
import com.biskyt.myplexplayer.testutil.createUiComposeRule
import com.biskyt.myplexplayer.testutil.waitAdvancingClock
import com.biskyt.myplexplayer.ui.common.CategoryColumn
import com.biskyt.myplexplayer.ui.common.CategoryEntry
import com.biskyt.myplexplayer.ui.theme.MyPlexTheme
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A long category name (Live TV uses [CategoryColumn]) is cut short with "…", and scrolls to show the rest while its row
 * has focus.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestApp::class, qualifiers = "+w960dp-h540dp-tvdpi")
class CategoryNameScrollUiTest {
    @get:Rule val compose = createUiComposeRule()

    @get:Rule(order = Navigators.ORDER)
    val navigators = Navigators()

    /** The name's own text layout, and the width of its row's text area. */
    private fun name(): TextLayoutResult {
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNode(hasText(LONG), useUnmergedTree = true).fetchSemanticsNode()
            .config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(layouts)
        return layouts.single()
    }

    private fun row(): SemanticsNodeInteraction = compose.onNode(hasText(LONG) and hasClickAction())

    @Test
    fun `a long name is cut short, and laid out in full to scroll while its row has focus`() {
        compose.setContent {
            MyPlexTheme {
                CategoryColumn(
                    entries = listOf(CategoryEntry("short", "News"), CategoryEntry("long", LONG)),
                    // Selected: coming into the column, focus goes to the selected entry.
                    selected = "long",
                    onFocus = {},
                    onSelect = {}
                )
            }
        }
        compose.waitAdvancingClock(0) { runCatching { name() }.isSuccess }
        assertTrue("cut short with … without focus", name().hasVisualOverflow)

        row().performSemanticsAction(SemanticsActions.RequestFocus)
        compose.waitAdvancingClock(0) { !name().hasVisualOverflow }
        // Laid out at its full width, wider than the column, for the scroll to show the rest.
        val rowWidth = row().fetchSemanticsNode().size.width
        assertFalse(name().hasVisualOverflow)
        assertTrue("${name().size.width} px wide, in a row of $rowWidth px", name().size.width > rowWidth)
    }

    private companion object {
        const val LONG = "Entertainment and Documentaries from Around the World"
    }
}
