package com.biskyt.mytvplayer.ui

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasText
import com.biskyt.mytvplayer.data.ChannelFilter
import com.biskyt.mytvplayer.data.GuideStep
import com.biskyt.mytvplayer.data.SyncState
import com.biskyt.mytvplayer.testutil.TestApp
import com.biskyt.mytvplayer.testutil.createUiComposeRule
import com.biskyt.mytvplayer.testutil.waitAdvancingClock
import com.biskyt.mytvplayer.testutil.waitForText
import com.biskyt.mytvplayer.ui.common.SyncText
import com.biskyt.mytvplayer.ui.common.SyncToast
import com.biskyt.mytvplayer.ui.common.syncText
import com.biskyt.mytvplayer.ui.theme.MyTvTheme
import java.io.IOException
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Background updates in the corner of the screen: what they show, and when. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestApp::class, qualifiers = "+w960dp-h540dp-tvdpi")
class SyncToastUiTest {
    @get:Rule val compose = createUiComposeRule()

    private val updates = MutableStateFlow<SyncState>(SyncState.Idle)

    private fun show(screen: Screen) = compose.setContent { MyTvTheme { SyncToast(Navigator(screen), updates = updates) } }

    private fun shown(text: String) = compose.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun `an update shows what it's doing and how far along, and nothing once it's done`() {
        show(Screen.Home)
        assertTrue(!shown("Updating"))
        updates.value = SyncState.Guide(GuideStep.Downloading("Free TV guide", 0.4f))
        compose.waitForText("Updating the guide…")
        compose.waitForText("Downloading Free TV guide…")
        // It is never somewhere focus can go.
        assertTrue(
            compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.RequestFocus)).fetchSemanticsNodes().isEmpty()
        )
        updates.value = SyncState.Idle
        compose.waitAdvancingClock(stepMs = 0) { !shown("Updating") }
    }

    @Test
    fun `a failure shows for a while, then goes`() {
        show(Screen.Home)
        updates.value = SyncState.Failed(IOException("offline"), com.biskyt.mytvplayer.R.string.data_refresh_failed)
        compose.waitForText("The last update failed")
        compose.waitAdvancingClock(stepMs = 1_000) { !shown("The last update failed") }
    }

    @Test
    fun `a failure already shown doesn't come back after watching something`() {
        val nav = Navigator(Screen.Home)
        compose.setContent { MyTvTheme { SyncToast(nav, updates = updates) } }
        updates.value = SyncState.Failed(IOException("offline"), com.biskyt.mytvplayer.R.string.data_refresh_failed)
        compose.waitForText("The last update failed")
        compose.waitAdvancingClock(stepMs = 1_000) { !shown("The last update failed") }
        compose.runOnUiThread { nav.push(Screen.Player(1, 101, ChannelFilter.All)) }
        compose.waitForIdle()
        compose.runOnUiThread { nav.pop() }
        compose.waitAdvancingClock(stepMs = 1_000) { true }
        assertTrue(!shown("The last update failed"))
    }

    @Test
    fun `nothing shows over playback`() {
        updates.value = SyncState.Channels("Home TV", 1_234)
        show(Screen.Player(1, 101, ChannelFilter.All))
        compose.waitForIdle()
        assertTrue(!shown("Updating channels"))
    }

    @Test
    fun `each update in words`() {
        assertEquals(SyncText("Updating channels…", "Home TV"), syncText(SyncState.Channels("Home TV", 0)))
        assertEquals(SyncText("Updating movies and series…", "Home TV: 1,234 loaded"), syncText(SyncState.Vod("Home TV", 1_234)))
        assertEquals(
            SyncText("Updating the guide…", "Matching channels… 5 of 20", 0.25f),
            syncText(SyncState.Guide(GuideStep.Matching(5, 20)))
        )
        assertEquals("Loading programmes from the guide…", syncText(SyncState.Guide(GuideStep.Loading(null)))!!.detail)
        assertEquals(null, syncText(SyncState.Idle))
        // A playlist gone meanwhile: no empty line.
        assertEquals(null, syncText(SyncState.Channels("", 0))!!.detail)
    }
}
