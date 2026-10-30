package com.biskyt.myplexplayer.ui

import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onRoot
import androidx.test.core.app.ApplicationProvider
import com.biskyt.myplexplayer.data.db.CAT_MOVIE
import com.biskyt.myplexplayer.testutil.FakePlex
import com.biskyt.myplexplayer.testutil.Navigators
import com.biskyt.myplexplayer.testutil.OnAppBackground
import com.biskyt.myplexplayer.testutil.TestApp
import com.biskyt.myplexplayer.testutil.addSyncedHomePlex
import com.biskyt.myplexplayer.testutil.createUiComposeRule
import com.biskyt.myplexplayer.testutil.waitAdvancingClock
import com.biskyt.myplexplayer.testutil.waitForText
import com.biskyt.myplexplayer.ui.theme.MyPlexTheme
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A film's age rating in a badge, without its country, and its review scores with their sources' logos. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestApp::class, qualifiers = "+w960dp-h540dp-tvdpi")
class ScoresUiTest {
    @get:Rule val compose = createUiComposeRule()

    @get:Rule(order = Navigators.ORDER)
    val navigators = Navigators()

    private val plex = FakePlex()
    private val app = ApplicationProvider.getApplicationContext<TestApp>()
    private var source = 0L

    private fun rating(image: String, type: String, value: Double) = JsonObject(
        mapOf("image" to JsonPrimitive(image), "type" to JsonPrimitive(type), "value" to JsonPrimitive(value))
    )

    @Before
    fun setUp() = runBlocking {
        val film = FakePlex.film("11", "Big Buck Bunny", 2008, summary = "A giant rabbit takes on three bullies.")
        plex.movies = listOf(
            JsonObject(
                film + mapOf(
                    "contentRating" to JsonPrimitive("gb/12A"),
                    "Guid" to buildJsonArray {
                        listOf("imdb://tt1254207", "tmdb://10378", "tvdb://160").forEach {
                            add(JsonObject(mapOf("id" to JsonPrimitive(it))))
                        }
                    },
                    "Rating" to buildJsonArray {
                        add(rating("imdb://image.rating", "audience", 7.6))
                        add(rating("rottentomatoes://image.rating.ripe", "critic", 9.4))
                        add(rating("rottentomatoes://image.rating.spilled", "audience", 5.2))
                        add(rating("themoviedb://image.rating", "audience", 7.5))
                    }
                )
            )
        )
        val show = FakePlex.show("21", "Test Show", 2020, summary = "Two episodes of colour bars.")
        plex.shows = listOf(
            JsonObject(
                show + mapOf(
                    "contentRating" to JsonPrimitive("gb/15"),
                    "Rating" to buildJsonArray {
                        add(rating("imdb://image.rating", "audience", 8.1))
                        add(rating("rottentomatoes://image.rating.rotten", "critic", 5.5))
                        add(rating("rottentomatoes://image.rating.upright", "audience", 9.0))
                        add(rating("themoviedb://image.rating", "audience", 7.9))
                    }
                )
            )
        )
        source = app.container.addSyncedHomePlex(plex)
    }

    @After
    fun tearDown() = plex.close()

    private fun shown(text: String) = compose.onAllNodes(hasText(text)).fetchSemanticsNodes().size

    private fun logos(name: String) = compose.onAllNodes(hasContentDescription(name)).fetchSemanticsNodes().size

    @Test
    fun `a film's page shows its age rating in a badge and each score with its logo`() {
        val navigator = navigators.new(Screen.MovieDetails(source, 11))
        compose.setContent { MyPlexTheme { OnAppBackground { NavHost(navigator) { ScreenContent(it) } } } }
        compose.waitForText("12A", stepMs = 50)
        assertEquals("no country", 0, shown("gb/12A"))
        compose.waitAdvancingClock(stepMs = 50) { shown("7.6") > 0 }
        assertEquals(listOf(1, 1, 1, 1), listOf(shown("7.6"), shown("94%"), shown("52%"), shown("75%")))
        assertEquals(
            listOf(1, 1, 1, 1),
            listOf(logos("IMDb"), logos("Rotten Tomatoes critics"), logos("Rotten Tomatoes audience"), logos("TMDB"))
        )
        assertEquals(
            "the server's own score isn't shown as well",
            0,
            compose.onAllNodes(hasText("★", substring = true)).fetchSemanticsNodes().size
        )
        compose.onRoot().captureRoboImage("build/scores/film.png")
    }

    @Test
    fun `more info's links show their sites' logos`() {
        val navigator = navigators.new(Screen.MoreInfo(source, CAT_MOVIE, 11))
        compose.setContent { MyPlexTheme { OnAppBackground { NavHost(navigator) { ScreenContent(it) } } } }
        compose.waitAdvancingClock(stepMs = 50) { logos("TVDB") > 0 }
        assertEquals(
            listOf(1, 1),
            listOf(logos("TVDB"), compose.onAllNodes(hasContentDescription("IMDb") and hasClickAction()).fetchSemanticsNodes().size)
        )
        compose.onRoot().captureRoboImage("build/scores/more-info.png")
    }

    @Test
    fun `a show's scores carry on to another line in its narrow column`() {
        val navigator = navigators.new(Screen.SeriesDetails(source, 21))
        compose.setContent { MyPlexTheme { OnAppBackground { NavHost(navigator) { ScreenContent(it) } } } }
        compose.waitForText("15", stepMs = 50)
        compose.waitAdvancingClock(stepMs = 50) { shown("79%") > 0 }
        assertEquals(listOf(1, 1, 1, 1), listOf(shown("8.1"), shown("55%"), shown("90%"), shown("79%")))
        compose.onRoot().captureRoboImage("build/scores/show.png")
    }
}
