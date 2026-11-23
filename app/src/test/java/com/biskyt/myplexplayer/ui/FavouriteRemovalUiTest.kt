package com.biskyt.myplexplayer.ui

import androidx.compose.runtime.remember
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performSemanticsAction
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import com.biskyt.myplexplayer.data.ChannelFilter
import com.biskyt.myplexplayer.data.VodShow
import com.biskyt.myplexplayer.data.VodView
import com.biskyt.myplexplayer.data.db.CAT_MOVIE
import com.biskyt.myplexplayer.testutil.FakePlex
import com.biskyt.myplexplayer.testutil.Navigators
import com.biskyt.myplexplayer.testutil.TestApp
import com.biskyt.myplexplayer.testutil.addSyncedHomePlex
import com.biskyt.myplexplayer.testutil.createUiComposeRule
import com.biskyt.myplexplayer.testutil.focusedText
import com.biskyt.myplexplayer.testutil.plexChannel
import com.biskyt.myplexplayer.testutil.waitAdvancingClock
import com.biskyt.myplexplayer.testutil.waitForText
import com.biskyt.myplexplayer.ui.live.LiveViewModel
import com.biskyt.myplexplayer.ui.theme.MyPlexTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Taking a favourite off in Favourites: focus goes to the one in its place, not out of the list (the owner,
 * 2026-11-22; MyTVPlayer 1.30.0), in Live TV and in Movies (Series is the same screen). Taken off in the TV guide,
 * nothing is kept for the list to move focus by later (MyTVPlayer 1.32.0).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestApp::class, qualifiers = "+w960dp-h540dp-tvdpi")
class FavouriteRemovalUiTest {
    @get:Rule val compose = createUiComposeRule()

    @get:Rule(order = Navigators.ORDER)
    val navigators = Navigators()

    private val plex = FakePlex()
    private val app = ApplicationProvider.getApplicationContext<TestApp>()
    private var pid = 0L
    private lateinit var navigator: Navigator

    @Before
    fun setUp() = runBlocking {
        plex.channels = listOf(FakePlex.Channel("1", "One"), FakePlex.Channel("2", "Arena"), FakePlex.Channel("3", "Kite"))
        plex.movies =
            listOf(FakePlex.film("51", "Dune", 2021), FakePlex.film("52", "Paddington 2", 2017), FakePlex.film("53", "Heat", 1995))
        pid = app.container.addSyncedHomePlex(plex)
        listOf("1", "2", "3").forEach { app.container.channels.setFavourite(pid, plexChannel(it), true) }
        listOf(51L, 52L, 53L).forEach { app.container.vod.setFavourite(pid, CAT_MOVIE, it, true) }
    }

    @After
    fun tearDown() = plex.close()

    private fun show(screen: Screen) {
        navigator = navigators.new(screen)
        compose.setContent { MyPlexTheme { NavHost(remember { navigator }) { ScreenContent(it) } } }
    }

    /** Opens [card]'s options with focus on it, and takes it off favourites. */
    private fun removeFavourite(card: String) {
        val node = compose.onAllNodes(hasText(card, substring = true) and hasClickAction())[0]
        node.performSemanticsAction(SemanticsActions.RequestFocus)
        compose.waitAdvancingClock(stepMs = 50) { compose.focusedText().contains(card) }
        node.performSemanticsAction(SemanticsActions.OnLongClick)
        compose.waitForText("Remove from favourites", stepMs = 50)
        compose.onAllNodes(hasText("Remove from favourites") and hasClickAction())[0].performSemanticsAction(SemanticsActions.OnClick)
    }

    /**
     * Of [titles], the one after [removed] as the list shows them (by place on screen), or the one before it if it is
     * last: where focus goes once it is gone.
     */
    private fun nextAfter(removed: String, titles: List<String>): String {
        val shown = titles.map { t ->
            t to compose.onAllNodes(hasText(t, substring = true) and hasClickAction())[0].fetchSemanticsNode().boundsInRoot
        }.sortedWith(compareBy({ it.second.top }, { it.second.left })).map { it.first }
        val i = shown.indexOf(removed)
        return shown.getOrNull(i + 1) ?: shown[i - 1]
    }

    @Test
    fun `in Live TV, focus goes to the next favourite channel`() {
        show(Screen.Live(ChannelFilter.Favourites))
        listOf("One", "Arena", "Kite").forEach { compose.waitForText(it, stepMs = 50) }
        val next = nextAfter("Arena", listOf("One", "Arena", "Kite"))
        removeFavourite("Arena")
        compose.waitAdvancingClock(stepMs = 50) { compose.focusedText().contains(next) }
    }

    @Test
    fun `in Movies, focus goes to the next favourite film`() {
        show(Screen.Movies(VodView(show = VodShow.FAVOURITES)))
        listOf("Dune", "Paddington 2", "Heat").forEach { compose.waitForText(it, stepMs = 50) }
        val next = nextAfter("Paddington 2", listOf("Dune", "Paddington 2", "Heat"))
        removeFavourite("Paddington 2")
        compose.waitAdvancingClock(stepMs = 50) { compose.focusedText().contains(next) }
    }

    @Test
    fun `in the TV guide, a removal is not kept for the list to act on later`() {
        show(Screen.Live(ChannelFilter.Favourites))
        compose.waitForText("Arena", stepMs = 50)
        val vm = ViewModelProvider(navigator.current)[LiveViewModel::class.java]
        compose.runOnUiThread { vm.showGuide(true, moveFocus = false) }
        val arena = plexChannel("2")
        val ch = runBlocking { app.container.channels.get(pid, arena)!! }
        compose.runOnUiThread { vm.setFavourite(ch, false) }
        compose.waitAdvancingClock { runBlocking { arena !in app.container.channels.observeFavouriteIds(pid).first() } }
        assertNull(vm.removedStreamId)
        assertNull(vm.removedAt)
    }
}
