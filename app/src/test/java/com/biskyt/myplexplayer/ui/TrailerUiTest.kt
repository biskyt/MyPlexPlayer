package com.biskyt.myplexplayer.ui

import androidx.compose.runtime.remember
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.core.app.ApplicationProvider
import androidx.tv.material3.Text
import com.biskyt.myplexplayer.testutil.BoardTitles
import com.biskyt.myplexplayer.testutil.FakePlex
import com.biskyt.myplexplayer.testutil.Navigators
import com.biskyt.myplexplayer.testutil.OnAppBackground
import com.biskyt.myplexplayer.testutil.TestApp
import com.biskyt.myplexplayer.testutil.addSyncedHomePlex
import com.biskyt.myplexplayer.testutil.click
import com.biskyt.myplexplayer.testutil.createUiComposeRule
import com.biskyt.myplexplayer.testutil.waitAdvancingClock
import com.biskyt.myplexplayer.testutil.waitForFilmPage
import com.biskyt.myplexplayer.testutil.waitForText
import com.biskyt.myplexplayer.ui.player.VodPlayerViewModel
import com.biskyt.myplexplayer.ui.theme.MyPlexTheme
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A film's or show's trailer (docs/plex-api.md row 30): offered in More… when it has one, and played as it is. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestApp::class, qualifiers = "+w960dp-h540dp-tvdpi")
class TrailerUiTest {
    @get:Rule val compose = createUiComposeRule()

    @get:Rule(order = Navigators.ORDER)
    val navigators = Navigators()

    private val plex = FakePlex()
    private val app = ApplicationProvider.getApplicationContext<TestApp>()
    private var source = 0L
    private lateinit var navigator: Navigator

    @Before
    fun setUp() = runBlocking {
        plex.movies = listOf(FakePlex.withTrailer(BoardTitles.film(), FILM_TRAILER), FakePlex.film("12", "Sintel", 2010))
        plex.shows = listOf(FakePlex.withTrailer(BoardTitles.show(), SHOW_TRAILER))
        plex.episodes = BoardTitles.episodes()
        plex.extras = listOf(FakePlex.trailer(FILM_TRAILER), FakePlex.trailer(SHOW_TRAILER))
        BoardTitles.watch(plex)
        source = app.container.addSyncedHomePlex(plex)
    }

    @After
    fun tearDown() = plex.close()

    /** [screen], with a marker in place of the player (it needs video hardware), to see what it was opened with. */
    private fun show(screen: Screen) {
        navigator = navigators.new(screen)
        compose.setContent {
            MyPlexTheme {
                OnAppBackground {
                    NavHost(remember { navigator }) { if (it is Screen.VodPlayer) Text("PLAYER $it") else ScreenContent(it) }
                }
            }
        }
    }

    private fun shown(text: String) = compose.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun `a film's More… plays its trailer`() {
        show(Screen.MovieDetails(source, BoardTitles.FILM.toLong()))
        compose.waitForFilmPage()
        compose.click("More…")
        compose.waitForText("Play trailer", stepMs = 50)
        compose.click("Play trailer")
        compose.waitAdvancingClock(stepMs = 50) { navigator.current.screen is Screen.VodPlayer }
        assertEquals(
            Screen.VodPlayer(source, BoardTitles.FILM.toLong(), trailer = FILM_TRAILER.toLong()),
            navigator.current.screen
        )
    }

    @Test
    fun `a film with no trailer offers none`() {
        show(Screen.MovieDetails(source, 12))
        compose.waitForText("Sintel", stepMs = 50)
        compose.click("More…")
        compose.waitForText("About this film", stepMs = 50)
        assertFalse(shown("Play trailer"))
    }

    @Test
    fun `a show's options play its trailer`() {
        show(Screen.SeriesDetails(source, BoardTitles.SHOW.toLong()))
        compose.waitForText("Continue S2 E4 · 21 min left", stepMs = 50)
        compose.onNode(hasText("The Lighthouse Keeper") and hasClickAction()).performSemanticsAction(SemanticsActions.RequestFocus)
        compose.waitForText("More…", stepMs = 50)
        compose.click("More…")
        compose.waitForText("THE SHOW", stepMs = 50)
        compose.click("Play trailer")
        compose.waitAdvancingClock(stepMs = 50) { navigator.current.screen is Screen.VodPlayer }
        val show = BoardTitles.SHOW.toLong()
        assertEquals(Screen.VodPlayer(source, show, show, trailer = SHOW_TRAILER.toLong()), navigator.current.screen)
    }

    @Test
    fun `the trailer plays its own file as it is, under the film's title, with no options`() {
        val screen = Screen.VodPlayer(source, BoardTitles.FILM.toLong(), trailer = FILM_TRAILER.toLong())
        val vm = VodPlayerViewModel(app.container, app, screen)
        try {
            compose.waitAdvancingClock { vm.engine?.player?.currentMediaItem != null }
            val uri = vm.engine!!.player.currentMediaItem!!.localConfiguration!!.uri.toString()
            assertTrue(uri, uri.endsWith(FakePlex.trailerPath(FILM_TRAILER)))
            assertEquals(BoardTitles.film().getValue("title").toString().trim('"'), vm.title)
            assertEquals("Trailer", vm.subtitle)
            assertTrue(vm.menuActions().isEmpty())
            assertEquals("an open player holds back the sync for new titles", 1, app.container.sync.playersOpen.value)
        } finally {
            vm.engine?.release()
        }
    }

    private companion object {
        const val FILM_TRAILER = "900"
        const val SHOW_TRAILER = "901"
    }
}
