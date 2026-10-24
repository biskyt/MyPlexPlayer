package com.biskyt.mytvplayer.ui.settings

import androidx.compose.runtime.remember
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.core.app.ApplicationProvider
import com.biskyt.mytvplayer.testutil.FakeProvider
import com.biskyt.mytvplayer.testutil.Navigators
import com.biskyt.mytvplayer.testutil.TestApp
import com.biskyt.mytvplayer.testutil.addHomeTv
import com.biskyt.mytvplayer.testutil.createUiComposeRule
import com.biskyt.mytvplayer.testutil.waitAdvancingClock
import com.biskyt.mytvplayer.testutil.waitForText
import com.biskyt.mytvplayer.ui.NavHost
import com.biskyt.mytvplayer.ui.Screen
import com.biskyt.mytvplayer.ui.ScreenContent
import com.biskyt.mytvplayer.ui.theme.MyTvTheme
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

    private val provider = FakeProvider()
    private val app = ApplicationProvider.getApplicationContext<TestApp>()

    @Before
    fun setUp() = runBlocking {
        val c = app.container
        val id = c.addHomeTv(provider)
        c.playlists.get(id)?.let { c.epg.ensureProviderSource(it, c.playlists.client(it).xmltvUrl()) }
        Unit
    }

    @After
    fun tearDown() = provider.close()

    private fun updateNow() {
        compose.setContent { MyTvTheme { NavHost(remember { navigators.new(Screen.Playlists) }) { ScreenContent(it) } } }
        compose.waitAdvancingClock { compose.onAllNodes(hasText("Update now") and hasClickAction()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(hasText("Update now") and hasClickAction()).performSemanticsAction(SemanticsActions.OnClick)
    }

    private fun waitFor(text: String) = compose.waitForText(text, stepMs = 50)

    @Test
    fun `update now says when the playlist is up to date`() {
        updateNow()
        waitFor("Home TV updated")
    }

    @Test
    fun `update now says when the playlist couldn't be updated`() {
        provider.close() // the provider is gone
        updateNow()
        waitFor("Couldn't update Home TV")
    }

    @Test
    fun `update now says when the playlist updated but its guide didn't`() {
        provider.guideFails = true
        updateNow()
        waitFor("Home TV updated, but some guides couldn't be loaded")
    }
}
