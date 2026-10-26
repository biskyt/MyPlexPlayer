package com.biskyt.myplexplayer.ui

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
import com.biskyt.myplexplayer.testutil.FakePlex
import com.biskyt.myplexplayer.testutil.Navigators
import com.biskyt.myplexplayer.testutil.TestApp
import com.biskyt.myplexplayer.testutil.addHomePlex
import com.biskyt.myplexplayer.testutil.createUiComposeRule
import com.biskyt.myplexplayer.testutil.focusedText
import com.biskyt.myplexplayer.testutil.idleFrames
import com.biskyt.myplexplayer.testutil.waitAdvancingClock
import com.biskyt.myplexplayer.testutil.waitForText
import com.biskyt.myplexplayer.ui.theme.MyPlexTheme
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The remote's routes off the grid's top row (the owner's rule): up goes to the Category, Filter and Sort pills only
 * from a poster under them, otherwise to the menu; right off the row's end goes to the pills only from a short row.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestApp::class, qualifiers = "+w960dp-h540dp-tvdpi")
class GridRoutesUiTest {
    @get:Rule val compose = createUiComposeRule()

    @get:Rule(order = Navigators.ORDER)
    val navigators = Navigators()

    private val plex = FakePlex()
    private val app = ApplicationProvider.getApplicationContext<TestApp>()

    @After
    fun tearDown() = plex.close()

    /** Movies with [count] films, titled "Film 1" on, newest first. */
    private fun movies(count: Int) {
        plex.movies = (1..count).map { n -> FakePlex.film("${100 + n}", "Film $n", 2000 + n, addedAt = 1_700_000_000L - n) }
        runBlocking {
            val source = app.container.addHomePlex(plex)
            app.container.vod.sync(app.container.db.playlists().get(source)!!)
        }
        val navigator = navigators.new(Screen.Movies())
        compose.setContent { MyPlexTheme { NavHost(navigator) { ScreenContent(it) } } }
        compose.waitForText("Film 1", stepMs = 50)
    }

    private fun focus(title: String) {
        compose.onAllNodes(hasText(title) and hasClickAction())[0].performSemanticsAction(SemanticsActions.RequestFocus)
        compose.waitAdvancingClock { title in compose.focusedText() }
    }

    private fun press(key: Key) {
        compose.onRoot().performKeyInput { pressKey(key) }
        compose.idleFrames()
    }

    private fun assertFocused(text: String) = assertTrue(compose.focusedText(), text in compose.focusedText())

    @Test
    fun `up from a poster under the pills goes to them, from one clear of them to the menu`() {
        movies(9)
        focus("Film 7")
        press(Key.DirectionUp)
        assertFocused("Category: All")

        focus("Film 1")
        press(Key.DirectionUp)
        assertFocused("Movies")
    }

    @Test
    fun `right off the end of a full top row goes nowhere`() {
        movies(9)
        focus("Film 7")
        press(Key.DirectionRight)
        assertFocused("Film 7")
    }

    @Test
    fun `right off the end of a short top row goes to the pills`() {
        movies(2)
        focus("Film 2")
        press(Key.DirectionRight)
        assertFocused("Category: All")
        // And from the pills, down comes back to the grid.
        press(Key.DirectionDown)
        assertTrue(compose.focusedText(), "Film" in compose.focusedText())
    }
}
