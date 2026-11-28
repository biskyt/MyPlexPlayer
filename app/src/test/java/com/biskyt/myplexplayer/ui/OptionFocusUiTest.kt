package com.biskyt.myplexplayer.ui

import android.view.KeyEvent.KEYCODE_DPAD_DOWN
import android.view.KeyEvent.KEYCODE_DPAD_RIGHT
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.biskyt.myplexplayer.core.plex.TitleLink
import com.biskyt.myplexplayer.testutil.Navigators
import com.biskyt.myplexplayer.testutil.TestApp
import com.biskyt.myplexplayer.testutil.createUiComposeRule
import com.biskyt.myplexplayer.testutil.focusedText
import com.biskyt.myplexplayer.testutil.idleFrames
import com.biskyt.myplexplayer.testutil.press
import com.biskyt.myplexplayer.testutil.waitForFocus
import com.biskyt.myplexplayer.ui.common.BoardOption
import com.biskyt.myplexplayer.ui.common.ItemAction
import com.biskyt.myplexplayer.ui.common.ItemActionsPanel
import com.biskyt.myplexplayer.ui.common.OptionPlace
import com.biskyt.myplexplayer.ui.common.focusedAt
import com.biskyt.myplexplayer.ui.common.optionPlace
import com.biskyt.myplexplayer.ui.common.rememberOptionFocus
import com.biskyt.myplexplayer.ui.common.reveal
import com.biskyt.myplexplayer.ui.theme.MyPlexTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The focus of an options panel when its rows change: rows of links, and a list of rows not all on screen. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestApp::class, qualifiers = "+w960dp-h540dp-tvdpi")
class OptionFocusUiTest {
    @get:Rule val compose = createUiComposeRule<ComponentActivity>()

    @get:Rule(order = Navigators.ORDER)
    val navigators = Navigators()

    private val links = ItemAction.Links(
        "Links",
        listOf(TitleLink("Site A", "https://a.example"), TitleLink("Site B", "https://b.example"))
    )

    @Test
    fun `a link that is not the first keeps the focus as an option loads above it`() {
        val actions = mutableStateOf(listOf(ItemAction.Run("Alpha") {}, links))
        show(actions, null)
        compose.waitForFocus("Alpha")
        compose.press(KEYCODE_DPAD_DOWN)
        compose.waitForFocus("Site A")
        compose.press(KEYCODE_DPAD_RIGHT)
        compose.waitForFocus("Site B")
        actions.value = listOf(ItemAction.Run("Extra") {}) + actions.value
        compose.idleFrames()
        assertEquals("Site B", compose.focusedText())
    }

    @Test
    fun `the option used takes the focus when it loads late into its stand-in's row as another kind of row`() {
        val actions = mutableStateOf<List<ItemAction>>(listOf(ItemAction.Run("Alpha") {}, ItemAction.Run("Bravo") {}))
        // Back from a link of the row of links, which loads late, in the first row.
        show(actions, optionPlace(listOf("Links", "Alpha", "Bravo"), 0))
        compose.waitForFocus("Alpha")
        actions.value = listOf(links) + actions.value
        compose.waitForFocus("Site A")
    }

    @Test
    fun `in a list, the option used takes the focus when options load above it and move it off screen`() {
        val options = (0 until 30).map { "Option $it" }
        val labels = mutableStateOf(options)
        val used = optionPlace(options, 5)
        compose.setContent {
            MyPlexTheme {
                val list = remember { LazyListState(focusedAt(labels.value, used)) }
                val focus = rememberOptionFocus(labels.value, used) { i ->
                    list.reveal(i)
                }
                LazyColumn(Modifier.fillMaxSize(), list) {
                    itemsIndexed(labels.value) { i, label ->
                        focus.Row(labels.value, i) { requester, _ -> BoardOption(label, modifier = requester) {} }
                    }
                }
            }
        }
        compose.waitForFocus("Option 5")
        labels.value = (0 until 20).map { "Extra $it" } + options
        compose.waitForFocus("Option 5")
    }

    private fun show(actions: MutableState<List<ItemAction>>, used: OptionPlace?) {
        val navigator = navigators.new(Screen.Home)
        compose.setContent {
            MyPlexTheme {
                CompositionLocalProvider(LocalNavigator provides navigator) {
                    val place = remember { mutableStateOf(used) }
                    ItemActionsPanel("Title", actions.value, {}, used = place)
                }
            }
        }
    }
}
