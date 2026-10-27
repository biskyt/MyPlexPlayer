package com.biskyt.myplexplayer.ui

import androidx.compose.runtime.remember
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
import com.biskyt.myplexplayer.data.ChannelFilter
import com.biskyt.myplexplayer.data.plexChannelId
import com.biskyt.myplexplayer.testutil.FakePlex
import com.biskyt.myplexplayer.testutil.Navigators
import com.biskyt.myplexplayer.testutil.TestApp
import com.biskyt.myplexplayer.testutil.addSyncedHomePlex
import com.biskyt.myplexplayer.testutil.createUiComposeRule
import com.biskyt.myplexplayer.testutil.focusedText
import com.biskyt.myplexplayer.testutil.waitAdvancingClock
import com.biskyt.myplexplayer.testutil.waitForText
import com.biskyt.myplexplayer.ui.theme.MyPlexTheme
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** What the guide says about a programme (its episode and description), and channel cards in search. */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestApp::class, qualifiers = "+w960dp-h540dp-tvdpi")
class ProgrammeInfoUiTest {
    @get:Rule val compose = createUiComposeRule()

    @get:Rule(order = Navigators.ORDER)
    val navigators = Navigators()

    private val plex = FakePlex()
    private val app = ApplicationProvider.getApplicationContext<TestApp>()
    private var source = 0L

    @Before
    fun setUp() = runBlocking {
        plex.channels = listOf(FakePlex.Channel("1", "Channel One"), FakePlex.Channel("2", "Two"))
        plex.programme = { i, n ->
            when {
                i == 0 && n == 0 -> FakePlex.Programme(
                    "Spring Planting",
                    show = "The Garden Planners",
                    season = 2,
                    episode = 5,
                    summary = "Two neighbours turn a bare yard into a summer garden."
                )
                n == 0 -> FakePlex.Programme("Now on ${plex.channels[i].title}")
                else -> FakePlex.Programme("Next on ${plex.channels[i].title}")
            }
        }
        source = app.container.addSyncedHomePlex(plex)
    }

    @After
    fun tearDown() = plex.close()

    private fun show(start: Screen) {
        val navigator = navigators.new(start)
        compose.setContent { MyPlexTheme { NavHost(remember { navigator }) { ScreenContent(it) } } }
    }

    private fun waitFor(text: String) = compose.waitForText(text, stepMs = 50)

    private fun count(text: String) = compose.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().size

    @Test
    fun `while watching, the controls tell of the programme, and About tells all of it`() {
        show(Screen.Player(source, plexChannelId("ch-1"), ChannelFilter.All))
        waitFor("Spring Planting · S2 E5")
        waitFor("Two neighbours turn a bare yard")
        compose.onAllNodes(hasText("About this programme") and hasClickAction())[0].performSemanticsAction(SemanticsActions.OnClick)
        // The panel too: the episode and description a second time.
        compose.waitAdvancingClock(stepMs = 50) { count("Two neighbours turn a bare yard") >= 2 && count("Spring Planting") >= 2 }
    }

    @Test
    fun `in the TV guide, a programme's description shows above the grid and in full in its options`() {
        show(Screen.Live(ChannelFilter.All, guide = true))
        waitFor("The Garden Planners")
        waitFor("Two neighbours turn a bare yard")
        // The options of the programme the highlight is on (the remote's Menu button, as holding OK).
        compose.waitAdvancingClock { compose.focusedText().contains("The Garden Planners") }
        compose.onRoot().performKeyInput { pressKey(Key.Menu) }
        waitFor("Spring Planting · S2 E5")
        compose.waitAdvancingClock(stepMs = 50) { count("Two neighbours turn a bare yard") >= 2 }
    }

    @Test
    fun `moving on in the TV guide, the last programme's description goes at once`() {
        show(Screen.Live(ChannelFilter.All, guide = true))
        waitFor("Two neighbours turn a bare yard")
        // Frames only: the next one's is read once the highlight has rested there a moment.
        compose.waitAdvancingClock { compose.focusedText().contains("The Garden Planners") }
        compose.mainClock.autoAdvance = false
        compose.onRoot().performKeyInput { pressKey(Key.DirectionRight) } // on to the next programme
        // A few frames for the press to land, far short of the 300 ms before a description is read.
        repeat(4) {
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle() // what each frame starts (not the clock: the next one's details wait on it)
        }
        assertEquals(0, count("Two neighbours turn a bare yard"))
        compose.mainClock.autoAdvance = true
    }

    @Test
    fun `channels found in search show what's on, as on Home`() {
        show(Screen.Search("Channel One"))
        waitFor("Channel One")
        waitFor("The Garden Planners")
        waitFor("then Next on Channel One")
    }
}
