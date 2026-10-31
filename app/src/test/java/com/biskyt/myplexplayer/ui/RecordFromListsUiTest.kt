package com.biskyt.myplexplayer.ui

import androidx.compose.runtime.remember
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
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
import com.biskyt.myplexplayer.testutil.topRoot
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

/** Recording with Plex's DVR from the channel list (what's on now) and from search results, with the Record button. */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestApp::class, qualifiers = "+w960dp-h540dp-tvdpi")
class RecordFromListsUiTest {
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

    private fun show(screen: Screen) {
        compose.setContent { MyPlexTheme { NavHost(remember { navigators.new(screen) }) { ScreenContent(it) } } }
    }

    private fun marked(description: String) =
        compose.onAllNodes(hasContentDescription(description), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun record() {
        compose.topRoot().performKeyInput { pressKey(Key.MediaRecord) }
        compose.idleFrames()
        compose.waitForText("Record", stepMs = 50)
        compose.onNode(hasText("Record") and hasClickAction()).performSemanticsAction(SemanticsActions.OnClick)
        compose.waitAdvancingClock(50) { plex.rules.isNotEmpty() }
    }

    @Test
    fun `in the channel list, the Record button records what's on now`() {
        show(Screen.Live(ChannelFilter.All))
        compose.waitAdvancingClock(50) { compose.focusedText().contains("One") && compose.focusedText().contains("Show 0") }
        record()
        assertEquals("plex://episode/ch-1-0", plex.rules.single().guid)
        // Once it records, the channel says so.
        plex.recordingNow += "plex://episode/ch-1-0"
        runBlocking { app.container.recordings.refresh(app.container.settings.current().activePlaylistId) }
        compose.waitAdvancingClock(50) { marked("Recording") }
    }

    @Test
    fun `in search results, the Record button records a programme`() {
        show(Screen.Search("Film 1"))
        // A programme result, not the search box with the same words in it.
        val programme = hasText("Film 1", substring = true) and hasClickAction() and !hasSetTextAction()
        compose.waitAdvancingClock(50) { compose.onAllNodes(programme).fetchSemanticsNodes().isNotEmpty() }
        val result = compose.onAllNodes(programme)[0]
        // The keyboard is up on arrival: the result takes the focus once it has gone.
        compose.waitAdvancingClock(50) {
            result.performSemanticsAction(SemanticsActions.RequestFocus)
            compose.focusedText().contains("Film 1")
        }
        record()
        assertEquals("plex://episode/ch-2-1", plex.rules.single().guid)
        compose.waitAdvancingClock(50) { marked("Set to record") }
    }
}
