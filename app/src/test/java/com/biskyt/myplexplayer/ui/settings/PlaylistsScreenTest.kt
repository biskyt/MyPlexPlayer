package com.biskyt.myplexplayer.ui.settings

import androidx.compose.runtime.remember
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.core.app.ApplicationProvider
import com.biskyt.myplexplayer.testutil.FakePlex
import com.biskyt.myplexplayer.testutil.Navigators
import com.biskyt.myplexplayer.testutil.TestApp
import com.biskyt.myplexplayer.testutil.addSyncedHomePlex
import com.biskyt.myplexplayer.testutil.createUiComposeRule
import com.biskyt.myplexplayer.testutil.waitAdvancingClock
import com.biskyt.myplexplayer.testutil.waitForText
import com.biskyt.myplexplayer.ui.NavHost
import com.biskyt.myplexplayer.ui.Screen
import com.biskyt.myplexplayer.ui.ScreenContent
import com.biskyt.myplexplayer.ui.theme.MyPlexTheme
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Settings › Playlists: Update now shows how the update is going, then how it went. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestApp::class, qualifiers = "+w960dp-h540dp-tvdpi")
class PlaylistsScreenTest {
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

    private fun updateNow() {
        compose.setContent { MyPlexTheme { NavHost(remember { navigators.new(Screen.Playlists) }) { ScreenContent(it) } } }
        compose.waitAdvancingClock { compose.onAllNodes(hasText("Update now") and hasClickAction()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(hasText("Update now") and hasClickAction()).performSemanticsAction(SemanticsActions.OnClick)
    }

    private fun waitFor(text: String) = compose.waitForText(text, stepMs = 50)

    @Test
    fun `update now says when the playlist is up to date`() {
        updateNow()
        waitFor("Home Plex updated")
    }

    @Test
    fun `update now says when the playlist couldn't be updated`() {
        plex.close() // the server is gone
        updateNow()
        waitFor("Couldn't update Home Plex")
    }

    @Test
    fun `update now says when the playlist updated but its guide didn't`() {
        plex.guideFails = true
        updateNow()
        waitFor("Home Plex updated, but some guides couldn't be loaded")
    }
}
