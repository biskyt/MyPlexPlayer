package com.biskyt.myplexplayer.ui

import android.view.KeyEvent.ACTION_DOWN
import android.view.KeyEvent.ACTION_UP
import android.view.KeyEvent.KEYCODE_DPAD_DOWN
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import com.biskyt.myplexplayer.testutil.Navigators
import com.biskyt.myplexplayer.testutil.TestApp
import com.biskyt.myplexplayer.testutil.backOnPanel
import com.biskyt.myplexplayer.testutil.click
import com.biskyt.myplexplayer.testutil.createUiComposeRule
import com.biskyt.myplexplayer.testutil.focusedText
import com.biskyt.myplexplayer.testutil.idleFrames
import com.biskyt.myplexplayer.testutil.sendKey
import com.biskyt.myplexplayer.testutil.waitForFocus
import com.biskyt.myplexplayer.testutil.waitForText
import com.biskyt.myplexplayer.ui.common.ItemAction
import com.biskyt.myplexplayer.ui.common.ItemActionsPanel
import com.biskyt.myplexplayer.ui.common.OptionPlace
import com.biskyt.myplexplayer.ui.common.optionPlace
import com.biskyt.myplexplayer.ui.theme.MyPlexTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Which option of an options panel has the focus. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestApp::class, qualifiers = "+w960dp-h540dp-tvdpi")
class ItemActionsPanelUiTest {
    @get:Rule val compose = createUiComposeRule<ComponentActivity>()

    @get:Rule(order = Navigators.ORDER)
    val navigators = Navigators()

    @Test
    fun `an option read later, above the one used, does not take the focus from where the user is`() {
        val labels = mutableStateOf(listOf("Alpha", "Bravo", "Charlie", "Delta"))
        val navigator = navigators.new(Screen.Home)
        compose.setContent {
            MyPlexTheme {
                CompositionLocalProvider(LocalNavigator provides navigator) {
                    // Back from the panel Bravo opened.
                    val used = remember { mutableStateOf<OptionPlace?>(optionPlace(listOf("Alpha", "Bravo", "Charlie", "Delta"), 1)) }
                    ItemActionsPanel("Title", labels.value.map { ItemAction.Run(it) {} }, {}, used = used)
                }
            }
        }
        compose.waitForFocus("Bravo")
        repeat(2) {
            compose.sendKey(KEYCODE_DPAD_DOWN, ACTION_DOWN)
            compose.sendKey(KEYCODE_DPAD_DOWN, ACTION_UP)
        }
        compose.waitForFocus("Delta")
        // As TMDB's options come in above: Bravo, the option used, moves down a row, but must not take the focus again.
        labels.value = listOf("Extra") + labels.value
        compose.idleFrames()
        // Still the option the user is on, not Bravo, and not the option now in Delta's old row.
        assertEquals("Delta", compose.focusedText())
    }

    @Test
    fun `back from a panel, the option used takes the focus when it comes in late`() {
        // This app's own (its review of 1.41.2): the first row's first focus, given while Charlie is not read yet, must not
        // replace Charlie as the option to focus.
        val labels = mutableStateOf(listOf("Alpha", "Bravo"))
        val navigator = navigators.new(Screen.Home)
        compose.setContent {
            MyPlexTheme {
                CompositionLocalProvider(LocalNavigator provides navigator) {
                    val used = remember { mutableStateOf<OptionPlace?>(optionPlace(listOf("Alpha", "Bravo", "Charlie"), 2)) }
                    ItemActionsPanel("Title", labels.value.map { ItemAction.Run(it) {} }, {}, used = used)
                }
            }
        }
        compose.idleFrames()
        labels.value = listOf("Alpha", "Bravo", "Charlie")
        compose.waitForFocus("Charlie")
    }

    @Test
    fun `a panel whose options come in after it opened focuses the first of them`() {
        val labels = mutableStateOf(emptyList<String>())
        val navigator = navigators.new(Screen.Home)
        compose.setContent {
            MyPlexTheme {
                CompositionLocalProvider(LocalNavigator provides navigator) {
                    ItemActionsPanel("Title", labels.value.map { ItemAction.Run(it) {} }, {})
                }
            }
        }
        compose.idleFrames()
        labels.value = listOf("Alpha", "Bravo")
        compose.waitForFocus("Alpha")
    }

    @Test
    fun `back from a panel, the option used keeps the focus as options read later come in above it`() {
        val labels = mutableStateOf(listOf("Alpha", "Bravo", "Charlie"))
        val navigator = navigators.new(Screen.Home)
        compose.setContent {
            MyPlexTheme {
                CompositionLocalProvider(LocalNavigator provides navigator) {
                    val used = remember { mutableStateOf<OptionPlace?>(optionPlace(listOf("Alpha", "Bravo", "Charlie"), 1)) }
                    ItemActionsPanel("Title", labels.value.map { ItemAction.Run(it) {} }, {}, used = used)
                }
            }
        }
        compose.waitForFocus("Bravo")
        labels.value = listOf("Extra", "More") + labels.value
        compose.idleFrames()
        assertEquals("Bravo", compose.focusedText())
    }

    @Test
    fun `an option whose label changes keeps the focus`() {
        val labels = mutableStateOf(listOf("Open", "Add to favourites"))
        val navigator = navigators.new(Screen.Home)
        compose.setContent {
            MyPlexTheme {
                CompositionLocalProvider(LocalNavigator provides navigator) {
                    ItemActionsPanel("Title", labels.value.map { ItemAction.Run(it) {} }, {})
                }
            }
        }
        compose.waitForFocus("Open")
        compose.sendKey(KEYCODE_DPAD_DOWN, ACTION_DOWN)
        compose.sendKey(KEYCODE_DPAD_DOWN, ACTION_UP)
        compose.waitForFocus("Add to favourites")
        labels.value = listOf("Open", "Remove from favourites")
        compose.idleFrames()
        assertEquals("Remove from favourites", compose.focusedText())
        // Then an option read later just above it: the focus stays on it.
        labels.value = listOf("Open", "Trailer", "Remove from favourites")
        compose.idleFrames()
        assertEquals("Remove from favourites", compose.focusedText())
    }

    @Test
    fun `back on a question, its option has the focus again, though options came in above it meanwhile`() {
        val labels = mutableStateOf(listOf("Alpha"))
        val navigator = navigators.new(Screen.Home)
        compose.setContent {
            MyPlexTheme {
                CompositionLocalProvider(LocalNavigator provides navigator) {
                    // No `used` from the caller: the panel keeps the option used itself.
                    ItemActionsPanel(
                        "Title",
                        labels.value.map { ItemAction.Run(it) {} } + ItemAction.Confirmed("Delete it", "Sure?", "Yes") {},
                        {}
                    )
                }
            }
        }
        compose.waitForFocus("Alpha")
        // A tap gives no focus: the option used is noted all the same.
        compose.click("Delete it")
        compose.waitForText("Sure?")
        labels.value = listOf("Extra", "Alpha")
        compose.idleFrames()
        compose.backOnPanel()
        compose.waitForFocus("Delete it")
    }
}
