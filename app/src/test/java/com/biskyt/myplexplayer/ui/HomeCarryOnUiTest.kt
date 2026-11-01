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
import com.biskyt.myplexplayer.data.db.VOD_EPISODE
import com.biskyt.myplexplayer.data.db.VOD_MOVIE
import com.biskyt.myplexplayer.data.db.WatchProgressEntity
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Home's Carry on watching: back from playing a card, the remote is on it wherever it has moved to on the row. */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestApp::class, qualifiers = "+w960dp-h540dp-tvdpi")
class HomeCarryOnUiTest {
    @get:Rule val compose = createUiComposeRule()

    @get:Rule(order = Navigators.ORDER)
    val navigators = Navigators()

    private val plex = FakePlex()
    private val app = ApplicationProvider.getApplicationContext<TestApp>()
    private var source = 0L
    private val start = System.currentTimeMillis()

    /** A part-watched movie on the row, played [ago] ms before the test began ("Big Buck Bunny" is 11, the rest made up). */
    private fun movie(id: Long, title: String, ago: Long) = WatchProgressEntity(
        source,
        VOD_MOVIE,
        id,
        positionMs = 600_000,
        durationMs = 6_000_000,
        updatedAt = start - ago,
        title = title,
        imageUrl = null
    )

    @Before
    fun setUp() = runBlocking {
        source = app.container.addHomePlex(plex)
        // Twelve on the row, newest first: twice what fits across the screen. Big Buck Bunny is last.
        (0 until 12).forEach { i ->
            val p = if (i == 11) movie(11, "Big Buck Bunny", ago = i * 60_000L) else movie(900L + i, "Film $i", ago = i * 60_000L)
            app.container.vod.saveProgress(p.copy(pending = true))
        }
    }

    @After
    fun tearDown() = plex.close()

    private fun press(key: Key) {
        compose.onRoot().performKeyInput { pressKey(key) }
        compose.idleFrames()
    }

    @Test
    fun `a watched episode's card is the next episode, not started`() {
        runBlocking {
            plex.episodes = listOf(
                FakePlex.episode("21", "Test Show", "211", 1, 1, "Pilot"),
                FakePlex.episode("21", "Test Show", "212", 1, 2, "Second"),
            )
            val p = app.container.db.playlists().get(source)!!
            val next = app.container.vod.seriesDetails(p, 21).episodes.first { it.id == 212L }
            app.container.vod.saveProgress(
                WatchProgressEntity(
                    source, VOD_EPISODE, 211, positionMs = 600_000, durationMs = 600_000, updatedAt = start + 5,
                    title = "Test Show", imageUrl = null, seriesId = 21, season = 1, episode = 1, hasNext = true
                ),
                next = next
            )
        }
        val navigator = navigators.new(Screen.Home)
        compose.setContent { MyPlexTheme { NavHost(navigator) { ScreenContent(it) } } }
        // Its code and running time, and no "Up next after".
        compose.waitAdvancingClock(50) { compose.onAllNodes(hasText("S1E2 10m")).fetchSemanticsNodes().isNotEmpty() }
        assertEquals(0, compose.onAllNodes(hasText("Up next", substring = true)).fetchSemanticsNodes().size)
    }

    @Test
    fun `back from playing a card, the remote is on it at the start of the row`() {
        val navigator = navigators.new(Screen.Home)
        compose.setContent { MyPlexTheme { NavHost(navigator) { ScreenContent(it) } } }
        compose.waitAdvancingClock(50) { "Film 0" in compose.focusedText() }
        // Along the row to it.
        compose.waitAdvancingClock(50) { ("Big Buck Bunny" in compose.focusedText()).also { if (!it) press(Key.DirectionRight) } }
        press(Key.DirectionCenter)
        compose.waitAdvancingClock(50) { navigator.current.screen is Screen.VodPlayer }

        // Played on a little: it's the newest on the row now. Then Back.
        runBlocking {
            app.container.vod.saveProgress(movie(11, "Big Buck Bunny", ago = -60_000L).copy(positionMs = 900_000, pending = true))
        }
        compose.runOnUiThread { navigator.pop() }

        // At the start of the row, in view (the row had been scrolled along to it). The new order comes from the database
        // on another thread: until it comes, the remote is still on the card where it was, far along the row.
        compose.waitAdvancingClock(50) {
            "Big Buck Bunny" in compose.focusedText() && compose.onAllNodes(hasText("Film 0")).fetchSemanticsNodes().isNotEmpty()
        }
        compose.idleFrames()
        assertTrue(compose.focusedText(), "Big Buck Bunny" in compose.focusedText())
        assertEquals(1, compose.onAllNodes(hasText("Film 0")).fetchSemanticsNodes().size)
        press(Key.DirectionRight)
        assertTrue(compose.focusedText(), "Film 0" in compose.focusedText())
    }

    @Test
    fun `a card removed from the row leaves the remote on the card now in its place`() {
        val navigator = navigators.new(Screen.Home)
        compose.setContent { MyPlexTheme { NavHost(navigator) { ScreenContent(it) } } }
        compose.waitAdvancingClock(50) { "Film 0" in compose.focusedText() }
        compose.waitAdvancingClock(50) { ("Film 2" in compose.focusedText()).also { if (!it) press(Key.DirectionRight) } }
        compose.onNode(hasText("Film 2") and hasClickAction()).performSemanticsAction(SemanticsActions.OnLongClick)
        compose.waitForText("Remove from Carry on watching")
        compose.onNode(hasText("Remove from Carry on watching") and hasClickAction()).performSemanticsAction(SemanticsActions.OnClick)
        // The card now in its place, not the first card of the row.
        compose.waitAdvancingClock(50) { "Film 3" in compose.focusedText() }
        compose.idleFrames()
        assertTrue(compose.focusedText(), "Film 3" in compose.focusedText())
        assertEquals(0, compose.onAllNodes(hasText("Film 2")).fetchSemanticsNodes().size)
    }

    @Test
    fun `a card says its episode and the time left, or a film's time left`() {
        runBlocking {
            app.container.vod.saveProgress(
                movie(212, "Test Show", ago = -60_000L).copy(
                    kind = VOD_EPISODE,
                    positionMs = 120_000,
                    durationMs = 1_440_000,
                    seriesId = 21,
                    season = 1,
                    episode = 2,
                    episodeTitle = "Second",
                    pending = true
                )
            )
        }
        val navigator = navigators.new(Screen.Home)
        compose.setContent { MyPlexTheme { NavHost(navigator) { ScreenContent(it) } } }
        compose.waitAdvancingClock(50) { compose.onAllNodes(hasText("S1E2 22m")).fetchSemanticsNodes().isNotEmpty() }
        assertEquals(0, compose.onAllNodes(hasText("Second", substring = true)).fetchSemanticsNodes().size)
        // A film: 100 minutes, 10 in.
        assertTrue(compose.onAllNodes(hasText("1h 30m")).fetchSemanticsNodes().isNotEmpty())
    }
}
