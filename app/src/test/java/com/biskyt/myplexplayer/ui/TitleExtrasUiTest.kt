package com.biskyt.myplexplayer.ui

import androidx.compose.runtime.remember
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.core.app.ApplicationProvider
import com.biskyt.myplexplayer.R
import com.biskyt.myplexplayer.data.db.CAT_MOVIE
import com.biskyt.myplexplayer.data.db.VOD_EPISODE
import com.biskyt.myplexplayer.data.db.WatchProgressEntity
import com.biskyt.myplexplayer.testutil.BoardTitles
import com.biskyt.myplexplayer.testutil.FakePlex
import com.biskyt.myplexplayer.testutil.Navigators
import com.biskyt.myplexplayer.testutil.OnAppBackground
import com.biskyt.myplexplayer.testutil.TestApp
import com.biskyt.myplexplayer.testutil.addSyncedHomePlex
import com.biskyt.myplexplayer.testutil.createUiComposeRule
import com.biskyt.myplexplayer.testutil.focusedText
import com.biskyt.myplexplayer.testutil.pressAndRead
import com.biskyt.myplexplayer.testutil.scrollToHomeRow
import com.biskyt.myplexplayer.testutil.waitAdvancingClock
import com.biskyt.myplexplayer.testutil.waitForText
import com.biskyt.myplexplayer.ui.theme.MyPlexTheme
import kotlinx.coroutines.flow.first
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
 * A film's or show's More… away from its page: Home's Continue watching and favourite cards offer what its page's
 * More… offers, from the server's own details (MyTVPlayer 1.30.0's TitleExtras; here the server first).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestApp::class, qualifiers = "+w960dp-h540dp-tvdpi")
class TitleExtrasUiTest {
    @get:Rule val compose = createUiComposeRule()

    @get:Rule(order = Navigators.ORDER)
    val navigators = Navigators()

    private val plex = FakePlex()
    private val app = ApplicationProvider.getApplicationContext<TestApp>()
    private var source = 0L

    @Before
    fun setUp() = runBlocking {
        plex.movies = listOf(BoardTitles.film())
        plex.shows = listOf(BoardTitles.show())
        plex.episodes = BoardTitles.episodes()
        BoardTitles.watch(plex)
        plex.continueWatching = listOf(BoardTitles.FILM)
        val pid = app.container.addSyncedHomePlex(plex)
        source = pid
        app.container.vod.setFavourite(pid, CAT_MOVIE, BoardTitles.FILM.toLong(), true)
    }

    @After
    fun tearDown() = plex.close()

    private companion object {
        const val MIN = 60_000L
    }

    private fun showHome() {
        val navigator = navigators.new(Screen.Home)
        compose.setContent { MyPlexTheme { OnAppBackground { NavHost(remember { navigator }) { ScreenContent(it) } } } }
    }

    private fun waitFor(text: String) = compose.waitForText(text, stepMs = 50)

    /** Holds OK on the [first] (Continue watching, at the top) or last card showing [title]. */
    private fun options(title: String, first: Boolean) {
        val cards = compose.onAllNodes(hasText(title, substring = true) and hasClickAction())
        val card = if (first) cards.onFirst() else cards.onLast()
        card.performSemanticsAction(SemanticsActions.RequestFocus)
        card.performSemanticsAction(SemanticsActions.OnLongClick)
    }

    private fun choose(option: String) =
        compose.onAllNodes(hasText(option) and hasClickAction()).onLast().performSemanticsAction(SemanticsActions.OnClick)

    @Test
    fun `a film's Continue watching card offers its page's options, with no About`() {
        showHome()
        waitFor("The Long Harbour")
        options("The Long Harbour", first = true)
        waitFor("Remove from Continue watching")
        // Once the server answers, as on the film's page.
        waitFor("Cast and crew")
        waitFor("Technical info")
        assertFalse(compose.onAllNodes(hasText("About this film")).fetchSemanticsNodes().isNotEmpty())
    }

    @Test
    fun `a watched episode's card describes the next episode, the one Play plays`() {
        // Series 2's episode 3 (Low Water) watched to its end, with no card of its own for episode 4: "Up next after".
        runBlocking {
            val now = System.currentTimeMillis()
            app.container.db.vod().saveProgress(
                WatchProgressEntity(
                    source, VOD_EPISODE, 623, positionMs = 57 * MIN, durationMs = 57 * MIN, updatedAt = now,
                    title = "Coastline", imageUrl = null, seriesId = BoardTitles.SHOW.toLong(), season = 2, episode = 3,
                    hasNext = true
                )
            )
        }
        showHome()
        waitFor("Continue watching")
        options("Coastline", first = true)
        waitFor("Play the next episode")
        waitFor("Episode description")
        choose("Episode description")
        waitFor("The Lighthouse Keeper")
        waitFor("A body on the rocks")
    }

    @Test
    fun `a film's card offers what its page's More offers, after Resume, Remove and About (MyTVPlayer 1_39_0)`() {
        showHome()
        waitFor("The Long Harbour")
        options("The Long Harbour", first = true)
        waitFor("Technical info")
        val order = listOf("Resume", "Remove from Continue watching", "About this movie", "Mark as watched", "Remove from favourites")
        val tops = order.map { compose.onAllNodes(hasText(it) and hasClickAction()).onLast().fetchSemanticsNode().boundsInRoot.top }
        assertEquals(tops.sorted(), tops)
        choose("Remove from favourites")
        compose.waitAdvancingClock(stepMs = 50) {
            runBlocking { app.container.db.vod().observeFavouriteIds(source, CAT_MOVIE).first() }.isEmpty()
        }
    }

    @Test
    fun `an episode's card offers what the series page offers for it, and Watched moves the card on`() {
        // Series 2's episode 4 (The Lighthouse Keeper), watched part way.
        runBlocking {
            app.container.db.vod().saveProgress(
                WatchProgressEntity(
                    source, VOD_EPISODE, 624, positionMs = 33 * MIN, durationMs = 54 * MIN, updatedAt = System.currentTimeMillis(),
                    title = "Coastline", imageUrl = null, seriesId = BoardTitles.SHOW.toLong(), season = 2, episode = 4
                )
            )
        }
        showHome()
        waitFor("Continue watching")
        options("Coastline", first = true)
        // Its own options once the server lists the episodes, then the show's favourite.
        listOf("Go to series", "THIS EPISODE", "Mark as watched", "Mark as unwatched", "THE SHOW", "Add to favourites")
            .forEach(::waitFor)
        // The owner, 2026-11-25: Home's card does not delete the series or its watched episodes.
        assertTrue(compose.onAllNodes(hasText("Delete watched episodes")).fetchSemanticsNodes().isEmpty())
        assertTrue(compose.onAllNodes(hasText("Delete the series…")).fetchSemanticsNodes().isEmpty())
        choose("Mark as watched")
        compose.waitAdvancingClock(stepMs = 50) { "624" in plex.watched }
    }

    @Test
    fun `a mark the server does not take says so`() {
        showHome()
        waitFor("The Long Harbour")
        options("The Long Harbour", first = true)
        waitFor("Mark as watched")
        plex.close()
        choose("Mark as watched")
        compose.waitAdvancingClock(stepMs = 50) {
            app.container.notices.current.value?.title == app.getString(R.string.vod_watched_not_saved)
        }
    }

    @Test
    fun `a mark the server does not take closes the panel, and the remote stays where it goes next`() {
        showHome()
        waitFor("The Long Harbour")
        options("The Long Harbour", first = true)
        waitFor("Mark as watched")
        // The server is out of reach from now on.
        plex.close()
        choose("Mark as watched")
        // The panel closes once the server answers, and a notice says the mark was not saved.
        compose.waitAdvancingClock(stepMs = 50) {
            compose.onAllNodes(hasText("Remove from Continue watching")).fetchSemanticsNodes().isEmpty()
        }
        assertEquals(app.getString(R.string.vod_watched_not_saved), app.container.notices.current.value?.title)
        // The remote goes on, down to a row under the top one.
        compose.pressAndRead(Key.DirectionDown)
        val focused = compose.pressAndRead(Key.DirectionDown)
        assertTrue(focused, focused.isNotEmpty())
        // Later the card goes from the row for another reason (here, straight from the database): the remote stays.
        runBlocking { app.container.db.vod().leaveMovieCarryOn(source, BoardTitles.FILM.toLong()) }
        compose.waitAdvancingClock(stepMs = 50) { compose.onAllNodes(hasText("Continue watching")).fetchSemanticsNodes().isEmpty() }
        compose.waitAdvancingClock(stepMs = 50) { true }
        assertEquals(focused, compose.focusedText())
    }

    @Test
    fun `an episode's card offers Watched at once, before the server lists the episodes`() {
        // Series 2's episode 4, watched part way. The server does not answer for the series' episodes.
        runBlocking {
            app.container.db.vod().saveProgress(
                WatchProgressEntity(
                    source, VOD_EPISODE, 624, positionMs = 33 * MIN, durationMs = 54 * MIN, updatedAt = System.currentTimeMillis(),
                    title = "Coastline", imageUrl = null, seriesId = BoardTitles.SHOW.toLong(), season = 2, episode = 4
                )
            )
        }
        plex.libraryUp = false
        showHome()
        waitFor("Continue watching")
        options("Coastline", first = true)
        waitFor("Mark as watched")
        waitFor("Mark as unwatched")
        plex.libraryUp = true
        choose("Mark as watched")
        compose.waitAdvancingClock(stepMs = 50) { "624" in plex.watched }
    }

    @Test
    fun `a favourite film's card offers its page's options too`() {
        showHome()
        compose.scrollToHomeRow(R.string.home_favourite_movies)
        waitFor("The Long Harbour")
        options("The Long Harbour", first = false)
        waitFor("Remove from favourites")
        waitFor("Cast and crew")
        choose("Cast and crew")
        waitFor("Cast and crew")
    }
}
