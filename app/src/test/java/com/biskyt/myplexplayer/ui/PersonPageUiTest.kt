package com.biskyt.myplexplayer.ui

import androidx.compose.runtime.remember
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.lifecycle.ViewModelProvider
import androidx.media3.common.Player
import androidx.test.core.app.ApplicationProvider
import com.biskyt.myplexplayer.testutil.BoardTitles
import com.biskyt.myplexplayer.testutil.FakeCredit
import com.biskyt.myplexplayer.testutil.FakePerson
import com.biskyt.myplexplayer.testutil.FakePlex
import com.biskyt.myplexplayer.testutil.FakePlex.Companion.guidId
import com.biskyt.myplexplayer.testutil.Navigators
import com.biskyt.myplexplayer.testutil.OnAppBackground
import com.biskyt.myplexplayer.testutil.TestApp
import com.biskyt.myplexplayer.testutil.addSyncedHomePlex
import com.biskyt.myplexplayer.testutil.click
import com.biskyt.myplexplayer.testutil.createUiComposeRule
import com.biskyt.myplexplayer.testutil.focusedText
import com.biskyt.myplexplayer.testutil.idleFrames
import com.biskyt.myplexplayer.testutil.waitAdvancingClock
import com.biskyt.myplexplayer.testutil.waitForFilmPage
import com.biskyt.myplexplayer.testutil.waitForText
import com.biskyt.myplexplayer.ui.common.ItemAction
import com.biskyt.myplexplayer.ui.common.Tab
import com.biskyt.myplexplayer.ui.common.formatDate
import com.biskyt.myplexplayer.ui.common.initials
import com.biskyt.myplexplayer.ui.player.VodPlayerViewModel
import com.biskyt.myplexplayer.ui.theme.MyPlexTheme
import com.github.takahirom.roborazzi.captureRoboImage
import java.time.LocalDate
import kotlin.math.abs
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

/**
 * A cast or crew member's page (board "Cast member's page"): opened by OK on a cast photo (the film page's cast row) or
 * on a row of Cast and crew (the film's and the series') or of the player's Cast. Back from it shows the panel again on
 * the same person; from the player, the film plays on from the same place.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestApp::class, qualifiers = "+w960dp-h540dp-tvdpi")
class PersonPageUiTest {
    @get:Rule val compose = createUiComposeRule()

    @get:Rule(order = Navigators.ORDER)
    val navigators = Navigators()

    private val plex = FakePlex()
    private val app = ApplicationProvider.getApplicationContext<TestApp>()
    private var source = 0L
    private lateinit var navigator: Navigator

    @Before
    fun setUp() = runBlocking {
        plex.images = BoardTitles::art
        plex.movies = listOf(BoardTitles.film(), FakePlex.film("12", "Sintel", 2010))
        plex.shows = listOf(BoardTitles.show())
        plex.episodes = BoardTitles.episodes()
        plex.people = listOf(TOM, INES, KOFI)
        BoardTitles.watch(plex)
        source = app.container.addSyncedHomePlex(plex)
    }

    @After
    fun tearDown() = plex.close()

    private fun show(screen: Screen) {
        navigator = navigators.new(screen)
        compose.setContent { MyPlexTheme { OnAppBackground { NavHost(remember { navigator }) { ScreenContent(it) } } } }
    }

    private fun shown(text: String) = compose.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty()

    /** Back, as the app's own Back does: the screen under it again. */
    private fun back() {
        compose.runOnIdle { navigator.pop() }
        compose.idleFrames()
    }

    private val screen get() = compose.runOnIdle { navigator.current.screen }

    /** The film page, and OK on [name]'s photo in its cast row: their page, loaded. */
    private fun fromCastRow(name: String) {
        show(Screen.MovieDetails(source, BoardTitles.FILM.toLong()))
        compose.waitForFilmPage()
        compose.click(name)
        compose.waitAdvancingClock(stepMs = 50) { (screen as? Screen.Person)?.name == name }
        compose.waitForText("In your library", stepMs = 50)
    }

    @Test
    fun `OK on a cast photo opens their page, with each part`() {
        fromCastRow("Tom Hale")
        val opened = screen as Screen.Person
        assertEquals("under the tab it was opened from", Tab.MOVIES, opened.tab)
        assertEquals(BoardTitles.CAST to BoardTitles.tagKey(BoardTitles.CAST), opened.personId to opened.tagKey)
        val born = formatDate(LocalDate.of(1984, 5, 3))
        compose.waitForText("Actor, writer · Born $born in Leeds, England · Elias in The Long Harbour", stepMs = 50)
        assertTrue("the biography, cut short", shown(TOM.summary!!))
        assertTrue(shown("Full biography"))
        compose.waitAdvancingClock(stepMs = 50) { "Full biography" in compose.focusedText() }
        // In your library: the film and the show, with the part played in each.
        listOf("2021 · Elias", "TV show · Rose").forEach { assertTrue(it, shown(it)) }
        // Also appears in: the rest of what they acted in or wrote, newest first, each once.
        assertTrue(shown("Also appears in"))
        assertTrue(shown("TV show · Writer"))
        assertTrue(shown("2022 · Iona"))
        // All credits, further down: a show's title says so; producing too, but not appearing as themselves.
        compose.onNode(hasScrollToNodeAction() and hasAnyDescendant(hasText("Also appears in"))).performScrollToNode(hasText("Producer"))
        assertTrue(shown("All credits"))
        assertTrue(shown("Coastline (TV show)"))
        assertTrue(shown("Producer"))
        assertFalse("as themselves: not listed", shown("Late Night Talk") || shown("Late Night Talk (TV show)"))
    }

    @Test
    fun `Full biography opens the whole text in a side panel`() {
        fromCastRow("Tom Hale")
        compose.click("Full biography")
        // Its second paragraph on its own: only the panel has it.
        compose.waitForText(SECOND_PARAGRAPH, stepMs = 50)
        assertEquals("a window of its own", 2, compose.onAllNodes(isRoot()).fetchSemanticsNodes().size)
        compose.waitAdvancingClock(stepMs = 50) { "Tom Hale is an English actor" in compose.focusedText() }
    }

    @Test
    fun `someone with no biography has no paragraph and no Full biography`() {
        fromCastRow("Ines Moreau")
        compose.waitForText("TV show · Rose", stepMs = 50)
        assertFalse(shown("Full biography"))
        // With no button, the first card has the remote.
        compose.waitAdvancingClock(stepMs = 50) { "The Long Harbour" in compose.focusedText() }
    }

    @Test
    fun `OK on a title in the library opens its page`() {
        fromCastRow("Tom Hale")
        compose.onNode(hasText("Coastline") and hasClickAction()).performSemanticsAction(SemanticsActions.OnClick)
        compose.waitAdvancingClock(stepMs = 50) { screen == Screen.SeriesDetails(source, BoardTitles.SHOW.toLong()) }
        compose.waitForText("Continue S2 E4", stepMs = 50)
    }

    @Test
    fun `from the film's Cast and crew, and Back shows the panel again on the same person`() {
        show(Screen.MovieDetails(source, BoardTitles.FILM.toLong()))
        compose.waitForFilmPage()
        compose.click("More…")
        compose.waitForText("Cast and crew", stepMs = 50)
        compose.click("Cast and crew")
        // A writer: no part in the film, so no "… in The Long Harbour".
        compose.waitForText("Paul Okafor", stepMs = 50)
        compose.click("Paul Okafor")
        compose.waitAdvancingClock(stepMs = 50) { (screen as? Screen.Person)?.name == "Paul Okafor" }
        assertEquals(null, (screen as Screen.Person).role)
        back()
        compose.waitAdvancingClock(stepMs = 50) { "Paul Okafor" in compose.focusedText() }
        assertTrue("the panel again", shown("Cast and crew") && compose.onAllNodes(isRoot()).fetchSemanticsNodes().size == 2)
    }

    @Test
    fun `from the series' Cast and crew, and Back shows the panel again on the same person`() {
        show(Screen.SeriesDetails(source, BoardTitles.SHOW.toLong()))
        compose.waitForText("Continue S2 E4 · 21 min left", stepMs = 50)
        compose.onNode(hasText("The Lighthouse Keeper") and hasClickAction()).performSemanticsAction(SemanticsActions.RequestFocus)
        compose.waitForText("More…", stepMs = 50)
        compose.click("More…")
        compose.waitForText("Cast and crew", stepMs = 50)
        compose.click("Cast and crew")
        compose.waitForText("Ines Moreau", stepMs = 50)
        compose.click("Ines Moreau")
        compose.waitAdvancingClock(stepMs = 50) { (screen as? Screen.Person)?.name == "Ines Moreau" }
        assertEquals(Tab.SERIES to "Rose", (screen as Screen.Person).let { it.tab to it.role })
        compose.waitForText("Rose in Coastline", stepMs = 50)
        back()
        compose.waitAdvancingClock(stepMs = 50) { "Ines Moreau" in compose.focusedText() }
    }

    @Test
    fun `someone the server gave no id is not opened`() {
        show(Screen.MovieDetails(source, BoardTitles.FILM.toLong()))
        compose.waitForFilmPage()
        // Ana Ruiz, last in the cast row, as the cast lists her: with no id.
        compose.onNode(hasText("Director") and hasClickAction()).performSemanticsAction(SemanticsActions.OnClick)
        compose.idleFrames()
        assertTrue(screen is Screen.MovieDetails)
    }

    @Test
    fun `from the player's Cast, Back plays on from the same place with the panel on the same person`() {
        show(Screen.VodPlayer(source, BoardTitles.FILM.toLong(), fromStart = true))
        val vm = ViewModelProvider(navigator.current)[VodPlayerViewModel::class.java]
        compose.waitAdvancingClock { vm.engine?.player?.currentMediaItem != null }
        compose.runOnIdle { vm.engine!!.player.seekTo(PLACE_MS) }
        compose.runOnIdle { (vm.menuActions().first { it.label == "Cast" } as ItemAction.Run).run() }
        compose.waitForText("Kofi Mensah", stepMs = 50)
        compose.click("Kofi Mensah")
        compose.waitAdvancingClock(stepMs = 50) { (screen as? Screen.Person)?.name == "Kofi Mensah" }
        compose.waitForText("Sam in The Long Harbour", stepMs = 50)
        assertEquals("nothing streams under the page", Player.STATE_IDLE, compose.runOnIdle { vm.engine!!.player.playbackState })

        back()
        compose.waitAdvancingClock(stepMs = 50) { "Kofi Mensah" in compose.focusedText() }
        assertTrue("the cast again", compose.onAllNodes(isRoot()).fetchSemanticsNodes().size == 2)
        compose.runOnIdle {
            val player = vm.engine!!.player
            assertTrue("playing again", player.playWhenReady && player.playbackState != Player.STATE_IDLE)
            assertTrue("from ${player.currentPosition}", abs(player.currentPosition - PLACE_MS) < TOLERANCE_MS)
        }
    }

    @Test
    fun `the page as the board draws it`() {
        fromCastRow("Tom Hale")
        compose.waitAdvancingClock(stepMs = 50) {
            listOf("Tom Hale", "The Long Harbour", "Coastline", "Glass Coast").all {
                compose.onAllNodes(hasText(initials(it))).fetchSemanticsNodes().isEmpty()
            }
        }
        compose.idleFrames()
        compose.onAllNodes(isRoot())[0].captureRoboImage("screenshots/person-page.png")
    }

    private companion object {
        const val PLACE_MS = 600_000L
        const val TOLERANCE_MS = 2_000L

        const val SECOND_PARAGRAPH = "He lives on the coast with his family."

        /** Acts in the film and the show, writes and produces; on Discover with a biography in two paragraphs. */
        val TOM = FakePerson(
            BoardTitles.CAST,
            BoardTitles.tagKey(BoardTitles.CAST),
            "Tom Hale",
            summary = "Tom Hale is an English actor and writer. He trained at a drama school in Glasgow and worked in theatre " +
                "for ten years before his first leading role on television. He also wrote two episodes of the radio series " +
                "Still Waters.\n$SECOND_PARAGRAPH",
            bornAt = "1984-05-03",
            birthPlace = "Leeds, England",
            creditTypes = mapOf("writer" to 3, "actor" to 24, "appeared" to 40, "producer" to 1),
            credits = mapOf(
                "actor" to listOf(
                    FakeCredit(guidId(BoardTitles.FILM), "The Long Harbour", year = 2021, role = "Elias"),
                    FakeCredit(guidId(BoardTitles.SHOW), "Coastline", show = true, year = 2021, role = "Rose"),
                    FakeCredit("d-1", "Glass Coast", year = 2022, role = "Iona"),
                    FakeCredit("d-2", "The Quiet Year", year = 2021, role = "Beth"),
                    FakeCredit("d-3", "Signal Hill", show = true, year = 2020, role = "Kate"),
                    FakeCredit("d-4", "Paper Boats", year = 2018, role = "Anna"),
                    FakeCredit("d-5", "Low Country", year = 2015, role = "Jo"),
                    FakeCredit("d-6", "First Frost", year = 2013, role = "Nurse"),
                ),
                "writer" to listOf(FakeCredit("d-7", "Still Waters", show = true, year = 2023)),
                "producer" to listOf(FakeCredit("d-8", "North Light", year = 2019)),
                "appeared" to listOf(FakeCredit("d-9", "Late Night Talk", show = true, year = 2024, role = "Himself")),
            ),
            media = listOf(BoardTitles.FILM, BoardTitles.SHOW),
        )

        /** In the film and the show; Discover has no biography for her. */
        val INES = FakePerson(
            BoardTitles.CAST + 1,
            BoardTitles.tagKey(BoardTitles.CAST + 1),
            "Ines Moreau",
            creditTypes = mapOf("actor" to 5),
            credits = mapOf(
                "actor" to listOf(
                    FakeCredit(guidId(BoardTitles.FILM), "The Long Harbour", year = 2021, role = "Marta"),
                    FakeCredit(guidId(BoardTitles.SHOW), "Coastline", show = true, year = 2021, role = "Rose"),
                )
            ),
            media = listOf(BoardTitles.FILM, BoardTitles.SHOW),
        )

        val KOFI = FakePerson(
            BoardTitles.CAST + 2,
            BoardTitles.tagKey(BoardTitles.CAST + 2),
            "Kofi Mensah",
            media = listOf(BoardTitles.FILM)
        )
    }
}
