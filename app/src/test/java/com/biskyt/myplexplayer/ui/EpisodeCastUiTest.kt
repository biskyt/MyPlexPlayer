package com.biskyt.myplexplayer.ui

import androidx.compose.runtime.remember
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.performSemanticsAction
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import com.biskyt.myplexplayer.testutil.BoardTitles
import com.biskyt.myplexplayer.testutil.FakePlex
import com.biskyt.myplexplayer.testutil.FakeTmdb
import com.biskyt.myplexplayer.testutil.Navigators
import com.biskyt.myplexplayer.testutil.TestApp
import com.biskyt.myplexplayer.testutil.addSyncedHomePlex
import com.biskyt.myplexplayer.testutil.backOnPanel
import com.biskyt.myplexplayer.testutil.createUiComposeRule
import com.biskyt.myplexplayer.testutil.waitAdvancingClock
import com.biskyt.myplexplayer.testutil.waitForFocus
import com.biskyt.myplexplayer.testutil.waitForText
import com.biskyt.myplexplayer.ui.common.formatDate
import com.biskyt.myplexplayer.ui.player.PlaybackViewModel
import com.biskyt.myplexplayer.ui.player.VodPlayerViewModel
import com.biskyt.myplexplayer.ui.theme.MyPlexTheme
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
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
 * An episode's own Cast and crew and its description's scores and people (MyTVPlayer 1.35.0), on the series page and in
 * the player's More, which offers what the series page offers. The server's people and scores come first. TMDB's (with
 * a key) only fill what the server does not have (the owner, 2026-11-24).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestApp::class, qualifiers = "+w960dp-h540dp-tvdpi")
class EpisodeCastUiTest {
    @get:Rule val compose = createUiComposeRule()

    @get:Rule(order = Navigators.ORDER)
    val navigators = Navigators()

    private val plex = FakePlex()
    private val tmdb = FakeTmdb()
    private val app = ApplicationProvider.getApplicationContext<TestApp>()
    private var source = 0L

    @Before
    fun setUp() {
        plex.shows = listOf(BoardTitles.show())
        plex.episodes = BoardTitles.episodes()
        plex.tmdbIds = mapOf(BoardTitles.SHOW to FakeTmdb.SHOW)
        tmdb.use(app.container)
    }

    @After
    fun tearDown() {
        plex.close()
        tmdb.close()
    }

    /** Coastline's first episode playing, and its player's view model. */
    private fun playPilot(): VodPlayerViewModel {
        source = runBlocking { app.container.addSyncedHomePlex(plex) }
        val navigator = navigators.new(Screen.VodPlayer(source, PILOT.toLong(), seriesId = BoardTitles.SHOW.toLong(), fromStart = true))
        compose.setContent { MyPlexTheme { NavHost(remember { navigator }) { ScreenContent(it) } } }
        val vm = ViewModelProvider(navigator.current)[VodPlayerViewModel::class.java]
        compose.waitAdvancingClock { vm.engine?.player?.currentMediaItem != null }
        return vm
    }

    /** The series page's navigator, once [openPilotOptions] opened it. */
    private lateinit var seriesPage: Navigator

    private val screen get() = compose.runOnIdle { seriesPage.current.screen }

    /** The series page, with the options of its first episode ("Pilot") open. */
    private fun openPilotOptions() {
        source = runBlocking { app.container.addSyncedHomePlex(plex) }
        val navigator = navigators.new(Screen.SeriesDetails(source, BoardTitles.SHOW.toLong()))
        seriesPage = navigator
        compose.setContent { MyPlexTheme { NavHost(remember { navigator }) { ScreenContent(it) } } }
        compose.waitForText("Pilot", stepMs = 50)
        compose.onAllNodes(hasText("Pilot") and hasClickAction())[0].performSemanticsAction(SemanticsActions.OnLongClick)
        compose.waitForText("THE SHOW", stepMs = 50)
    }

    private fun shown(text: String) = compose.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty()

    private fun clickFirst(text: String) =
        compose.onAllNodes(hasText(text) and hasClickAction())[0].performSemanticsAction(SemanticsActions.OnClick)

    /** The options' Cast and crew, from the top down: the episode's, then the show's. */
    private fun castAndCrew(): List<SemanticsNodeInteraction> {
        val all = compose.onAllNodes(hasText("Cast and crew") and hasClickAction())
        return all.fetchSemanticsNodes().indices.sortedBy { all[it].fetchSemanticsNode().boundsInRoot.top }.map { all[it] }
    }

    /** Which of the options' two Cast and crew has the focus (0: the episode's, 1: the show's), or -1. */
    private fun focusedCastAndCrew(): Int {
        val focused = compose.onAllNodes(hasText("Cast and crew") and isFocused()).fetchSemanticsNodes().firstOrNull() ?: return -1
        return castAndCrew().indexOfFirst { it.fetchSemanticsNode().boundsInRoot.top == focused.boundsInRoot.top }
    }

    /** Two Cast and crew: the episode's (first, under This episode) and the show's. */
    private fun waitForTwoCasts() = compose.waitAdvancingClock(stepMs = 50) {
        compose.onAllNodes(hasText("Cast and crew") and hasClickAction()).fetchSemanticsNodes().size == 2
    }

    @Test
    fun `an episode's own Cast and crew lists who is in it and who made it, from TMDB when the server has none`() {
        openPilotOptions()
        waitForTwoCasts()
        clickFirst("Cast and crew")
        compose.waitForText(FakeTmdb.GUEST_STAR, stepMs = 50)
        assertTrue(shown("Vicky McClure")) // the show's cast who are in it
        assertTrue(shown(FakeTmdb.EPISODE_DIRECTOR))
        assertFalse(shown("Ines Moreau")) // the show's cast who are not
        // Back goes back to the options, on the Cast and crew used: the episode's, then the show's (by place, not name).
        compose.backOnPanel()
        compose.waitAdvancingClock(stepMs = 50) { focusedCastAndCrew() == 0 }
        castAndCrew()[1].performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForText("Ines Moreau", stepMs = 50)
        compose.backOnPanel()
        compose.waitAdvancingClock(stepMs = 50) { focusedCastAndCrew() == 1 }
    }

    @Test
    fun `an episode's description gives its TMDB score and who directed and wrote it, when the server has neither`() {
        openPilotOptions()
        compose.waitAdvancingClock(stepMs = 50) { shown("Episode description") }
        clickFirst("Episode description")
        compose.waitForText("Directed by ${FakeTmdb.EPISODE_DIRECTOR} · Written by ${FakeTmdb.EPISODE_WRITER}", stepMs = 50)
        compose.waitForText("81%", stepMs = 50)
    }

    @Test
    fun `the server's people and score win over TMDB's`() {
        plex.episodes = plex.episodes.map { if (it["ratingKey"].toString() == "\"$PILOT\"") withServerPeople(it) else it }
        openPilotOptions()
        waitForTwoCasts()
        clickFirst("Episode description")
        compose.waitForText("Directed by $SERVER_DIRECTOR · Written by ${FakeTmdb.EPISODE_WRITER}", stepMs = 50)
        compose.waitForText("7.4", stepMs = 50) // the server's IMDb score, not TMDB's 81%
        assertFalse(shown("81%"))
        assertFalse(shown(FakeTmdb.EPISODE_DIRECTOR))
    }

    @Test
    fun `in the episode's Cast and crew, the server's own cast wins and only they open a page`() {
        plex.episodes = plex.episodes.map { if (it["ratingKey"].toString() == "\"$PILOT\"") withServerPeople(it) else it }
        openPilotOptions()
        waitForTwoCasts()
        clickFirst("Cast and crew")
        compose.waitForText(SERVER_GUEST, stepMs = 50)
        // The server's guest star in place of TMDB's cast; TMDB's writer, as the server has none.
        assertFalse(shown(FakeTmdb.GUEST_STAR))
        assertFalse(shown("Vicky McClure"))
        assertTrue(shown(SERVER_DIRECTOR))
        assertTrue(shown(FakeTmdb.EPISODE_WRITER))
        // TMDB's writer has no server id: no page opens.
        clickFirst(FakeTmdb.EPISODE_WRITER)
        compose.waitAdvancingClock(stepMs = 0) { true }
        assertTrue(screen is Screen.SeriesDetails)
        // The server's guest star opens their page.
        clickFirst(SERVER_GUEST)
        compose.waitAdvancingClock(stepMs = 50) { (screen as? Screen.Person)?.name == SERVER_GUEST }
    }

    @Test
    fun `the player's More offers what the series page offers for the episode and its show, but no trailers`() {
        val vm = playPilot()
        compose.waitAdvancingClock(stepMs = 50) { vm.menuActions().count { it.label == "Cast and crew" } == 2 }
        val labels = vm.menuActions().map { it.label }
        // This episode: its description and its own cast and crew; the show: its options, its links, no trailers.
        assertEquals(listOf("This episode", "Episode description", "Cast and crew", "The show"), labels.take(4))
        assertTrue(labels.containsAll(listOf("Go to series", "About this show", "Links")))
        assertTrue(labels.none { "trailer" in it.lowercase() })
        assertEquals("Pilot", vm.menuTitle())
    }

    @Test
    fun `the episode's description and its own Cast and crew open over the video, as on the series page`() {
        val vm = playPilot()
        compose.waitAdvancingClock(stepMs = 50) { vm.menuActions().count { it.label == "Cast and crew" } == 2 }
        compose.runOnIdle { vm.sheet = PlaybackViewModel.Sheet.MORE }
        compose.waitForText("THIS EPISODE", stepMs = 50)
        compose.waitForText("PLAYBACK", stepMs = 50) // the player's own options, under their own heading
        clickFirst("Episode description")
        compose.waitForText("Directed by ${FakeTmdb.EPISODE_DIRECTOR} · Written by ${FakeTmdb.EPISODE_WRITER}", stepMs = 50)
        // Back goes back to More, on the option that opened the panel (the owner, 2026-11-26).
        compose.backOnPanel()
        compose.waitAdvancingClock { vm.sheet == PlaybackViewModel.Sheet.MORE && vm.panel == null }
        compose.waitForFocus("Episode description")
        clickFirst("Cast and crew")
        compose.waitForText(FakeTmdb.GUEST_STAR, stepMs = 50)
        assertTrue("still playing", vm.engine?.player?.playWhenReady == true)
        compose.backOnPanel()
        compose.waitAdvancingClock { vm.sheet == PlaybackViewModel.Sheet.MORE && vm.cast == null }
        // On the Cast and crew used: the episode's, then the show's (by place, not name).
        compose.waitAdvancingClock(stepMs = 50) { focusedCastAndCrew() == 0 }
        castAndCrew()[1].performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForText("Ines Moreau", stepMs = 50)
        compose.backOnPanel()
        compose.waitAdvancingClock(stepMs = 50) { focusedCastAndCrew() == 1 }
    }

    /** The pilot's description lines, from the server (its date) and from TMDB (the rest). */
    private fun pilotLines(): List<String> {
        plex.episodes = plex.episodes.map {
            if (it["ratingKey"].toString() == "\"$PILOT\"") JsonObject(it + ("originallyAvailableAt" to JsonPrimitive(AIRED))) else it
        }
        return listOf(
            "First shown ${formatDate(LocalDate.parse(AIRED))}",
            FakeTmdb.PILOT_WORDS,
            "Directed by ${FakeTmdb.EPISODE_DIRECTOR} · Written by ${FakeTmdb.EPISODE_WRITER}",
            "81%",
        )
    }

    @Test
    fun `the series page's episode description shows its date, words, score and people`() {
        val lines = pilotLines()
        openPilotOptions()
        waitForTwoCasts()
        clickFirst("Episode description")
        lines.forEach { compose.waitForText(it, stepMs = 50) }
    }

    @Test
    fun `the player's episode description shows the same lines as the series page's`() {
        val lines = pilotLines()
        val vm = playPilot()
        compose.waitAdvancingClock(stepMs = 50) { vm.menuActions().count { it.label == "Cast and crew" } == 2 }
        compose.runOnIdle { vm.sheet = PlaybackViewModel.Sheet.MORE }
        compose.waitForText("THIS EPISODE", stepMs = 50)
        clickFirst("Episode description")
        lines.forEach { compose.waitForText(it, stepMs = 50) }
    }

    /** [episode] with the server's own director, guest star and IMDb score (and no writer). */
    private fun withServerPeople(episode: JsonObject) = JsonObject(
        episode + mapOf(
            "Director" to JsonArray(listOf(person(SERVER_DIRECTOR, 701))),
            "Role" to JsonArray(listOf(person(SERVER_GUEST, 702))),
            "Rating" to JsonArray(
                listOf(
                    buildJsonObject {
                        put("image", "imdb://image.rating")
                        put("type", "audience")
                        put("value", 7.4)
                    }
                )
            ),
        )
    )

    private fun person(name: String, id: Long) = buildJsonObject {
        put("tag", name)
        put("id", id)
    }

    private companion object {
        /** Coastline's first episode on the server. */
        const val PILOT = "611"
        const val SERVER_DIRECTOR = "Rhona Hale"
        const val SERVER_GUEST = "Owen Pryce"

        /** When the server says Coastline's first episode was first shown. */
        const val AIRED = "2021-01-10"
    }
}
