package com.biskyt.myplexplayer.ui

import android.content.pm.PackageManager
import androidx.compose.runtime.remember
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.printToString
import androidx.test.core.app.ApplicationProvider
import com.biskyt.myplexplayer.data.ChannelFilter
import com.biskyt.myplexplayer.data.plexChannelId
import com.biskyt.myplexplayer.testutil.FakePlex
import com.biskyt.myplexplayer.testutil.Navigators
import com.biskyt.myplexplayer.testutil.TestApp
import com.biskyt.myplexplayer.testutil.addSyncedHomePlex
import com.biskyt.myplexplayer.testutil.createUiComposeRule
import com.biskyt.myplexplayer.testutil.waitAdvancingClock
import com.biskyt.myplexplayer.ui.theme.MyPlexTheme
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** A phone or tablet: taps and long presses instead of the remote. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestApp::class, qualifiers = "+w960dp-h540dp-tvdpi")
class TouchUiTest {
    @get:Rule val compose = createUiComposeRule()

    @get:Rule(order = Navigators.ORDER)
    val navigators = Navigators()

    private val plex = FakePlex()
    private val app = ApplicationProvider.getApplicationContext<TestApp>()
    private var source = 0L
    private lateinit var navigator: Navigator

    @Before
    fun setUp() = runBlocking {
        shadowOf(app.packageManager).setSystemFeature(PackageManager.FEATURE_TOUCHSCREEN, true)
        source = app.container.addSyncedHomePlex(plex)
    }

    @After
    fun tearDown() = plex.close()

    private fun show(start: Screen) {
        navigator = navigators.new(start)
        compose.setContent { MyPlexTheme { NavHost(remember { navigator }) { ScreenContent(it) } } }
    }

    private fun waitFor(matcher: androidx.compose.ui.test.SemanticsMatcher, what: String) = try {
        compose.waitAdvancingClock(100) { compose.onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
    } catch (e: androidx.compose.ui.test.ComposeTimeoutException) {
        throw AssertionError("$what not shown. Screen:\n" + compose.onRoot(useUnmergedTree = true).printToString(), e)
    }

    private fun gone(matcher: androidx.compose.ui.test.SemanticsMatcher) =
        compose.waitAdvancingClock(100) { compose.onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes().isEmpty() }

    @Test
    fun `a tap presses a button and a long press opens a channel's options`() {
        show(Screen.Welcome)
        waitFor(hasText("Sign in to Plex"), "Welcome")
        compose.onNode(hasText("Sign in to Plex")).performTouchInput { click() }
        compose.waitAdvancingClock { navigator.current.screen != Screen.Welcome }
        assertEquals(Screen.PlexSignIn(firstRun = true), navigator.current.screen)

        compose.runOnUiThread { navigator.reset(Screen.Live()) }
        waitFor(hasText("One"), "the channel list")
        compose.onNode(hasText("One")).performTouchInput { longClick() }
        waitFor(hasText("Add to favourites"), "the channel's options")
    }

    @Test
    fun `on the player a tap shows or hides the controls, and Back at the top goes back to the list`() {
        show(Screen.Live())
        compose.runOnUiThread { navigator.push(Screen.Player(source, plexChannelId("ch-1"), ChannelFilter.All)) }
        // The controls are up as playback starts, with Back in the corner on a touch screen.
        waitFor(hasContentDescription("Back"), "Back")
        // The channel's own controls come once it's been looked up.
        waitFor(hasContentDescription("Skip back"), "the controls")
        compose.onRoot().performTouchInput { click(center) }
        gone(hasContentDescription("Skip back"))
        compose.onRoot().performTouchInput { click(center) }
        waitFor(hasContentDescription("Skip back"), "the controls")

        compose.onNode(hasContentDescription("Back")).performTouchInput { click() }
        compose.waitAdvancingClock { navigator.current.screen !is Screen.Player }
        assertEquals(Screen.Live(), navigator.current.screen)
    }

    @Test
    fun `the TV guide has buttons to move later and earlier`() {
        show(Screen.Live(guide = true))
        waitFor(hasText("Later ▶"), "Later")
        // The header starts at the current half hour; moving later leaves its first half hours behind, and earlier
        // brings them back. Checked on its second time: the first is also the clock's for a minute (e.g. 22:00).
        val halfHour = 30 * 60_000L
        val second = com.biskyt.myplexplayer.ui.common.formatTime(System.currentTimeMillis() / halfHour * halfHour + halfHour)
        waitFor(hasText(second), "the time $second")
        val before = navigator.current.screen
        compose.onNode(hasText("Later ▶")).performTouchInput { click() }
        gone(hasText(second))
        compose.onNode(hasText("◀ Earlier")).performTouchInput { click() }
        waitFor(hasText(second), "the time $second again")
        assertEquals(before, navigator.current.screen)
    }
}
