package com.biskyt.myplexplayer.ui

import androidx.compose.runtime.remember
import androidx.compose.ui.test.hasText
import androidx.test.core.app.ApplicationProvider
import androidx.tv.material3.Text
import com.biskyt.myplexplayer.testutil.BoardTitles
import com.biskyt.myplexplayer.testutil.FakePlex
import com.biskyt.myplexplayer.testutil.FakeTmdb
import com.biskyt.myplexplayer.testutil.Navigators
import com.biskyt.myplexplayer.testutil.OnAppBackground
import com.biskyt.myplexplayer.testutil.TestApp
import com.biskyt.myplexplayer.testutil.addSyncedHomePlex
import com.biskyt.myplexplayer.testutil.click
import com.biskyt.myplexplayer.testutil.createUiComposeRule
import com.biskyt.myplexplayer.testutil.settle
import com.biskyt.myplexplayer.testutil.waitAdvancingClock
import com.biskyt.myplexplayer.testutil.waitForFilmPage
import com.biskyt.myplexplayer.testutil.waitForText
import com.biskyt.myplexplayer.ui.theme.MyPlexTheme
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * TMDB's trailer, with a TMDB key, for a film or show whose server has no trailer of its own: "Play trailer" opens its
 * YouTube page, as the TMDB page's does. The server's own trailer still plays in the app. Other trailers lists TMDB's
 * trailers and teasers, each with its country (MyTVPlayer's TmdbFilmUiTest).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestApp::class, qualifiers = "+w960dp-h540dp-tvdpi")
class TmdbTrailerUiTest {
    @get:Rule val compose = createUiComposeRule()

    @get:Rule(order = Navigators.ORDER)
    val navigators = Navigators()

    private val plex = FakePlex()
    private val tmdb = FakeTmdb()
    private val app = ApplicationProvider.getApplicationContext<TestApp>()
    private var source = 0L
    private lateinit var navigator: Navigator

    @After
    fun tearDown() {
        plex.close()
        tmdb.close()
    }

    /** The board's film, with the server's trailer when [ownTrailer]; TMDB knows its id. */
    private fun setUp(ownTrailer: Boolean) = runBlocking {
        val film = BoardTitles.film()
        plex.movies = listOf(if (ownTrailer) FakePlex.withTrailer(film, OWN_TRAILER) else film)
        plex.extras = listOf(FakePlex.trailer(OWN_TRAILER))
        plex.tmdbIds = mapOf(BoardTitles.FILM to FakeTmdb.FILM)
        BoardTitles.watch(plex)
        source = app.container.addSyncedHomePlex(plex)
        tmdb.use(app.container)
    }

    /** The film's page, with its More… open, and a marker in place of the player (it needs video hardware). */
    private fun filmOptions() {
        navigator = navigators.new(Screen.MovieDetails(source, BoardTitles.FILM.toLong()))
        compose.setContent {
            MyPlexTheme {
                OnAppBackground {
                    NavHost(remember { navigator }) { if (it is Screen.VodPlayer) Text("PLAYER $it") else ScreenContent(it) }
                }
            }
        }
        compose.waitForFilmPage()
        compose.click("More…")
        compose.waitForText("About this film", stepMs = 50)
    }

    private fun shown(text: String) = compose.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun `a film with no trailer on the server offers TMDB's, which opens its YouTube page`() {
        setUp(ownTrailer = false)
        filmOptions()
        compose.waitForText("Play trailer", stepMs = 50)
        compose.click("Play trailer")
        compose.settle()
        assertEquals("https://www.youtube.com/watch?v=${FakeTmdb.FILM_TRAILER}", shadowOf(app).nextStartedActivity.dataString)
        assertTrue(navigator.current.screen is Screen.MovieDetails)
    }

    @Test
    fun `a film with its own trailer plays that one, not TMDB's`() {
        setUp(ownTrailer = true)
        filmOptions()
        // TMDB is asked for its trailers too, for Other trailers.
        compose.waitAdvancingClock(stepMs = 50) { "movie/${FakeTmdb.FILM}" in tmdb.requests }
        compose.click("Play trailer")
        compose.waitAdvancingClock(stepMs = 50) { navigator.current.screen is Screen.VodPlayer }
        assertEquals(Screen.VodPlayer(source, BoardTitles.FILM.toLong(), trailer = OWN_TRAILER.toLong()), navigator.current.screen)
        assertNull(shadowOf(app).nextStartedActivity)
    }

    @Test
    fun `More's Other trailers lists every trailer, with its country, and plays the one chosen`() {
        setUp(ownTrailer = false)
        filmOptions()
        compose.waitForText("Other trailers", stepMs = 50)
        compose.click("Other trailers")
        compose.waitForText("Teaser · GB", stepMs = 50)
        compose.click("Teaser · GB")
        compose.waitAdvancingClock { shadowOf(app).peekNextStartedActivity() != null }
        assertEquals("https://www.youtube.com/watch?v=${FakeTmdb.TEASER}", shadowOf(app).nextStartedActivity.dataString)
    }

    @Test
    fun `without a TMDB key, a film with no trailer on the server offers none`() {
        setUp(ownTrailer = false)
        tmdb.use(app.container, key = null)
        runBlocking { app.container.settings.update { it.copy(tmdbKey = "") } }
        filmOptions()
        compose.settle()
        assertFalse(shown("Play trailer"))
        assertEquals(emptyList<String>(), tmdb.requests)
    }

    private companion object {
        const val OWN_TRAILER = "900"
    }
}
