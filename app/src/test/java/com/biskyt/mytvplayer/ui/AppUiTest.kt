package com.biskyt.mytvplayer.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.printToString
import androidx.test.core.app.ApplicationProvider
import androidx.tv.material3.Text
import com.biskyt.mytvplayer.container
import com.biskyt.mytvplayer.data.ChannelFilter
import com.biskyt.mytvplayer.data.PlaylistRepository
import com.biskyt.mytvplayer.data.db.CAT_MOVIE
import com.biskyt.mytvplayer.data.db.CAT_SERIES
import com.biskyt.mytvplayer.testutil.FakeProvider
import com.biskyt.mytvplayer.testutil.TestApp
import com.biskyt.mytvplayer.ui.common.formatTime
import com.biskyt.mytvplayer.ui.guide.GuideMatchingScreen
import com.biskyt.mytvplayer.ui.home.HomeScreen
import com.biskyt.mytvplayer.ui.live.LiveScreen
import com.biskyt.mytvplayer.ui.settings.AppearanceScreen
import com.biskyt.mytvplayer.ui.settings.PlaybackSettingsScreen
import com.biskyt.mytvplayer.ui.settings.PlaylistsScreen
import com.biskyt.mytvplayer.ui.theme.MyTvTheme
import com.biskyt.mytvplayer.ui.vod.MovieDetailsScreen
import com.biskyt.mytvplayer.ui.vod.SeriesDetailsScreen
import com.biskyt.mytvplayer.ui.vod.VodBrowseScreen
import com.github.takahirom.roborazzi.captureRoboImage
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
import org.robolectric.annotation.GraphicsMode

/**
 * Drives real screens against a fake provider. Also records screenshots (a 1280×720 TV) into app/screenshots;
 * `./gradlew verifyRoborazziDebug` fails if a screen changes unexpectedly.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = TestApp::class, qualifiers = "w960dp-h540dp-tvdpi")
class AppUiTest {
    @get:Rule val compose = createComposeRule()

    private val provider = FakeProvider()
    private val app = ApplicationProvider.getApplicationContext<TestApp>()
    private var playlistId = 0L
    private lateinit var navigator: Navigator

    @Before
    fun setUp() = runBlocking {
        val c = app.container
        playlistId = c.playlists.add("Home TV", provider.url, "me", "secret")
        c.playlists.syncChannels(playlistId)
        c.vod.sync(c.playlists.get(playlistId)!!)
        c.playlists.get(playlistId)?.let { c.epg.ensureProviderSource(it, c.playlists.client(it).xmltvUrl()) }
        c.epg.importAll(listOf(playlistId))
    }

    @After
    fun tearDown() = provider.close()

    private fun show(start: Screen) {
        navigator = Navigator(start)
        compose.setContent { MyTvTheme { NavHost(remember { navigator }) { Screens(it) } } }
    }

    @Composable
    private fun Screens(screen: Screen) {
        when (screen) {
            Screen.Home -> HomeScreen()
            is Screen.Live -> LiveScreen(screen.filter, screen.guide)
            is Screen.GuideMatching -> GuideMatchingScreen(screen.playlistId, screen.streamId)
            Screen.PlaybackSettings -> PlaybackSettingsScreen()
            Screen.Appearance -> AppearanceScreen()
            Screen.Playlists -> PlaylistsScreen()
            is Screen.Movies -> VodBrowseScreen(CAT_MOVIE, screen.filter)
            is Screen.Series -> VodBrowseScreen(CAT_SERIES, screen.filter)
            is Screen.MovieDetails -> MovieDetailsScreen(screen)
            is Screen.SeriesDetails -> SeriesDetailsScreen(screen)
            is Screen.VodPlayer -> Text("VOD ${screen.itemId} ${screen.seriesId}")
            // The real player needs video hardware; a marker is enough to see where navigation went.
            is Screen.Player -> Text("PLAYER ${screen.streamId}")
            else -> Text(screen.toString())
        }
    }

    private fun waitFor(text: String) = try {
        compose.waitUntil(5_000) { compose.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty() }
    } catch (e: androidx.compose.ui.test.ComposeTimeoutException) {
        throw AssertionError("'$text' not shown. Screen:\n" + compose.onRoot(useUnmergedTree = true).printToString(), e)
    }

    @Test
    fun `moving through the list never changes channel and OK plays the focused one`() {
        show(Screen.Live())
        waitFor("UK: KITE KIDZ")
        compose.onRoot().performKeyInput { pressKey(Key.DirectionDown) }
        compose.waitForIdle()
        assertEquals(Screen.Live(), navigator.current.screen)
        compose.onRoot().performKeyInput { pressKey(Key.DirectionCenter) }
        compose.waitForIdle()
        assertEquals(Screen.Player(playlistId, 102, ChannelFilter.All), navigator.current.screen)
    }

    @Test
    fun `long press opens channel options and leads to guide matching`() {
        show(Screen.Live())
        waitFor("UK: CHANNEL ONE HD")
        compose.onRoot().performKeyInput {
            keyDown(Key.DirectionCenter)
            advanceEventTime(1_500)
            keyUp(Key.DirectionCenter)
        }
        waitFor("Change guide source")
        // TV surfaces react to the remote, not touch: trigger the item's click action directly.
        compose.onNode(hasText("Change guide source…")).performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
        assertEquals(Screen.GuideMatching(playlistId, 101), navigator.current.screen)
    }

    private fun focusedText(): String = compose.onAllNodes(isFocused()).fetchSemanticsNodes().joinToString(" | ") { node ->
        node.config.getOrNull(SemanticsProperties.Text)?.joinToString().orEmpty()
    }

    private fun press(key: Key) {
        compose.onRoot().performKeyInput { pressKey(key) }
        compose.waitForIdle()
    }

    @Test
    fun `guide scrolls to later times keeping focus, and the Guide button switches view on the same channel`() {
        show(Screen.Live(guide = true))
        waitFor("Next on one.uk")
        val start = System.currentTimeMillis() / (30 * 60_000L) * (30 * 60_000L)
        compose.waitUntil(5_000) { focusedText().contains("Now on one.uk") }

        press(Key.DirectionRight) // now → next programme
        assertTrue(focusedText(), focusedText().contains("Next on one.uk"))
        press(Key.DirectionRight) // past the window edge: the guide moves on an hour
        waitFor(formatTime(start + 60 * 60_000L))
        assertTrue(focusedText(), focusedText().contains("Next on one.uk"))
        press(Key.DirectionRight) // and again, into empty time: focus stays in the same row
        waitFor(formatTime(start + 2 * 60 * 60_000L))
        assertTrue(focusedText(), focusedText().contains("No guide information"))

        press(Key.Guide)
        waitFor("Now on one.uk")
        compose.waitUntil(5_000) { focusedText().contains("UK: CHANNEL ONE HD") }
    }

    @Test
    fun `movies grid opens details and plays, series show their episodes`() {
        show(Screen.Movies())
        waitFor("Paddington 2")
        compose.onRoot().captureRoboImage("screenshots/movies.png")
        compose.onNode(hasText("Dune")).performSemanticsAction(SemanticsActions.OnClick)
        waitFor("A story.")
        waitFor("2021 · 1 h 40 min · Drama")
        compose.onRoot().captureRoboImage("screenshots/movie-details.png")
        compose.onNode(hasText("Play")).performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
        assertEquals(Screen.VodPlayer(playlistId, 501), navigator.current.screen)

        compose.runOnUiThread { navigator.reset(Screen.SeriesDetails(playlistId, 77)) }
        waitFor("Play S1 E1")
        waitFor("Episode 2")
        compose.onRoot().captureRoboImage("screenshots/series-details.png")
        compose.onNode(hasText("Series 2")).performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
        compose.onNode(hasText("Episode 1")).performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
        assertEquals(Screen.VodPlayer(playlistId, 9003, 77), navigator.current.screen)
    }

    @Test
    fun `free channels button shows only until they are added`() {
        show(Screen.Playlists)
        waitFor("Home TV")
        waitFor("Add free channels (iptv-org)")
        runBlocking {
            val c = app.container
            c.db.playlists().insert(
                com.biskyt.mytvplayer.data.db.PlaylistEntity(
                    name = "Free TV (iptv-org)",
                    kind = "m3u",
                    server = PlaylistRepository.FREE_PLAYLIST_URL,
                    username = "",
                    password = ""
                )
            )
        }
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("Add free channels", substring = true)).fetchSemanticsNodes().isEmpty() }
    }

    @Test
    fun `screenshots of the main screens`() {
        show(Screen.Live())
        waitFor("Now on one.uk")
        compose.onRoot().captureRoboImage("screenshots/live-list.png")

        compose.runOnUiThread { navigator.reset(Screen.Live(guide = true)) }
        waitFor("Next on one.uk")
        compose.onRoot().captureRoboImage("screenshots/live-guide.png")

        runBlocking { app.container.channels.setFavourite(playlistId, 101, true) }
        compose.runOnUiThread { navigator.reset(Screen.Home) }
        waitFor("Your channels, on now")
        waitFor("Now on one.uk")
        compose.onRoot().captureRoboImage("screenshots/home.png")

        compose.runOnUiThread { navigator.reset(Screen.GuideMatching(playlistId, 102)) }
        waitFor("Arena Sports 1 HD")
        waitFor("UK: KITE KIDZ")
        compose.onRoot().captureRoboImage("screenshots/guide-matching.png")

        compose.runOnUiThread { navigator.reset(Screen.PlaybackSettings) }
        waitFor("Audio passthrough")
        compose.onRoot().captureRoboImage("screenshots/settings-playback.png")

        compose.runOnUiThread { navigator.reset(Screen.Appearance) }
        waitFor("Accent colour")
        compose.onRoot().captureRoboImage("screenshots/settings-appearance.png")
    }
}
