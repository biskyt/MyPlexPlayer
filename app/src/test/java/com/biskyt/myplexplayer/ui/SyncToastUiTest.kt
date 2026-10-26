package com.biskyt.myplexplayer.ui

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasText
import com.biskyt.myplexplayer.data.ChannelFilter
import com.biskyt.myplexplayer.data.SyncState
import com.biskyt.myplexplayer.testutil.TestApp
import com.biskyt.myplexplayer.testutil.createUiComposeRule
import com.biskyt.myplexplayer.testutil.waitAdvancingClock
import com.biskyt.myplexplayer.testutil.waitForText
import com.biskyt.myplexplayer.ui.common.SyncText
import com.biskyt.myplexplayer.ui.common.SyncToast
import com.biskyt.myplexplayer.ui.common.syncText
import com.biskyt.myplexplayer.ui.theme.MyPlexTheme
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

    private fun show(screen: Screen) = compose.setContent { MyPlexTheme { SyncToast(Navigator(screen), updates = updates) } }

    private fun shown(text: String) = compose.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun `an update shows what it's doing and how far it has got, and nothing once it's done`() {
        show(Screen.Home)
        assertTrue(!shown("Updating"))
        updates.value = SyncState.Vod("Home Plex", 1_234)
        compose.waitForText("Updating movies and series…")
        compose.waitForText("Home Plex: 1,234 loaded")
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
        updates.value = SyncState.Failed(IOException("offline"), com.biskyt.myplexplayer.R.string.data_refresh_failed)
        compose.waitForText("The last update failed")
        compose.waitAdvancingClock(stepMs = 1_000) { !shown("The last update failed") }
    }

    @Test
    fun `a failure already shown doesn't come back after watching something`() {
        val nav = Navigator(Screen.Home)
        compose.setContent { MyPlexTheme { SyncToast(nav, updates = updates) } }
        updates.value = SyncState.Failed(IOException("offline"), com.biskyt.myplexplayer.R.string.data_refresh_failed)
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
        assertEquals(null, syncText(SyncState.Idle))
        // A playlist gone meanwhile: no empty line.
        assertEquals(null, syncText(SyncState.Channels("", 0))!!.detail)
    }
}
