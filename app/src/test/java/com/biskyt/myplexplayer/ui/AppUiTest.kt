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
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.pressKey
import androidx.test.core.app.ApplicationProvider
import androidx.tv.material3.Text
import com.biskyt.myplexplayer.R
import com.biskyt.myplexplayer.container
import com.biskyt.myplexplayer.data.ChannelFilter
import com.biskyt.myplexplayer.data.db.CAT_LIVE
import com.biskyt.myplexplayer.data.db.CAT_MOVIE
import com.biskyt.myplexplayer.data.db.CAT_SERIES
import com.biskyt.myplexplayer.testutil.FakePlex
import com.biskyt.myplexplayer.testutil.FakeProvider
import com.biskyt.myplexplayer.testutil.Navigators
import com.biskyt.myplexplayer.testutil.TestApp
import com.biskyt.myplexplayer.testutil.addHomeTv
import com.biskyt.myplexplayer.testutil.addSyncedHomePlex
import com.biskyt.myplexplayer.testutil.createUiComposeRule
import com.biskyt.myplexplayer.testutil.focusedText
import com.biskyt.myplexplayer.testutil.plexChannel
import com.biskyt.myplexplayer.testutil.scrollToHomeRow
import com.biskyt.myplexplayer.testutil.settle
import com.biskyt.myplexplayer.testutil.waitAdvancingClock
import com.biskyt.myplexplayer.testutil.waitForText
import com.biskyt.myplexplayer.ui.common.formatTime
import com.biskyt.myplexplayer.ui.guide.GuideMatchingScreen
import com.biskyt.myplexplayer.ui.guide.GuideSourcesScreen
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
 * Drives real screens against a fake Plex server (or, for what only an Xtream provider and XMLTV guides have, a fake
 * provider). Also records screenshots (a 1280×720 TV) into app/screenshots;
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
    private val provider = FakeProvider()
    private val app = ApplicationProvider.getApplicationContext<TestApp>()
    private var playlistId = 0L
    private lateinit var navigator: Navigator

    /** Adds Home Plex, with its channels One and Two, their guide, films and series, as [configure] leaves them. */
    private fun homePlex(configure: FakePlex.() -> Unit = {}) = runBlocking {
        plex.configure()
        playlistId = app.container.addSyncedHomePlex(plex)
    }

    /** Adds the fake provider ("Home TV"), with its guide, movies and series: for its categories and guides. */
    private fun homeTv() = runBlocking { playlistId = app.container.addHomeTv(provider, vod = true, guide = true) }

    @After
    fun tearDown() {
        plex.close()
        provider.close()
    }

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
            else -> Text(screen.toString())
        }
    }

    private fun waitFor(text: String) = compose.waitForText(text, stepMs = 50)

    private fun press(key: Key) {
        compose.onRoot().performKeyInput { pressKey(key) }
        compose.waitForIdle()
    }

    /** Picks [label] in the options panel open (the top bar may have a tab of the same name). */
    private fun clickOption(label: String) {
        compose.onAllNodes(hasText(label) and hasClickAction()).onLast().performSemanticsAction(SemanticsActions.OnClick)
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
    fun `long press opens channel options and leads to guide matching`() {
        homeTv()
        show(Screen.Live())
        waitFor("UK: CHANNEL ONE HD")
        // To the screen's own window: the options open in a window of their own while OK is still held.
        compose.onAllNodes(isRoot())[0].performKeyInput {
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
        press(Key.DirectionRight) // and again, into empty time: focus stays in the same row
        waitFor(formatTime(start + 2 * 60 * 60_000L))
        assertTrue(compose.focusedText(), compose.focusedText().contains("No guide information"))

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
        waitFor("Play S1 E1")
        waitFor("Second")
        compose.onRoot().captureRoboImage("screenshots/series-details.png")
        compose.onNode(hasText("Season 2")).performSemanticsAction(SemanticsActions.OnClick)
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
    fun `a search result's options add it to favourites and open its guide source`() {
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
        clickOption("Change guide source…")
        compose.waitForIdle()
        assertEquals(Screen.GuideMatching(playlistId, plexChannel("1")), navigator.current.screen)
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
    fun `the guide stays on the category it was opened from`() {
        homeTv()
        show(Screen.Live(ChannelFilter.Category("2")))
        compose.waitAdvancingClock { compose.focusedText().contains("UK: ARENA SPRTS 1 FHD") }
        press(Key.Guide)
        compose.settle()
        compose.waitAdvancingClock { compose.focusedText().contains("No guide information") }
        compose.settle()
        assertTrue(compose.onAllNodes(hasText("UK: KITE KIDZ")).fetchSemanticsNodes().isEmpty())
        // Back to the list and to the guide again: still the same category.
        press(Key.Guide)
        compose.settle()
        press(Key.Guide)
        compose.settle()
        assertTrue(compose.onAllNodes(hasText("UK: ARENA SPRTS 1 FHD")).fetchSemanticsNodes().isNotEmpty())
        assertTrue(compose.onAllNodes(hasText("UK: KITE KIDZ")).fetchSemanticsNodes().isEmpty())

        // Focus thrown at another category (as when the row with focus goes away) goes to the selected one instead…
        compose.onNode(hasText("All channels") and hasClickAction()).performSemanticsAction(SemanticsActions.RequestFocus)
        // The column moves focus a frame later: let (simulated) time pass while waiting.
        compose.waitAdvancingClock(100) { compose.focusedText().contains("SPORT", ignoreCase = true) }
        assertTrue(compose.onAllNodes(hasText("UK: KITE KIDZ")).fetchSemanticsNodes().isEmpty())
        // …and moving through the categories switches the list.
        press(Key.DirectionUp)
        compose.settle()
        waitFor("UK: KITE KIDZ")
    }

    @Test
    fun `left into the categories works when the list shown isn't among them`() {
        homeTv()
        show(Screen.Live(ChannelFilter.Category("2")))
        compose.waitAdvancingClock { compose.focusedText().contains("UK: ARENA SPRTS 1 FHD") }
        // The category shown goes from the column (e.g. unticked elsewhere) while its list is still up.
        runBlocking { app.container.db.categories().setIncluded(playlistId, com.biskyt.myplexplayer.data.db.CAT_LIVE, listOf("2"), false) }
        compose.waitAdvancingClock {
            compose.onAllNodes(hasText("SPORT", substring = true) and hasClickAction()).fetchSemanticsNodes().isEmpty()
        }
        val before = compose.focusedText()
        press(Key.DirectionLeft)
        compose.settle()
        assertTrue("before: '$before'", compose.focusedText().isNotBlank())
    }

    @Test
    fun `left from the channel list goes to the selected category`() {
        homeTv()
        show(Screen.Live(ChannelFilter.Category("2")))
        compose.waitAdvancingClock { compose.focusedText().contains("UK: ARENA SPRTS 1 FHD") }
        press(Key.DirectionLeft)
        // The column scrolls to the selected category first when it's out of view, so focus can take a moment.
        // The column moves focus a frame later: let (simulated) time pass while waiting.
        compose.waitAdvancingClock(100) { compose.focusedText().contains("SPORT", ignoreCase = true) }
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
    fun `holding OK on a category offers to hide it`() {
        homeTv()
        show(Screen.Live())
        waitFor("UK: KITE KIDZ")
        compose.onNode(
            hasText("Sport", substring = true, ignoreCase = true) and hasClickAction()
        ).performSemanticsAction(SemanticsActions.OnLongClick)
        waitFor("Show it again in Settings")
        compose.onNode(hasText("Hide category")).performSemanticsAction(SemanticsActions.OnClick)
        compose.waitAdvancingClock {
            compose.onAllNodes(hasText("Sport", substring = true, ignoreCase = true) and hasClickAction()).fetchSemanticsNodes().isEmpty()
        }
        val included = runBlocking { app.container.db.categories().included(playlistId, com.biskyt.myplexplayer.data.db.CAT_LIVE) }
        assertTrue(included.none { it.categoryId == "2" })
        // Its channels go at once, and focus stays in the column.
        assertTrue(compose.onAllNodes(hasText("UK: ARENA SPRTS 1 FHD")).fetchSemanticsNodes().isEmpty())
        compose.settle()
        assertTrue(compose.focusedText(), compose.focusedText().isNotBlank())
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
        waitFor("Change guide source")
        compose.onNode(hasText("Remove from Recently watched")).performSemanticsAction(SemanticsActions.OnClick)
        compose.waitAdvancingClock { compose.onAllNodes(hasText("Recently watched")).fetchSemanticsNodes().isEmpty() }
    }

    /** Lets delayed work (e.g. a category switch after focus rests) happen. */

    @Test
    fun `guide sources can match channels without a guide, showing the result`() {
        homeTv()
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
        homeTv()
        show(Screen.GuideSources)
        waitFor("Your guides")
        compose.onNode(hasText("Add a guide")).performSemanticsAction(SemanticsActions.OnClick)
        waitFor("Free guides by country")
        waitFor("Your own XMLTV address")
    }

    @Test
    fun `the live player's More menu adds to favourites and opens the guide source`() {
        homePlex()
        val vm = com.biskyt.myplexplayer.ui.player.PlayerViewModel(
            app.container,
            app,
            Screen.Player(playlistId, plexChannel("1"), ChannelFilter.All)
        )
        compose.waitAdvancingClock { vm.menuActions().isNotEmpty() }
        val labels = vm.menuActions().map { it.label }
        assertEquals(listOf("Add to favourites", "Change guide source…"), labels)
        assertEquals(
            Screen.GuideMatching(playlistId, plexChannel("1")),
            (vm.menuActions().last() as com.biskyt.myplexplayer.ui.common.ItemAction.Open).screen
        )
        (vm.menuActions().first() as com.biskyt.myplexplayer.ui.common.ItemAction.Run).run()
        compose.waitAdvancingClock { vm.menuActions().first().label == "Remove from favourites" }
        vm.engine?.release()
    }

    @Test
    fun `first launch offers a choice and sets nothing up by itself`() {
        homePlex()
        show(Screen.Welcome)
        waitFor("Sign in to Plex")
        waitFor("Add my TV provider")
        waitFor("Restore a backup")
        compose.mainClock.advanceTimeBy(2_000)
        compose.waitForIdle()
        runBlocking { assertEquals(listOf(playlistId), app.container.playlists.allIds()) }
    }

    @Test
    fun `languages and categories can be filtered`() {
        homeTv()
        show(Screen.Categories(playlistId))
        waitFor("UK | ENTERTAINMENT")
        // The box opens for typing when pressed (OK on the remote).
        compose.onNode(hasText("Filter countries and categories")).performSemanticsAction(SemanticsActions.OnClick)
        compose.waitAdvancingClock { compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(hasSetTextAction()).performTextInput("cine")
        waitFor("FR: CINEMA")
        compose.waitAdvancingClock { compose.onAllNodes(hasText("UK | ENTERTAINMENT")).fetchSemanticsNodes().isEmpty() }
    }

    @Test
    fun `each list has its own select all and none, the languages' while filtering only the matching ones`() {
        homeTv()
        val dao = app.container.db.categories()
        fun included() = runBlocking { dao.included(playlistId, CAT_LIVE) }
        show(Screen.Categories(playlistId))
        waitFor("UK | ENTERTAINMENT")
        // The languages' None (the first): nothing at all.
        compose.onAllNodes(hasText("None") and hasClickAction())[0].performSemanticsAction(SemanticsActions.OnClick)
        compose.waitAdvancingClock { included().isEmpty() }

        // The categories' All: every category in the language shown, and no other language.
        waitFor("All in ")
        compose.onNode(hasText("All in ", substring = true) and hasClickAction()).performSemanticsAction(SemanticsActions.OnClick)
        compose.waitAdvancingClock { included().isNotEmpty() }
        val langs = included().map { it.lang }.toSet()
        assertEquals(1, langs.size)
        assertEquals(runBlocking { dao.all(playlistId, CAT_LIVE) }.count { it.lang in langs }, included().size)

        compose.onAllNodes(hasText("None") and hasClickAction())[0].performSemanticsAction(SemanticsActions.OnClick)
        compose.waitAdvancingClock { included().isEmpty() }
        compose.onNode(hasText("Filter countries and categories")).performSemanticsAction(SemanticsActions.OnClick)
        compose.waitAdvancingClock { compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(hasSetTextAction()).performTextInput("UK")
        waitFor("All matching")
        compose.onNode(hasText("All matching")).performSemanticsAction(SemanticsActions.OnClick)
        compose.waitAdvancingClock { included().map { it.name }.toSet() == setOf("UK | ENTERTAINMENT", "UK | SPORT") }

        compose.onNode(hasSetTextAction()).performTextClearance()
        waitFor("All")
        compose.onNode(hasText("All") and hasClickAction()).performSemanticsAction(SemanticsActions.OnClick)
        compose.waitAdvancingClock { included().size == 4 }
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

    @Test
    fun `guide matching opened for one channel lists only that channel`() {
        homeTv()
        show(Screen.GuideMatching(playlistId, 102))
        waitFor("Arena Sports 1 HD")
        waitFor("UK: ARENA SPRTS 1 FHD")
        assertTrue(compose.onAllNodes(hasText("UK: KITE KIDZ")).fetchSemanticsNodes().isEmpty())
        compose.onRoot().captureRoboImage("screenshots/guide-matching.png")
    }
}
