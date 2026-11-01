package com.biskyt.myplexplayer.ui

import androidx.compose.runtime.remember
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.test.core.app.ApplicationProvider
import com.biskyt.myplexplayer.testutil.FakePlex
import com.biskyt.myplexplayer.testutil.Navigators
import com.biskyt.myplexplayer.testutil.TestApp
import com.biskyt.myplexplayer.testutil.addSyncedHomePlex
import com.biskyt.myplexplayer.testutil.createUiComposeRule
import com.biskyt.myplexplayer.testutil.focusedText
import com.biskyt.myplexplayer.testutil.idleFrames
import com.biskyt.myplexplayer.testutil.topRoot
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

/** The DVR tab: last in the menu; what's recording, coming up and set to record as a series, and changing them. */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestApp::class, qualifiers = "+w960dp-h540dp-tvdpi")
class DvrUiTest {
    @get:Rule val compose = createUiComposeRule()

    @get:Rule(order = Navigators.ORDER)
    val navigators = Navigators()

    private val plex = FakePlex()
    private val app = ApplicationProvider.getApplicationContext<TestApp>()

    @Before
    fun setUp() = runBlocking {
        app.container.addSyncedHomePlex(plex)
        Unit
    }

    @After
    fun tearDown() = plex.close()

    private fun show() {
        compose.setContent { MyPlexTheme { NavHost(remember { navigators.new(Screen.Dvr) }) { ScreenContent(it) } } }
    }

    /** Opens the options of the row that says [text], and chooses [option]. */
    private fun choose(text: String, option: String) {
        compose.onAllNodes(hasText(text, substring = true) and hasClickAction())[0].performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForText(option, stepMs = 50)
        compose.onNode(hasText(option) and hasClickAction()).performSemanticsAction(SemanticsActions.OnClick)
    }

    private fun left(text: String) = compose.onNode(hasText(text) and hasClickAction()).fetchSemanticsNode().boundsInRoot.left

    @Test
    fun `with nothing set to record, it says how to record, and the tab is last in the menu`() {
        show()
        compose.waitForText("Nothing is set to record", stepMs = 50)
        assertTrue(left("DVR") > left("Live TV"))
    }

    @Test
    fun `it lists what's recording, what's coming up and the series, and cancels one airing`() {
        // Show 0's series, recording now on channel One; Film 1 alone, coming up on Two.
        plex.rules += FakePlex.Rule("1", 2, "plex://show/Show-0", mapOf("onlyNewAirings" to "1"))
        plex.rules += FakePlex.Rule("2", 4, "plex://episode/ch-2-1", emptyMap())
        plex.recordingNow += "plex://episode/ch-1-0"
        show()
        compose.waitForText("Recording now", stepMs = 50)
        // The focus starts on the first row.
        compose.waitAdvancingClock(50) { compose.focusedText().contains("Show 0") }
        compose.waitForText("Coming up", stepMs = 50)
        compose.waitForText("Series recordings", stepMs = 50)
        compose.waitForText("Film 1", stepMs = 50)
        compose.waitForText("Nothing to record yet", stepMs = 50) // its one airing is recording now

        choose("Film 1", "Cancel this recording")
        compose.waitAdvancingClock(50) { plex.rules.none { it.key == "2" } }
        compose.waitAdvancingClock(50) { compose.onAllNodes(hasText("Film 1")).fetchSemanticsNodes().isEmpty() }
    }

    @Test
    fun `a recording waiting for its programme to be in the guide is listed, says so, and cancels`() {
        plex.rules += FakePlex.Rule("1", 4, "plex://episode/not-in-the-guide", emptyMap())
        show()
        compose.waitForText("Other recordings", stepMs = 50)
        compose.waitForText("Waiting for it to be in the TV guide", stepMs = 50)
        choose("Waiting for it", "Cancel this recording")
        compose.waitAdvancingClock(50) { plex.rules.isEmpty() }
        compose.waitForText("Nothing is set to record", stepMs = 50)
    }

    @Test
    fun `a series records new airings only or repeats too, and stops`() {
        plex.rules += FakePlex.Rule("1", 2, "plex://show/Show-0", mapOf("onlyNewAirings" to "1"))
        show()
        compose.waitForText("Series recordings", stepMs = 50)
        // The series' row, not its airing's under Coming up.
        compose.onAllNodes(
            hasText("airing to come", substring = true) and hasClickAction()
        )[0].performSemanticsAction(SemanticsActions.OnClick)
        // As Plex's list of rules says (it has no settings, only the airings each records).
        compose.waitAdvancingClock(50) { compose.focusedText().let { "Episodes" in it && "New only" in it } }
        // Right steps the choice on: new and repeats.
        compose.topRoot().performKeyInput { pressKey(Key.DirectionRight) }
        compose.idleFrames()
        compose.waitAdvancingClock(50) { plex.rules.single().prefs["onlyNewAirings"] == "0" }
        compose.waitAdvancingClock(50) { "New and repeats" in compose.focusedText() }

        compose.onNode(hasText("Stop recording the series") and hasClickAction()).performSemanticsAction(SemanticsActions.OnClick)
        compose.waitAdvancingClock(50) { plex.rules.isEmpty() }
        compose.waitForText("Nothing is set to record", stepMs = 50)
        assertEquals(0, plex.rules.size)
    }
}
