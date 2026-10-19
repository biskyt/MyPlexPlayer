package com.biskyt.mytvplayer.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
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
import com.biskyt.mytvplayer.ui.guide.GuideSourcesScreen
import com.biskyt.mytvplayer.ui.home.HomeScreen
import com.biskyt.mytvplayer.ui.live.CatchupScreen
import com.biskyt.mytvplayer.ui.live.LiveScreen
import com.biskyt.mytvplayer.ui.search.SearchScreen
import com.biskyt.mytvplayer.ui.settings.AppearanceScreen
import com.biskyt.mytvplayer.ui.settings.BackupScreen
import com.biskyt.mytvplayer.ui.settings.CategoriesScreen
import com.biskyt.mytvplayer.ui.settings.PlaybackSettingsScreen
import com.biskyt.mytvplayer.ui.settings.PlaylistsScreen
import com.biskyt.mytvplayer.ui.settings.WelcomeScreen
import com.biskyt.mytvplayer.ui.theme.MyTvTheme
import com.biskyt.mytvplayer.ui.vod.MovieDetailsScreen
import com.biskyt.mytvplayer.ui.vod.SeriesDetailsScreen
import com.biskyt.mytvplayer.ui.vod.VodBrowseScreen
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.coroutines.flow.first
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
        // Settings outlive a test (one DataStore per process): Live TV starts on All channels unless a test says otherwise.
        c.settings.update { it.copy(lastLiveFilter = "all") }
        c.settings.current()
        Unit
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
            Screen.Backup -> BackupScreen()
            Screen.Welcome -> WelcomeScreen()
            is Screen.Categories -> CategoriesScreen(screen.playlistId, screen.firstRun)
            Screen.GuideSources -> GuideSourcesScreen()
            Screen.Playlists -> PlaylistsScreen()
            is Screen.Search -> SearchScreen(screen.query)
            is Screen.Movies -> VodBrowseScreen(CAT_MOVIE, screen.filter)
            is Screen.Series -> VodBrowseScreen(CAT_SERIES, screen.filter)
            is Screen.MovieDetails -> MovieDetailsScreen(screen)
            is Screen.SeriesDetails -> SeriesDetailsScreen(screen)
            is Screen.VodPlayer -> Text("VOD ${screen.itemId} ${screen.seriesId}")
            // The real player needs video hardware; a marker is enough to see where navigation went.
            is Screen.Player -> Text("PLAYER ${screen.streamId}")
            is Screen.CatchupPlayer -> Text("CATCHUP ${screen.streamId} ${screen.startMs}")
            is Screen.Catchup -> CatchupScreen(screen)
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

    @Test
    fun `a guide programme's options find its series episode, or search for its title`() {
        runBlocking {
            provider.nowProgramme = mapOf(
                "one.uk" to "<title>Line of Duty</title><category>Drama</category><episode-num system=\"xmltv_ns\">0.1.</episode-num>",
                "kite.uk" to "<title>Mystery Hour</title>"
            )
            app.container.epg.importAll(listOf(playlistId))
        }
        show(Screen.Live(guide = true))
        compose.waitUntil(5_000) { focusedText().contains("Line of Duty") }
        compose.onRoot().performKeyInput {
            keyDown(Key.DirectionCenter)
            advanceEventTime(1_500)
            keyUp(Key.DirectionCenter)
        }
        waitFor("Find series")
        compose.onNode(hasText("Find series")).performSemanticsAction(SemanticsActions.OnClick)
        compose.waitUntil(5_000) {
            org.robolectric.shadows.ShadowLooper.idleMainLooper()
            navigator.current.screen is Screen.SeriesDetails
        }
        assertEquals(Screen.SeriesDetails(playlistId, 77, season = 1, episode = 2), navigator.current.screen)
    }

    @Test
    fun `catch-up channels offer catch-up and start over, and the page replays a past programme`() {
        show(Screen.Live())
        waitFor("UK: CHANNEL ONE HD")
        compose.onRoot().performKeyInput {
            keyDown(Key.DirectionCenter)
            advanceEventTime(1_500)
            keyUp(Key.DirectionCenter)
        }
        waitFor("Start over")
        compose.onNode(hasText("Catch-up")).performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
        assertEquals(Screen.Catchup(playlistId, 101), navigator.current.screen)
        waitFor("Earlier Today")
        assertTrue(compose.onAllNodes(hasText("Not Kept")).fetchSemanticsNodes().isEmpty())
        compose.onNode(hasText("Earlier Today")).performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
        assertTrue(navigator.current.screen.toString(), navigator.current.screen is Screen.CatchupPlayer)

        // A channel without catch-up offers neither.
        compose.runOnUiThread { navigator.reset(Screen.Live()) }
        waitFor("UK: ARENA SPRTS 1 FHD")
        press(Key.DirectionDown)
        compose.onRoot().performKeyInput {
            keyDown(Key.DirectionCenter)
            advanceEventTime(1_500)
            keyUp(Key.DirectionCenter)
        }
        waitFor("Hide channel")
        assertTrue(compose.onAllNodes(hasText("Start over")).fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun `the guide asks for catch-up only when scrolled into the past`() {
        show(Screen.Live(guide = true))
        compose.waitUntil(5_000) { focusedText().contains("Now on one.uk") }
        assertTrue(provider.requests.none { it.endsWith("get_simple_data_table") })
        // Left at "now" looks up how far back catch-up goes, then the guide moves back an hour.
        press(Key.DirectionLeft)
        compose.waitUntil(5_000) {
            org.robolectric.shadows.ShadowLooper.idleMainLooper()
            compose.onAllNodes(hasText("Earlier Today", substring = true)).fetchSemanticsNodes().isNotEmpty()
        }
        assertTrue(provider.requests.any { it.endsWith("get_simple_data_table") })
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
    fun `search finds channels, programmes and movies, and opens what is picked`() {
        show(Screen.Search())
        compose.waitUntil(5_000) { compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(hasSetTextAction()).performTextInput("one")
        waitFor("Now on one.uk") // programme
        waitFor("UK: CHANNEL ONE HD") // channel
        compose.onRoot().captureRoboImage("screenshots/search.png")
        compose.onNode(hasSetTextAction()).performTextReplacement("padd")
        waitFor("Paddington 2")
        compose.onNode(hasText("Paddington 2")).performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
        assertEquals(Screen.MovieDetails(playlistId, 502), navigator.current.screen)
    }

    @Test
    fun `a search result's options add it to favourites and open catch-up`() {
        show(Screen.Search("one"))
        waitFor("UK: CHANNEL ONE HD")
        // Holding OK on the channel card (its long click) opens the options.
        compose.onAllNodes(hasText("UK: CHANNEL ONE HD", substring = true) and hasClickAction(), useUnmergedTree = false)
            .onFirst().performSemanticsAction(SemanticsActions.OnLongClick)
        waitFor("Add to favourites")
        compose.onNode(hasText("Add to favourites")).performSemanticsAction(SemanticsActions.OnClick)
        waitFor("Remove from favourites")
        runBlocking { assertTrue(101L in app.container.channels.observeFavouriteIds(playlistId).first()) }
        waitFor("Catch-up")
        compose.onNode(hasText("Catch-up")).performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
        assertEquals(Screen.Catchup(playlistId, 101), navigator.current.screen)
    }

    @Test
    fun `backup saves to Downloads and restores after confirming`() {
        show(Screen.Backup)
        waitFor("No backup on this TV yet.")
        compose.onNode(hasText("Back up now")).performSemanticsAction(SemanticsActions.OnClick)
        waitFor("Saved to Download/MyTVPlayer/mytvplayer-backup.json")
        compose.onRoot().captureRoboImage("screenshots/settings-backup.png")

        runBlocking { app.container.channels.setHidden(playlistId, 101, true) }
        compose.onNode(hasText("Restore")).performSemanticsAction(SemanticsActions.OnClick)
        waitFor("Restore?")
        compose.onNode(hasText("Restore")).performSemanticsAction(SemanticsActions.OnClick)
        compose.waitUntil(5_000) {
            // The restore finishes on the main thread, which this test is holding while it waits.
            org.robolectric.shadows.ShadowLooper.idleMainLooper()
            navigator.current.screen == Screen.Home
        }
        runBlocking {
            assertEquals(listOf(playlistId), app.container.playlists.allIds())
            assertEquals(false, app.container.db.channels().get(playlistId, 101)?.hidden ?: false)
        }
    }

    @Test
    fun `live TV opens on the category used last`() {
        runBlocking {
            app.container.settings.update { it.copy(lastLiveFilter = ChannelFilter.Category("2").key) }
            app.container.settings.current()
        }
        show(Screen.Live())
        waitFor("UK: ARENA SPRTS 1 FHD")
        assertTrue(compose.onAllNodes(hasText("UK: KITE KIDZ")).fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun `guide sources can match channels without a guide, showing the result`() {
        show(Screen.GuideSources)
        waitFor("Match channels without a guide")
        compose.onNode(hasText("Match channels without a guide")).performSemanticsAction(SemanticsActions.OnClick)
        compose.waitUntil(10_000) {
            org.robolectric.shadows.ShadowLooper.idleMainLooper()
            compose.onAllNodes(hasText("No new matches found") or hasText("Found a guide for", substring = true))
                .fetchSemanticsNodes().isNotEmpty()
        }
        // The outcome says how many channels have a guide, not only what this run found.
        waitFor("channel has a guide")
    }

    @Test
    fun `guides are added from a panel offering free country guides`() {
        show(Screen.GuideSources)
        waitFor("Your guides")
        compose.onNode(hasText("Add a guide")).performSemanticsAction(SemanticsActions.OnClick)
        waitFor("Free guides by country")
        waitFor("Your own XMLTV address")
    }

    @Test
    fun `the live player's More menu adds to favourites and opens catch-up and the guide source`() {
        val vm = com.biskyt.mytvplayer.ui.player.PlayerViewModel(app.container, app, Screen.Player(playlistId, 101, ChannelFilter.All))
        compose.waitUntil(5_000) {
            org.robolectric.shadows.ShadowLooper.idleMainLooper()
            vm.menuActions().isNotEmpty()
        }
        val labels = vm.menuActions().map { it.label }
        assertEquals(listOf("Add to favourites", "Catch-up", "Change guide source…"), labels)
        assertEquals(
            Screen.GuideMatching(playlistId, 101),
            (vm.menuActions().last() as com.biskyt.mytvplayer.ui.common.ItemAction.Open).screen
        )
        (vm.menuActions().first() as com.biskyt.mytvplayer.ui.common.ItemAction.Run).run()
        compose.waitUntil(5_000) {
            org.robolectric.shadows.ShadowLooper.idleMainLooper()
            vm.menuActions().first().label == "Remove from favourites"
        }
        vm.engine?.release()
    }

    @Test
    fun `first launch offers a choice and sets nothing up by itself`() {
        show(Screen.Welcome)
        waitFor("Add my TV provider")
        waitFor("Free channels (iptv-org)")
        waitFor("Restore a backup")
        compose.mainClock.advanceTimeBy(2_000)
        compose.waitForIdle()
        runBlocking { assertEquals(listOf(playlistId), app.container.playlists.allIds()) }
    }

    @Test
    fun `languages and categories can be filtered`() {
        show(Screen.Categories(playlistId))
        waitFor("UK | ENTERTAINMENT")
        // The box opens for typing when pressed (OK on the remote).
        compose.onNode(hasText("Filter countries and categories")).performSemanticsAction(SemanticsActions.OnClick)
        compose.waitUntil(5_000) { compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(hasSetTextAction()).performTextInput("cine")
        waitFor("FR: CINEMA")
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("UK | ENTERTAINMENT")).fetchSemanticsNodes().isEmpty() }
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
        // Opened for one channel: only that channel is listed.
        waitFor("UK: ARENA SPRTS 1 FHD")
        assertTrue(compose.onAllNodes(hasText("UK: KITE KIDZ")).fetchSemanticsNodes().isEmpty())
        compose.onRoot().captureRoboImage("screenshots/guide-matching.png")

        compose.runOnUiThread { navigator.reset(Screen.PlaybackSettings) }
        waitFor("Audio passthrough")
        compose.onRoot().captureRoboImage("screenshots/settings-playback.png")

        compose.runOnUiThread { navigator.reset(Screen.Appearance) }
        waitFor("Accent colour")
        compose.onRoot().captureRoboImage("screenshots/settings-appearance.png")
    }
}
