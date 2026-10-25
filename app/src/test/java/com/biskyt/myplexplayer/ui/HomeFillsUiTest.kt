package com.biskyt.myplexplayer.ui

import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.test.core.app.ApplicationProvider
import com.biskyt.myplexplayer.testutil.FakePlex
import com.biskyt.myplexplayer.testutil.Navigators
import com.biskyt.myplexplayer.testutil.TestApp
import com.biskyt.myplexplayer.testutil.addHomePlex
import com.biskyt.myplexplayer.testutil.createUiComposeRule
import com.biskyt.myplexplayer.testutil.focusedText
import com.biskyt.myplexplayer.testutil.waitAdvancingClock
import com.biskyt.myplexplayer.ui.theme.MyPlexTheme
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Home open before the first sync: as it fills, focus moves from the hint (which goes) to the new row on top. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestApp::class, qualifiers = "+w960dp-h540dp-tvdpi")
class HomeFillsUiTest {
    @get:Rule val compose = createUiComposeRule()

    @get:Rule(order = Navigators.ORDER)
    val navigators = Navigators()

    private val plex = FakePlex()
    private val app = ApplicationProvider.getApplicationContext<TestApp>()
    private var source = 0L

    @Before
    fun setUp() {
        runBlocking { source = app.container.addHomePlex(plex) }
    }

    @After
    fun tearDown() = plex.close()

    @Test
    fun `focus moves to the newest series once the first sync fills Home`() {
        val navigator = navigators.new(Screen.Home)
        compose.setContent { MyPlexTheme { NavHost(navigator) { ScreenContent(it) } } }
        // Nothing yet: the hint to add favourite channels, focused.
        compose.waitAdvancingClock(stepMs = 50) {
            compose.onAllNodes(hasText("Browse channels") and isFocused()).fetchSemanticsNodes().isNotEmpty()
        }

        runBlocking { app.container.vod.sync(app.container.db.playlists().get(source)!!) }

        compose.waitAdvancingClock(stepMs = 50) { "Test Show" in compose.focusedText() }
    }
}
