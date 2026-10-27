package com.biskyt.myplexplayer.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.pressKey
import androidx.test.core.app.ApplicationProvider
import androidx.tv.material3.Text
import com.biskyt.myplexplayer.R
import com.biskyt.myplexplayer.container
import com.biskyt.myplexplayer.data.ChannelFilter
import com.biskyt.myplexplayer.data.db.CAT_MOVIE
import com.biskyt.myplexplayer.data.db.CAT_SERIES
import com.biskyt.myplexplayer.testutil.FakePlex
import com.biskyt.myplexplayer.testutil.Navigators
import com.biskyt.myplexplayer.testutil.TestApp
import com.biskyt.myplexplayer.testutil.addSyncedHomePlex
import com.biskyt.myplexplayer.testutil.createUiComposeRule
import com.biskyt.myplexplayer.testutil.focusedText
import com.biskyt.myplexplayer.testutil.plexChannel
import com.biskyt.myplexplayer.testutil.scrollToHomeRow
import com.biskyt.myplexplayer.testutil.settle
import com.biskyt.myplexplayer.testutil.waitAdvancingClock
import com.biskyt.myplexplayer.testutil.waitForText
import com.biskyt.myplexplayer.ui.common.formatTime
import com.biskyt.myplexplayer.ui.home.HomeScreen
import com.biskyt.myplexplayer.ui.live.LiveScreen
import com.biskyt.myplexplayer.ui.search.SearchScreen
import com.biskyt.myplexplayer.ui.settings.AppearanceScreen
import com.biskyt.myplexplayer.ui.settings.BackupScreen
import com.biskyt.myplexplayer.ui.settings.CategoriesScreen
import com.biskyt.myplexplayer.ui.settings.PlaybackSettingsScreen
import com.biskyt.myplexplayer.ui.settings.PlaylistsScreen
import com.biskyt.myplexplayer.ui.settings.SettingsScreen
import com.biskyt.myplexplayer.ui.settings.WelcomeScreen
import com.biskyt.myplexplayer.ui.theme.MyPlexTheme
import com.biskyt.myplexplayer.ui.vod.MovieDetailsScreen
import com.biskyt.myplexplayer.ui.vod.SeriesDetailsScreen
import com.biskyt.myplexplayer.ui.vod.VodBrowseScreen
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Drives real screens against a fake Plex server. Also records screenshots (a 1280×720 TV) into app/screenshots;
 * `./gradlew verifyRoborazziDebug` fails if a screen changes unexpectedly.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = TestApp::class, qualifiers = "+w960dp-h540dp-tvdpi")
class AppUiTest {
    @get:Rule val compose = createUiComposeRule()

    @get:Rule(order = Navigators.ORDER)
    val navigators = Navigators()

    private val plex = FakePlex()
    private val app = ApplicationProvider.getApplicationContext<TestApp>()
    private var playlistId = 0L
    private lateinit var navigator: Navigator

    /** Adds Home Plex, with its channels One and Two, their guide, films and series, as [configure] leaves them. */
    private fun homePlex(configure: FakePlex.() -> Unit = {}) = runBlocking {
        plex.configure()
        playlistId = app.container.addSyncedHomePlex(plex)
    }

    @After
    fun tearDown() = plex.close()

    private var backDispatcher: androidx.activity.OnBackPressedDispatcher? = null

    private fun show(start: Screen) {
        navigator = navigators.new(start)
        compose.setContent {
            backDispatcher = androidx.activity.compose.LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
            MyPlexTheme { NavHost(remember { navigator }) { Screens(it) } }
        }
    }

    /** The remote's Back button. */
    private fun pressBack() {
        compose.runOnUiThread { backDispatcher!!.onBackPressed() }
        compose.waitForIdle()
    }

    @Composable
    private fun Screens(screen: Screen) {
        when (screen) {
            Screen.Home -> HomeScreen()
            Screen.Settings -> SettingsScreen()
            is Screen.Live -> LiveScreen(screen.filter, screen.guide)
            Screen.PlaybackSettings -> PlaybackSettingsScreen()
            Screen.Appearance -> AppearanceScreen()
            Screen.Backup -> BackupScreen()
            Screen.Welcome -> WelcomeScreen()
            is Screen.Categories -> CategoriesScreen(screen.playlistId, screen.firstRun)
            Screen.Playlists -> PlaylistsScreen()
            is Screen.Search -> SearchScreen(screen.query)
            is Screen.Movies -> VodBrowseScreen(CAT_MOVIE, screen.view)
            is Screen.Series -> VodBrowseScreen(CAT_SERIES, screen.view)
            is Screen.MovieDetails -> MovieDetailsScreen(screen)
            is Screen.SeriesDetails -> SeriesDetailsScreen(screen)
            is Screen.VodPlayer -> Text("VOD ${screen.itemId} ${screen.seriesId}")
            // The real player needs video hardware; a marker is enough to see where navigation went.
            is Screen.Player -> Text("PLAYER ${screen.streamId}")
            else -> Text(screen.toString())
        }
    }

    private fun waitFor(text: String) = compose.waitForText(text, stepMs = 50)

    private fun press(key: Key) {
        compose.onRoot().performKeyInput { pressKey(key) }
        compose.waitForIdle()
    }

    /** Android 9 (Fire OS 7) is the oldest the app runs on; the rest of the suite runs on a current Android. */
    @Test
    @Config(sdk = [28])
    fun `on android 9 the playlist and guide load, and Home, the channel list and the guide show`() {
        homePlex()
        show(Screen.Home)
        waitFor("Recently added movies")
        compose.runOnUiThread { navigator.reset(Screen.Live()) }
        waitFor("Two")
        compose.runOnUiThread { navigator.reset(Screen.Live(guide = true)) }
        waitFor("Film 0")
        // Backup asks for the storage permission there (declined here): the page still works for restoring a file.
        compose.runOnUiThread { navigator.reset(Screen.Backup) }
        waitFor("Restore from a file")
    }

    @Test
    fun `moving through the list never changes channel and OK plays the focused one`() {
        homePlex()
        show(Screen.Live())
        waitFor("Two")
        compose.onRoot().performKeyInput { pressKey(Key.DirectionDown) }
        compose.waitForIdle()
        assertEquals(Screen.Live(), navigator.current.screen)
        compose.onRoot().performKeyInput { pressKey(Key.DirectionCenter) }
        compose.waitForIdle()
        assertEquals(Screen.Player(playlistId, plexChannel("2"), ChannelFilter.All), navigator.current.screen)
    }

    @Test
    fun `a guide programme's options find its series episode, or search for its title`() {
        homePlex {
            programme = { i, n ->
                when {
                    n == 1 -> FakePlex.Programme("Film $i")
                    i == 0 -> FakePlex.Programme("Second", show = "Test Show", season = 1, episode = 2)
                    else -> FakePlex.Programme("Mystery Hour")
                }
            }
        }
        show(Screen.Live(guide = true))
        compose.waitAdvancingClock { compose.focusedText().contains("Test Show") }
        // To the screen's own window: the options open in a window of their own while OK is still held.
        compose.onAllNodes(isRoot())[0].performKeyInput {
            keyDown(Key.DirectionCenter)
            advanceEventTime(1_500)
            keyUp(Key.DirectionCenter)
        }
        waitFor("Find series")
        compose.onNode(hasText("Find series")).performSemanticsAction(SemanticsActions.OnClick)
        compose.waitAdvancingClock { navigator.current.screen is Screen.SeriesDetails }
        assertEquals(Screen.SeriesDetails(playlistId, 21, season = 1, episode = 2), navigator.current.screen)
    }

    @Test
    fun `guide scrolls to later times keeping focus, and the Guide button switches view on the same channel`() {
        homePlex()
        show(Screen.Live(guide = true))
        waitFor("Film 0")
        val start = System.currentTimeMillis() / (30 * 60_000L) * (30 * 60_000L)
        compose.waitAdvancingClock { compose.focusedText().contains("Show 0") }

        press(Key.DirectionRight) // now → next programme
        assertTrue(compose.focusedText(), compose.focusedText().contains("Film 0"))
        press(Key.DirectionRight) // past the window edge: the guide moves on an hour
        waitFor(formatTime(start + 60 * 60_000L))
        assertTrue(compose.focusedText(), compose.focusedText().contains("Film 0"))
        // And again, into empty time: focus stays in the same row. (A press while the next stretch of time loads does
        // nothing, as when the window just crossed into a new 3-hour chunk, so pressed until it gets there.)
        compose.waitAdvancingClock(50) {
            compose.focusedText().contains("No guide information").also { if (!it) press(Key.DirectionRight) }
        }
        waitFor(formatTime(start + 2 * 60 * 60_000L))

        press(Key.Guide)
        waitFor("Show 0")
        compose.waitAdvancingClock { compose.focusedText().contains("One") }
    }

    @Test
    fun `movies grid opens details and plays, series show their episodes`() {
        homePlex { episodes = episodes + FakePlex.episode("21", "Test Show", "213", 2, 1, "Return") }
        show(Screen.Movies())
        waitFor("Sintel")
        compose.onRoot().captureRoboImage("screenshots/movies.png")
        compose.onNode(hasText("Big Buck Bunny")).performSemanticsAction(SemanticsActions.OnClick)
        waitFor("A giant rabbit takes on three bullies.")
        waitFor("2008 · 10 min")
        compose.onRoot().captureRoboImage("screenshots/movie-details.png")
        compose.onNode(hasText("Play")).performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
        assertEquals(Screen.VodPlayer(playlistId, 11), navigator.current.screen)

        compose.runOnUiThread { navigator.reset(Screen.SeriesDetails(playlistId, 21)) }
        waitFor("Up next")
        waitFor("Second")
        compose.onRoot().captureRoboImage("screenshots/series-details.png")
        compose.onNode(hasText("Series 2")).performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
        compose.onNode(hasText("Return")).performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
        assertEquals(Screen.VodPlayer(playlistId, 213, 21), navigator.current.screen)
    }

    @Test
    fun `search finds channels, programmes and movies, and opens what is picked`() {
        homePlex { programme = { i, n -> FakePlex.Programme((if (n == 0) "Now on " else "Next on ") + channels[i].title) } }
        show(Screen.Search())
        compose.waitAdvancingClock { compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(hasSetTextAction()).performTextInput("one")
        waitFor("Now on One") // programme
        compose.waitAdvancingClock { compose.onAllNodes(hasText("One") and hasClickAction()).fetchSemanticsNodes().isNotEmpty() } // channel
        compose.onRoot().captureRoboImage("screenshots/search.png")
        compose.onNode(hasSetTextAction()).performTextReplacement("bunn")
        waitFor("Big Buck Bunny")
        compose.onNode(hasText("Big Buck Bunny")).performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
        assertEquals(Screen.MovieDetails(playlistId, 11), navigator.current.screen)
    }

    @Test
    fun `a search result's options add it to favourites`() {
        homePlex()
        show(Screen.Search("one"))
        waitFor("One")
        // Holding OK on the channel card (its long click) opens the options.
        compose.onAllNodes(hasText("One", substring = true) and hasClickAction(), useUnmergedTree = false)
            .onFirst().performSemanticsAction(SemanticsActions.OnLongClick)
        waitFor("Add to favourites")
        compose.onNode(hasText("Add to favourites")).performSemanticsAction(SemanticsActions.OnClick)
        waitFor("Remove from favourites")
        runBlocking { assertTrue(plexChannel("1") in app.container.channels.observeFavouriteIds(playlistId).first()) }
    }

    @Test
    fun `backup saves to Downloads and restores after confirming`() {
        homePlex()
        show(Screen.Backup)
        waitFor("No backup on this TV yet.")
        compose.onNode(hasText("Back up now")).performSemanticsAction(SemanticsActions.OnClick)
        waitFor("Saved to Download/MyPlexPlayer/myplexplayer-backup.json")
        compose.onRoot().captureRoboImage("screenshots/settings-backup.png")

        runBlocking { app.container.channels.setHidden(playlistId, plexChannel("1"), true) }
        compose.onNode(hasText("Restore")).performSemanticsAction(SemanticsActions.OnClick)
        waitFor("Restore?")
        compose.onNode(hasText("Restore")).performSemanticsAction(SemanticsActions.OnClick)
        // The restore finishes on the main thread, which this test is holding while it waits.
        compose.waitAdvancingClock { navigator.current.screen == Screen.Home }
        runBlocking {
            assertEquals(listOf(playlistId), app.container.playlists.allIds())
            assertEquals(false, app.container.db.channels().get(playlistId, plexChannel("1"))?.hidden ?: false)
        }
    }

    @Test
    fun `settings ends with ways to support the app, each with a QR code to scan`() {
        homePlex()
        show(Screen.Settings)
        waitFor("Playlists")
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("Ko-fi"))
        compose.onNode(hasText("Ko-fi") and hasClickAction()).performSemanticsAction(SemanticsActions.OnClick)
        waitFor("ko-fi.com/biskyt")
        assertTrue(compose.onAllNodes(hasContentDescription("QR code for https://ko-fi.com/biskyt")).fetchSemanticsNodes().isNotEmpty())
        compose.onRoot().captureRoboImage("screenshots/settings-support.png")
    }

    @Test
    fun `back in the channel list goes to the filter`() {
        homePlex()
        show(Screen.Live())
        compose.waitAdvancingClock { compose.focusedText().contains("One") }
        pressBack()
        assertTrue(compose.focusedText(), compose.focusedText().contains("Filter by name"))
        assertEquals(Screen.Live(), navigator.current.screen)
    }

    @Test
    fun `Home cards' options take them off their row`() {
        homePlex()
        runBlocking {
            val c = app.container
            c.channels.markWatched(playlistId, plexChannel("2"))
            c.vod.saveProgress(
                com.biskyt.myplexplayer.data.db.WatchProgressEntity(
                    playlistId,
                    com.biskyt.myplexplayer.data.db.VOD_MOVIE,
                    11,
                    600_000,
                    6_000_000,
                    System.currentTimeMillis(),
                    "Half Watched",
                    null
                )
            )
        }
        show(Screen.Home)
        waitFor("Half Watched")
        compose.onNode(hasText("Half Watched") and hasClickAction()).performSemanticsAction(SemanticsActions.RequestFocus)
        compose.onNode(hasText("Half Watched") and hasClickAction()).performSemanticsAction(SemanticsActions.OnLongClick)
        waitFor("About this movie")
        compose.onNode(hasText("Remove from Carry on watching")).performSemanticsAction(SemanticsActions.OnClick)
        compose.waitAdvancingClock { compose.onAllNodes(hasText("Half Watched")).fetchSemanticsNodes().isEmpty() }
        // Focus doesn't go with the card.
        compose.settle()
        assertTrue(compose.focusedText(), compose.focusedText().isNotBlank())

        // Live TV is last on Home, below the films and series.
        compose.scrollToHomeRow(R.string.common_recent, loaded = R.string.home_recently_added_movies)
        compose.onNode(hasText("Two", substring = true) and hasClickAction())
            .performSemanticsAction(SemanticsActions.OnLongClick)
        waitFor("Remove from Recently watched")
        compose.onNode(hasText("Remove from Recently watched")).performSemanticsAction(SemanticsActions.OnClick)
        compose.waitAdvancingClock { compose.onAllNodes(hasText("Recently watched")).fetchSemanticsNodes().isEmpty() }
    }

    @Test
    fun `the live player's More menu adds to favourites`() {
        homePlex()
        val vm = com.biskyt.myplexplayer.ui.player.PlayerViewModel(
            app.container,
            app,
            Screen.Player(playlistId, plexChannel("1"), ChannelFilter.All)
        )
        compose.waitAdvancingClock { vm.menuActions().isNotEmpty() }
        val labels = vm.menuActions().map { it.label }
        assertEquals(listOf("Add to favourites"), labels)
        (vm.menuActions().first() as com.biskyt.myplexplayer.ui.common.ItemAction.Run).run()
        compose.waitAdvancingClock { vm.menuActions().first().label == "Remove from favourites" }
        vm.engine?.release()
    }

    @Test
    fun `first launch offers a choice and sets nothing up by itself`() {
        homePlex()
        show(Screen.Welcome)
        waitFor("Sign in to Plex")
        waitFor("Restore a backup")
        compose.mainClock.advanceTimeBy(2_000)
        compose.waitForIdle()
        runBlocking { assertEquals(listOf(playlistId), app.container.playlists.allIds()) }
    }

    @Test
    fun `screenshots of the main screens`() {
        homePlex()
        show(Screen.Live())
        waitFor("Show 0")
        compose.onRoot().captureRoboImage("screenshots/live-list.png")

        compose.runOnUiThread { navigator.reset(Screen.Live(guide = true)) }
        waitFor("Film 0")
        compose.onRoot().captureRoboImage("screenshots/live-guide.png")

        runBlocking { app.container.channels.setFavourite(playlistId, plexChannel("1"), true) }
        compose.runOnUiThread { navigator.reset(Screen.Home) }
        // Live TV is last on Home, below the films and series.
        compose.scrollToHomeRow(R.string.home_on_now, loaded = R.string.home_recently_added_movies)
        waitFor("Show 0")
        compose.onRoot().captureRoboImage("screenshots/home.png")

        compose.runOnUiThread { navigator.reset(Screen.PlaybackSettings) }
        waitFor("Audio passthrough")
        compose.onRoot().captureRoboImage("screenshots/settings-playback.png")

        compose.runOnUiThread { navigator.reset(Screen.Appearance) }
        waitFor("Accent colour")
        compose.onRoot().captureRoboImage("screenshots/settings-appearance.png")
    }
}
