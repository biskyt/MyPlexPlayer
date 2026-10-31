package com.biskyt.myplexplayer.ui

import androidx.compose.runtime.remember
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.test.core.app.ApplicationProvider
import com.biskyt.myplexplayer.data.ChannelFilter
import com.biskyt.myplexplayer.testutil.FakePlex
import com.biskyt.myplexplayer.testutil.Navigators
import com.biskyt.myplexplayer.testutil.TestApp
import com.biskyt.myplexplayer.testutil.addSyncedHomePlex
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
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Recording from the TV guide with Plex's DVR: the remote's Record button opens the programme's options, which record
 * it or its series (new airings only), mark it in the guide, and cancel.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestApp::class, qualifiers = "+w960dp-h540dp-tvdpi")
class RecordUiTest {
    @get:Rule val compose = createUiComposeRule()

    @get:Rule(order = Navigators.ORDER)
    val navigators = Navigators()

    private val plex = FakePlex()
    private val app = ApplicationProvider.getApplicationContext<TestApp>()

    @After
    fun tearDown() = plex.close()

    /** The guide, its highlight on channel One's programme now (an episode of Show 0). */
    private fun guide() {
        runBlocking { app.container.addSyncedHomePlex(plex) }
        compose.setContent {
            MyPlexTheme { NavHost(remember { navigators.new(Screen.Live(ChannelFilter.All, guide = true)) }) { ScreenContent(it) } }
        }
        compose.waitAdvancingClock { compose.focusedText() == "Show 0" }
    }

    private fun press(key: Key) {
        compose.onRoot().performKeyInput { pressKey(key) }
        compose.idleFrames()
    }

    private fun choose(text: String) {
        compose.waitForText(text, stepMs = 50)
        compose.onNode(hasText(text) and hasClickAction()).performSemanticsAction(SemanticsActions.OnClick)
    }

    private fun marked(description: String) =
        compose.onAllNodes(hasContentDescription(description), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun `the Record button opens the options, Record series records new airings, and the guide marks it`() {
        guide()
        press(Key.MediaRecord)
        choose("Record series")
        compose.waitAdvancingClock(50) { plex.rules.isNotEmpty() }
        val rule = plex.rules.single()
        assertEquals(2, rule.type)
        assertEquals("plex://show/Show-0", rule.guid)
        assertEquals("1", rule.prefs["onlyNewAirings"]) // the owner's default: new airings only
        assertEquals("false", rule.prefs["oneShot"]) // every setting is sent, hidden ones too
        compose.waitAdvancingClock(50) { marked("Series set to record") }

        // Set to record: its options cancel this airing, or the whole series.
        press(Key.MediaRecord)
        compose.waitForText("Stop recording the series", stepMs = 50)
        assertTrue(compose.onAllNodes(hasText("Record series")).fetchSemanticsNodes().isEmpty())
        choose("Stop recording the series")
        compose.waitAdvancingClock(50) { plex.rules.isEmpty() && !marked("Series set to record") }
    }

    @Test
    fun `Record sets this airing alone to record, and Cancel this recording removes it`() {
        guide()
        press(Key.MediaRecord)
        choose("Record")
        compose.waitAdvancingClock(50) { plex.rules.isNotEmpty() }
        assertEquals(4, plex.rules.single().type)
        assertEquals("true", plex.rules.single().prefs["oneShot"])
        compose.waitAdvancingClock(50) { marked("Set to record") }

        press(Key.MediaRecord)
        choose("Cancel this recording")
        compose.waitAdvancingClock(50) { plex.rules.isEmpty() && !marked("Set to record") }
    }

    @Test
    fun `a server that refuses recording says why, and nothing is marked`() {
        plex.recordingRefused = true
        guide()
        press(Key.MediaRecord)
        choose("Record")
        // (The notice shows in the app's corner, which this test doesn't draw.)
        compose.waitAdvancingClock(50) { app.container.notices.current.value != null }
        assertEquals("Only the server's owner can record", app.container.notices.current.value?.detail)
        assertTrue(plex.rules.isEmpty())
        assertTrue(!marked("Set to record"))
    }
}
