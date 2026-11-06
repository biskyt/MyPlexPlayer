package com.biskyt.myplexplayer.ui

import android.view.KeyEvent
import androidx.compose.runtime.remember
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.core.app.ApplicationProvider
import com.biskyt.myplexplayer.testutil.BoardTitles
import com.biskyt.myplexplayer.testutil.FakePlex
import com.biskyt.myplexplayer.testutil.Navigators
import com.biskyt.myplexplayer.testutil.OnAppBackground
import com.biskyt.myplexplayer.testutil.TestApp
import com.biskyt.myplexplayer.testutil.addSyncedHomePlex
import com.biskyt.myplexplayer.testutil.click
import com.biskyt.myplexplayer.testutil.createUiComposeRule
import com.biskyt.myplexplayer.testutil.focusedText
import com.biskyt.myplexplayer.testutil.idleFrames
import com.biskyt.myplexplayer.testutil.sendKey
import com.biskyt.myplexplayer.testutil.waitAdvancingClock
import com.biskyt.myplexplayer.testutil.waitForFilmPage
import com.biskyt.myplexplayer.testutil.waitForText
import com.biskyt.myplexplayer.ui.theme.MyPlexTheme
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The film page's Plex parts (board "PlexFilm"): its More… panel and the panels it opens (About this film, Technical
 * info, Cast and crew), its cast row and its picture and sound labels; and an episode's More… on the series page.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestApp::class, qualifiers = "+w960dp-h540dp-tvdpi")
class DetailPanelsUiTest {
    @get:Rule val compose = createUiComposeRule()

    @get:Rule(order = Navigators.ORDER)
    val navigators = Navigators()

    private val plex = FakePlex()
    private val app = ApplicationProvider.getApplicationContext<TestApp>()
    private var source = 0L

    @Before
    fun setUp() = runBlocking {
        plex.movies = listOf(BoardTitles.film(), FakePlex.film("12", "Sintel", 2010))
        plex.shows = listOf(BoardTitles.show())
        plex.episodes = BoardTitles.episodes()
        BoardTitles.watch(plex)
        source = app.container.addSyncedHomePlex(plex)
    }

    @After
    fun tearDown() = plex.close()

    private fun show(screen: Screen) {
        val navigator = navigators.new(screen)
        compose.setContent { MyPlexTheme { OnAppBackground { NavHost(remember { navigator }) { ScreenContent(it) } } } }
    }

    /** The board's film page, once its details are in and Resume has the remote. */
    private fun filmPage() {
        show(Screen.MovieDetails(source, BoardTitles.FILM.toLong()))
        compose.waitForFilmPage()
    }

    private fun shown(text: String, substring: Boolean = false) =
        compose.onAllNodes(hasText(text, substring = substring)).fetchSemanticsNodes().isNotEmpty()

    private fun top(text: String) = compose.onNode(hasText(text) and hasClickAction()).getBoundsInRoot().top.value

    private val more =
        listOf("Mark as watched", "Add to favourites", "Cast and crew", "About this film", "Technical info", "Links", "Delete…")

    /** More…'s options are all there, in the board's order (the panel is open). */
    private fun moreListed() {
        compose.waitForText("Delete…", stepMs = 50)
        val tops = more.map { label -> compose.onNode(hasText(label)).getBoundsInRoot().top.value }
        assertEquals("in the board's order: $tops", tops.sorted(), tops)
        assertTrue("with the help line under Technical info", shown("File, video, audio and subtitles"))
        compose.waitAdvancingClock(stepMs = 50) { "Mark as watched" in compose.focusedText() }
    }

    @Test
    fun `More… holds the film's options, and the page no favourite button or hint`() {
        filmPage()
        // Both are in More… now.
        assertTrue(!shown("Favourite", substring = true) && !shown("hold OK", substring = true))
        compose.click("More…")
        moreListed()
    }

    @Test
    fun `holding OK on a button opens the same More… panel`() {
        filmPage()
        compose.onNode(hasText("Resume · 38 min left") and hasClickAction()).performSemanticsAction(SemanticsActions.OnLongClick)
        moreListed()
    }

    @Test
    fun `the remote's Menu button opens the same More… panel`() {
        filmPage()
        compose.sendKey(KeyEvent.KEYCODE_MENU, KeyEvent.ACTION_DOWN)
        compose.sendKey(KeyEvent.KEYCODE_MENU, KeyEvent.ACTION_UP)
        moreListed()
    }

    @Test
    fun `after Delete then Keep it, the first option has the remote again`() {
        // MyTVPlayer 1.13.2 gave first focus once a visit, so the list that came back had none (1.13.3 fixed it).
        filmPage()
        compose.click("More…")
        moreListed()
        compose.click("Delete…")
        compose.waitForText("Keep it", stepMs = 50)
        compose.click("Keep it")
        compose.waitAdvancingClock(stepMs = 50) { "Mark as watched" in compose.focusedText() }
    }

    @Test
    fun `More…'s favourite and watched change the film`() {
        filmPage()
        compose.click("More…")
        compose.waitForText("Add to favourites", stepMs = 50)
        compose.click("Add to favourites")
        compose.waitForText("Remove from favourites", stepMs = 50)
        compose.click("Mark as watched")
        compose.waitAdvancingClock(stepMs = 50) { BoardTitles.FILM in plex.watched }
    }

    @Test
    fun `About this film lists its facts`() {
        filmPage()
        compose.click("More…")
        compose.waitForText("About this film", stepMs = 50)
        compose.click("About this film")
        compose.waitForText("North Light Films", stepMs = 50)
        listOf("El puerto largo", "Spain, United Kingdom", "Island stories", "Mia Ortega, Sam Reid").forEach {
            assertTrue(it, shown(it))
        }
        listOf("Original title", "Released", "Added", "Studio", "Countries", "Collections", "Producers").forEach {
            assertTrue(it, shown(it))
        }
    }

    @Test
    fun `About this film leaves out a line with no data`() {
        show(Screen.MovieDetails(source, 12))
        compose.waitForText("Sintel", stepMs = 50)
        compose.click("More…")
        compose.waitForText("About this film", stepMs = 50)
        compose.click("About this film")
        compose.waitForText("Added", stepMs = 50)
        assertTrue(listOf("Original title", "Studio", "Countries", "Collections", "Producers").none { shown(it) })
    }

    @Test
    fun `Technical info lists the file, the picture, each soundtrack and each subtitle track`() {
        filmPage()
        compose.click("More…")
        compose.waitForText("Technical info", stepMs = 50)
        compose.click("Technical info")
        compose.waitForText("HEVC Main 10", stepMs = 50)
        listOf(
            "22.1 Mbit/s",
            // As the player's Playback info writes them; the sound by its label's names.
            "3840×2160 · 23.976 fps",
            "Dolby Vision 8.1, HDR10",
            "TrueHD Atmos 7.1 · 4.2 Mbit/s",
            "Dolby Digital 5.1 · 640 kbit/s",
            "SRT · forced",
            "PGS",
            "French"
        ).forEach { assertTrue(it, shown(it)) }
        assertTrue("the container and the size, each on its own line", shown("MKV") && shown("18.60 GB"))
        listOf("FILE", "VIDEO", "AUDIO", "SUBTITLES").forEach { assertTrue(it, shown(it)) }
    }

    @Test
    fun `About and Technical info read the details when the page has none, with Retry`() {
        plex.libraryUp = false
        show(Screen.MovieDetails(source, BoardTitles.FILM.toLong()))
        compose.waitForText("More…", stepMs = 50)
        compose.click("More…")
        compose.waitForText("Technical info", stepMs = 50)
        compose.click("Technical info")
        compose.waitForText("Try again", stepMs = 50)
        plex.libraryUp = true
        compose.click("Try again")
        compose.waitForText("HEVC Main 10", stepMs = 50)
    }

    @Test
    fun `the cast row shows each one's name and part, and OK on one opens Cast and crew`() {
        filmPage()
        listOf("Tom Hale", "Elias", "Ines Moreau", "Marta").forEach { assertTrue(it, shown(it)) }
        compose.click("Tom Hale")
        compose.waitForText("Cast and crew", stepMs = 50)
        // Who directed and wrote it, after the cast.
        compose.waitForText("Paul Okafor", stepMs = 50)
        assertTrue(shown("Writer"))
    }

    @Test
    fun `the picture and sound labels are between the people and the buttons`() {
        filmPage()
        compose.idleFrames()
        val people = compose.onNode(hasText("Directed by Ana Ruiz · Written by Ana Ruiz, Paul Okafor")).getBoundsInRoot()
        val labels = listOf("4K", "Dolby Vision", "Dolby Atmos 7.1").map { compose.onNode(hasText(it)).getBoundsInRoot() }
        val resume = compose.onNode(hasText("Resume · 38 min left") and hasClickAction()).getBoundsInRoot()
        labels.forEach {
            assertTrue("labels under the people line", it.top > people.bottom)
            assertTrue("labels above the buttons", it.bottom < resume.top)
        }
        assertEquals("one line", labels[0].top.value, labels[2].top.value, 0.5f)
    }

    @Test
    fun `an episode's More… opens its options, with About this show`() {
        show(Screen.SeriesDetails(source, BoardTitles.SHOW.toLong()))
        compose.waitForText("Continue S2 E4 · 21 min left", stepMs = 50)
        compose.onNode(hasText("The Lighthouse Keeper") and hasClickAction()).performSemanticsAction(SemanticsActions.RequestFocus)
        compose.waitForText("More…", stepMs = 50)
        // The open episode's picture and sound labels.
        listOf("1080p", "HDR10", "Dolby Digital+ 5.1").forEach {
            assertEquals(it, 1, compose.onAllNodes(hasText(it)).fetchSemanticsNodes().size)
        }
        compose.click("More…")
        compose.waitForText("THIS EPISODE", stepMs = 50)
        compose.waitForText("THE SHOW", stepMs = 50)
        assertTrue("This episode first", top("Resume from 33:00") < top("About this show"))
        listOf("Restart", "Episode description", "Mark as watched", "Cast and crew", "Add to favourites").forEach {
            assertTrue(it, shown(it))
        }
        compose.click("About this show")
        compose.waitForText("Added", stepMs = 50)
    }
}
