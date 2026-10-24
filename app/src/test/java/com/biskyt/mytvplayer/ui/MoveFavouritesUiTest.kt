package com.biskyt.mytvplayer.ui

import androidx.compose.runtime.remember
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.test.core.app.ApplicationProvider
import com.biskyt.mytvplayer.data.ChannelFilter
import com.biskyt.mytvplayer.data.VodFilter
import com.biskyt.mytvplayer.data.db.CAT_LIVE
import com.biskyt.mytvplayer.data.db.CAT_MOVIE
import com.biskyt.mytvplayer.testutil.FakeProvider
import com.biskyt.mytvplayer.testutil.Navigators
import com.biskyt.mytvplayer.testutil.TestApp
import com.biskyt.mytvplayer.testutil.addHomeTv
import com.biskyt.mytvplayer.testutil.createUiComposeRule
import com.biskyt.mytvplayer.testutil.waitAdvancingClock
import com.biskyt.mytvplayer.testutil.waitForText
import com.biskyt.mytvplayer.ui.theme.MyTvTheme
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Moving a favourite: from its options, with the arrows, OK keeping the new order and Back the old one. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestApp::class, qualifiers = "+w960dp-h540dp-tvdpi")
class MoveFavouritesUiTest {
    @get:Rule val compose = createUiComposeRule()

    @get:Rule(order = Navigators.ORDER)
    val navigators = Navigators()

    private val provider = FakeProvider()
    private val app = ApplicationProvider.getApplicationContext<TestApp>()
    private var playlistId = 0L

    @Before
    fun setUp() = runBlocking {
        playlistId = app.container.addHomeTv(provider, vod = true)
        // Channel One, Arena, Kite Kidz; Dune then Paddington 2.
        listOf(101L, 102L, 103L).forEach { app.container.channels.setFavourite(playlistId, it, true) }
        listOf(501L, 502L).forEach { app.container.vod.setFavourite(playlistId, CAT_MOVIE, it, true) }
    }

    @After
    fun tearDown() = provider.close()

    private var backDispatcher: androidx.activity.OnBackPressedDispatcher? = null

    private fun show(start: Screen) {
        val navigator = navigators.new(start)
        compose.setContent {
            backDispatcher = androidx.activity.compose.LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
            MyTvTheme { NavHost(remember { navigator }) { ScreenContent(it) } }
        }
    }

    /** The remote's Back, as the activity gets it. */
    private fun back() {
        compose.runOnUiThread { backDispatcher!!.onBackPressed() }
        compose.waitForIdle()
    }

    private fun waitFor(text: String) = compose.waitForText(text, stepMs = 50)

    private fun order(type: String) = runBlocking { app.container.favourites.order(playlistId, type) }

    private fun waitForOrder(type: String, expected: List<Long>) = compose.waitAdvancingClock(stepMs = 50) { order(type) == expected }

    /** Holds OK on the card showing [text] and chooses Move. */
    private fun move(text: String) {
        compose.onAllNodes(hasText(text, substring = true) and hasClickAction())[0].performSemanticsAction(SemanticsActions.OnLongClick)
        waitFor("Move")
        compose.onNode(hasText("Move") and hasClickAction()).performSemanticsAction(SemanticsActions.OnClick)
        waitFor("Back to cancel")
    }

    private fun focused(text: String) =
        compose.onAllNodes(hasClickAction() and isFocused() and hasAnyDescendant(hasText(text, substring = true)))
            .fetchSemanticsNodes().isNotEmpty() ||
            compose.onAllNodes(hasText(text, substring = true) and isFocused()).fetchSemanticsNodes().isNotEmpty()

    @OptIn(ExperimentalTestApi::class)
    private fun press(key: Key) {
        compose.onAllNodes(isRoot())[0].performKeyInput { pressKey(key) }
        compose.waitForIdle()
    }

    @Test
    fun `on Home, a favourite channel moves along its row and stays there with OK`() {
        show(Screen.Home)
        waitFor("UK: KITE KIDZ")
        move("UK: CHANNEL ONE HD")
        press(Key.DirectionRight)
        waitForOrder(CAT_LIVE, listOf(102L, 101L, 103L))
        press(Key.DirectionRight)
        waitForOrder(CAT_LIVE, listOf(102L, 103L, 101L))
        press(Key.DirectionCenter)
        compose.waitAdvancingClock(stepMs = 50) {
            compose.onAllNodes(hasText("Back to cancel", substring = true)).fetchSemanticsNodes().isEmpty()
        }
        // Focus stays on the channel just placed.
        compose.waitAdvancingClock(stepMs = 50) { focused("UK: CHANNEL ONE HD") }
        // Moving is over: the arrows move focus again, not the channel.
        press(Key.DirectionLeft)
        compose.waitAdvancingClock(stepMs = 50) { true }
        waitForOrder(CAT_LIVE, listOf(102L, 103L, 101L))
    }

    @Test
    fun `on Home, Back puts the row back as it was`() {
        show(Screen.Home)
        waitFor("UK: KITE KIDZ")
        move("UK: KITE KIDZ")
        press(Key.DirectionLeft)
        waitForOrder(CAT_LIVE, listOf(101L, 103L, 102L))
        back()
        waitForOrder(CAT_LIVE, listOf(101L, 102L, 103L))
    }

    @Test
    fun `in Live TV's Favourites, a channel moves up and down`() {
        show(Screen.Live(ChannelFilter.Favourites))
        waitFor("UK: KITE KIDZ")
        move("UK: CHANNEL ONE HD")
        press(Key.DirectionDown)
        waitForOrder(CAT_LIVE, listOf(102L, 101L, 103L))
        press(Key.DirectionCenter)
        waitForOrder(CAT_LIVE, listOf(102L, 101L, 103L))
    }

    @Test
    fun `outside Favourites, channels can't be moved`() {
        show(Screen.Live(ChannelFilter.All))
        waitFor("UK: KITE KIDZ")
        compose.onAllNodes(hasText("UK: KITE KIDZ", substring = true) and hasClickAction())[0]
            .performSemanticsAction(SemanticsActions.OnLongClick)
        waitFor("Hide channel")
        assertTrue(compose.onAllNodes(hasText("Move", substring = true)).fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun `in Movies' Favourites, a poster moves along the grid`() {
        show(Screen.Movies(VodFilter.Favourites))
        waitFor("Paddington 2")
        move("Paddington 2")
        press(Key.DirectionLeft)
        waitForOrder(CAT_MOVIE, listOf(502L, 501L))
        press(Key.DirectionCenter)
        waitForOrder(CAT_MOVIE, listOf(502L, 501L))
    }

    @Test
    fun `in Movies' Favourites, taking one off closes its options and focus moves on`() {
        show(Screen.Movies(VodFilter.Favourites))
        waitFor("Paddington 2")
        compose.onAllNodes(hasText("Paddington 2", substring = true) and hasClickAction())[0]
            .performSemanticsAction(SemanticsActions.OnLongClick)
        waitFor("Remove from favourites")
        compose.onNode(hasText("Remove from favourites")).performSemanticsAction(SemanticsActions.OnClick)
        compose.waitAdvancingClock(stepMs = 50) { compose.onAllNodes(hasText("Paddington 2")).fetchSemanticsNodes().isEmpty() }
        compose.waitAdvancingClock(stepMs = 50) { focused("Dune") }
    }
}
