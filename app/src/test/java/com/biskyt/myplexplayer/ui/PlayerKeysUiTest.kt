package com.biskyt.myplexplayer.ui

import androidx.compose.runtime.remember
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.test.core.app.ApplicationProvider
import com.biskyt.myplexplayer.data.ChannelFilter
import com.biskyt.myplexplayer.data.plexChannelId
import com.biskyt.myplexplayer.testutil.FakePlex
import com.biskyt.myplexplayer.testutil.Navigators
import com.biskyt.myplexplayer.testutil.TestApp
import com.biskyt.myplexplayer.testutil.addSyncedHomePlex
import com.biskyt.myplexplayer.testutil.createUiComposeRule
import com.biskyt.myplexplayer.testutil.topRoot
import com.biskyt.myplexplayer.testutil.waitAdvancingClock
import com.biskyt.myplexplayer.testutil.waitForText
import com.biskyt.myplexplayer.ui.theme.MyPlexTheme
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Remote buttons on the player and the live screen, through the app's own screens. */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestApp::class, qualifiers = "+w960dp-h540dp-tvdpi")
class PlayerKeysUiTest {
    @get:Rule val compose = createUiComposeRule()

    @get:Rule(order = Navigators.ORDER)
    val navigators = Navigators()

    private val plex = FakePlex()
    private val app = ApplicationProvider.getApplicationContext<TestApp>()
    private var source = 0L
    private lateinit var navigator: Navigator

    /** Channel One, the first. */
    private val one = plexChannelId("ch-1")

    @Before
    fun setUp() = runBlocking {
        // Three channels, the second with no guide.
        plex.channels = listOf(FakePlex.Channel("1", "One"), FakePlex.Channel("2", "Two"), FakePlex.Channel("3", "Three"))
        plex.programme = { i, n ->
            if (i == 1) null else FakePlex.Programme((if (n == 0) "Now on " else "Next on ") + plex.channels[i].title)
        }
        plex.liveTrickles = true // never fails, so no retry plays it again
        source = app.container.addSyncedHomePlex(plex)
    }

    @After
    fun tearDown() = plex.close()

    private fun show(start: Screen) {
        navigator = navigators.new(start)
        compose.setContent { MyPlexTheme { NavHost(remember { navigator }) { ScreenContent(it) } } }
    }

    private fun waitFor(text: String) = compose.waitForText(text)

    private fun waitGone(text: String) =
        compose.waitAdvancingClock { compose.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isEmpty() }

    /** The text and descriptions of what has focus. */
    private fun focused(): String = compose.onAllNodes(isFocused()).fetchSemanticsNodes().joinToString(" | ") { node ->
        (
            node.config.getOrNull(
                SemanticsProperties.Text
            ).orEmpty() + node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
            )
            .joinToString()
    }

    /** A remote button, to the topmost window (a panel over the video is a window of its own). */
    private fun press(key: Key) {
        compose.topRoot().performKeyInput { pressKey(key) }
        compose.waitForIdle()
    }

    private fun waitForFocus(text: String) = try {
        compose.waitAdvancingClock { focused().contains(text) }
    } catch (e: androidx.compose.ui.test.ComposeTimeoutException) {
        throw AssertionError("'$text' hasn't focus: ${focused()}", e)
    }

    /** The remote's Options button brings up the controls on More; OK opens it. */
    private fun openMore() {
        press(Key.Menu)
        waitForFocus("More")
        press(Key.DirectionCenter)
        waitFor("Playback info")
    }

    @Test
    fun `the controls come up with focus on play-pause, and up goes back to the video`() {
        show(Screen.Player(source, one, ChannelFilter.All))
        waitFor("Channel guide")
        compose.waitAdvancingClock { focused().contains("Pause") || focused().contains("Play") }
        press(Key.DirectionUp)
        assertTrue(focused(), !focused().contains("Pause") && !focused().contains("Play"))
    }

    @Test
    fun `live tv's controls say how long the programme has left, with no key hints`() {
        show(Screen.Player(source, one, ChannelFilter.All))
        waitFor("Channel guide")
        waitFor(" left")
        assertTrue(compose.onAllNodes(hasText("Back hides this", substring = true)).fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun `options again puts focus back on more, and takes the place of the subtitles panel`() {
        show(Screen.Player(source, one, ChannelFilter.All))
        waitFor("Channel guide")
        press(Key.Menu)
        waitForFocus("More")
        press(Key.DirectionLeft)
        waitForFocus("Subtitles")
        press(Key.Menu)
        waitForFocus("More")
        press(Key.DirectionLeft)
        waitForFocus("Subtitles")
        press(Key.DirectionCenter) // the Subtitles panel
        waitFor("Off")
        press(Key.Menu)
        waitGone("Off")
        waitForFocus("More")
    }

    @Test
    fun `the media buttons work with a panel open over the video`() {
        show(Screen.Live())
        compose.runOnIdle { navigator.push(Screen.Player(source, one, ChannelFilter.All)) }
        waitFor("Channel guide")
        openMore()
        press(Key.MediaFastForward)
        // The panel closes and the player answers (live TV here can't skip).
        waitFor("Skipping isn't available here")
        waitGone("Playback info")
        openMore()
        press(Key.MediaStop)
        compose.waitAdvancingClock { navigator.current.screen is Screen.Live } // stopped, back where it was opened from
    }

    @Test
    fun `the channel guide previews channel after channel, whatever their programme times`() {
        // Its programmes start at other times than channel one's: the one focused there isn't in this list.
        plex.guideShiftMin = mapOf("ch-3" to 10)
        runBlocking { app.container.sync.refreshNow(source).getOrThrow() }
        show(Screen.Player(source, one, ChannelFilter.All))
        waitFor("Channel guide")
        press(Key.Guide)
        waitForFocus("Now on One")
        press(Key.DirectionLeft) // round to the last channel, Three
        waitFor("Now on Three")
        press(Key.DirectionLeft)
        waitFor("Two") // which has no guide
        press(Key.DirectionLeft)
        waitFor("Now on One")
    }

    @Test
    fun `the guide button with a panel open over the video opens the channel guide`() {
        show(Screen.Player(source, one, ChannelFilter.All))
        waitFor("Channel guide")
        openMore()
        press(Key.Guide)
        // The panel gives way to the channel guide, as with no panel open (not the TV's own guide).
        // Its hint for the remote, or on a touch screen its buttons.
        compose.waitAdvancingClock {
            compose.onAllNodes(hasText("other channels", substring = true) or hasText("Previous", substring = true))
                .fetchSemanticsNodes().isNotEmpty()
        }
        assertTrue(compose.onAllNodes(hasText("Playback info", substring = true)).fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun `the guide and menu buttons close the panels they open`() {
        show(Screen.Player(source, one, ChannelFilter.All))
        waitFor("Channel guide")
        press(Key.Guide)
        waitForFocus("Now on One")
        press(Key.Guide)
        waitGone("Now on One")
        openMore()
        press(Key.Menu)
        waitGone("Playback info")
        assertTrue(navigator.current.screen is Screen.Player) // still playing
    }

    @Test
    fun `playback info takes focus from the More panel, and the menu button closes it`() {
        show(Screen.Player(source, one, ChannelFilter.All))
        waitFor("Channel guide")
        openMore()
        compose.onNode(hasText("Playback info") and hasClickAction()).performSemanticsAction(SemanticsActions.OnClick)
        waitFor("Video:")
        // Media buttons reach the panel, so it has focus.
        press(Key.MediaFastForward)
        waitFor("Skipping isn't available here")
        openMore()
        compose.onNode(hasText("Playback info") and hasClickAction()).performSemanticsAction(SemanticsActions.OnClick)
        waitFor("Video:")
        press(Key.Menu)
        waitGone("Video:")
        waitForFocus("More")
        assertTrue(compose.onAllNodes(hasText("Playback info")).fetchSemanticsNodes().isEmpty()) // not back to the panel
    }

    @Test
    fun `menu with the channel guide open brings up the controls on More in its place`() {
        show(Screen.Player(source, one, ChannelFilter.All))
        waitFor("Channel guide")
        press(Key.Guide)
        waitForFocus("Now on One")
        press(Key.Menu)
        waitForFocus("More")
        waitGone("Now on One")
        assertTrue("the panel isn't open yet", compose.onAllNodes(hasText("Playback info")).fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun `the guide button works on the live screen with focus on the tabs`() {
        show(Screen.Live())
        waitForFocus("One")
        press(Key.DirectionUp) // the filter box
        press(Key.DirectionUp) // the tabs
        assertTrue(focused(), listOf("Home", "Live TV", "Movies", "Series").any { focused().contains(it) })
        press(Key.Guide)
        waitFor("No guide information")
    }

    @Test
    fun `up from the guide's first row goes to the filter box`() {
        show(Screen.Live(guide = true))
        waitForFocus("Now on One")
        press(Key.DirectionDown)
        press(Key.DirectionUp)
        waitForFocus("on One") // back on the first row
        press(Key.DirectionUp)
        waitForFocus("Filter by name or number")
    }
}
