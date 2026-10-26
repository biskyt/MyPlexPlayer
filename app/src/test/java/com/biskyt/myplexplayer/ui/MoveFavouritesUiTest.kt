package com.biskyt.myplexplayer.ui

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
import com.biskyt.myplexplayer.R
import com.biskyt.myplexplayer.data.ChannelFilter
import com.biskyt.myplexplayer.data.VodFilter
import com.biskyt.myplexplayer.data.db.CAT_LIVE
import com.biskyt.myplexplayer.data.db.CAT_MOVIE
import com.biskyt.myplexplayer.data.plexChannelId
import com.biskyt.myplexplayer.testutil.FakePlex
import com.biskyt.myplexplayer.testutil.Navigators
import com.biskyt.myplexplayer.testutil.TestApp
import com.biskyt.myplexplayer.testutil.addSyncedHomePlex
import com.biskyt.myplexplayer.testutil.createUiComposeRule
import com.biskyt.myplexplayer.testutil.scrollToHomeRow
import com.biskyt.myplexplayer.testutil.waitAdvancingClock
import com.biskyt.myplexplayer.testutil.waitForText
import com.biskyt.myplexplayer.ui.theme.MyPlexTheme
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

    private val plex = FakePlex()
    private val app = ApplicationProvider.getApplicationContext<TestApp>()
    private var playlistId = 0L

    @Before
    fun setUp() = runBlocking {
        plex.channels = listOf(FakePlex.Channel("1", "One"), FakePlex.Channel("2", "Two"), FakePlex.Channel("3", "Three"))
        playlistId = app.container.addSyncedHomePlex(plex)
        // One, Two, Three; Big Buck Bunny then Sintel.
        listOf(ONE, TWO, THREE).forEach { app.container.channels.setFavourite(playlistId, it, true) }
        listOf(11L, 12L).forEach { app.container.vod.setFavourite(playlistId, CAT_MOVIE, it, true) }
    }

    @After
    fun tearDown() = plex.close()

    private var backDispatcher: androidx.activity.OnBackPressedDispatcher? = null

    private fun show(start: Screen) {
        val navigator = navigators.new(start)
        compose.setContent {
            backDispatcher = androidx.activity.compose.LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
            MyPlexTheme { NavHost(remember { navigator }) { ScreenContent(it) } }
        }
    }

    /** The remote's Back, as the activity gets it. */
    private fun back() {
        compose.runOnUiThread { backDispatcher!!.onBackPressed() }
        compose.waitForIdle()
    }

    private fun waitFor(text: String) = compose.waitForText(text, stepMs = 50)

    /** Home with its channels' row in view (Live TV comes last, below the films). */
    private fun showHomeChannels() {
        show(Screen.Home)
        compose.scrollToHomeRow(R.string.home_on_now, loaded = R.string.home_favourite_movies)
    }

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
        showHomeChannels()
        waitFor("Three")
        move("One")
        press(Key.DirectionRight)
        waitForOrder(CAT_LIVE, listOf(TWO, ONE, THREE))
        press(Key.DirectionRight)
        waitForOrder(CAT_LIVE, listOf(TWO, THREE, ONE))
        press(Key.DirectionCenter)
        compose.waitAdvancingClock(stepMs = 50) {
            compose.onAllNodes(hasText("Back to cancel", substring = true)).fetchSemanticsNodes().isEmpty()
        }
        // Focus stays on the channel just placed.
        compose.waitAdvancingClock(stepMs = 50) { focused("One") }
        // Moving is over: the arrows move focus again, not the channel.
        press(Key.DirectionLeft)
        compose.waitAdvancingClock(stepMs = 50) { true }
        waitForOrder(CAT_LIVE, listOf(TWO, THREE, ONE))
    }

    @Test
    fun `on Home, Back puts the row back as it was`() {
        showHomeChannels()
        waitFor("Three")
        move("Three")
        press(Key.DirectionLeft)
        waitForOrder(CAT_LIVE, listOf(ONE, THREE, TWO))
        back()
        waitForOrder(CAT_LIVE, listOf(ONE, TWO, THREE))
    }

    @Test
    fun `in Live TV's Favourites, a channel moves up and down`() {
        show(Screen.Live(ChannelFilter.Favourites))
        waitFor("Three")
        move("One")
        press(Key.DirectionDown)
        waitForOrder(CAT_LIVE, listOf(TWO, ONE, THREE))
        press(Key.DirectionCenter)
        waitForOrder(CAT_LIVE, listOf(TWO, ONE, THREE))
    }

    @Test
    fun `outside Favourites, channels can't be moved`() {
        show(Screen.Live(ChannelFilter.All))
        waitFor("Three")
        compose.onAllNodes(hasText("Three", substring = true) and hasClickAction())[0]
            .performSemanticsAction(SemanticsActions.OnLongClick)
        waitFor("Hide channel")
        assertTrue(compose.onAllNodes(hasText("Move", substring = true)).fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun `in Movies' Favourites, a poster moves along the grid`() {
        show(Screen.Movies(VodFilter.Favourites))
        waitFor("Sintel")
        move("Sintel")
        press(Key.DirectionLeft)
        waitForOrder(CAT_MOVIE, listOf(12L, 11L))
        press(Key.DirectionCenter)
        waitForOrder(CAT_MOVIE, listOf(12L, 11L))
    }

    @Test
    fun `in Movies' Favourites, taking one off closes its options and focus moves on`() {
        show(Screen.Movies(VodFilter.Favourites))
        waitFor("Sintel")
        compose.onAllNodes(hasText("Sintel", substring = true) and hasClickAction())[0]
            .performSemanticsAction(SemanticsActions.OnLongClick)
        waitFor("Remove from favourites")
        compose.onNode(hasText("Remove from favourites")).performSemanticsAction(SemanticsActions.OnClick)
        compose.waitAdvancingClock(stepMs = 50) { compose.onAllNodes(hasText("Sintel")).fetchSemanticsNodes().isEmpty() }
        compose.waitAdvancingClock(stepMs = 50) { focused("Big Buck Bunny") }
    }

    private companion object {
        val ONE = plexChannelId("ch-1")
        val TWO = plexChannelId("ch-2")
        val THREE = plexChannelId("ch-3")
    }
}
