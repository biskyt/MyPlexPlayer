package com.biskyt.myplexplayer.ui.phone

import android.content.pm.PackageManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasScrollToKeyAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToKey
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import com.biskyt.myplexplayer.data.ChannelFilter
import com.biskyt.myplexplayer.testutil.FakePlex
import com.biskyt.myplexplayer.testutil.Navigators
import com.biskyt.myplexplayer.testutil.TestApp
import com.biskyt.myplexplayer.testutil.addSyncedHomePlex
import com.biskyt.myplexplayer.testutil.createUiComposeRule
import com.biskyt.myplexplayer.testutil.plexChannel
import com.biskyt.myplexplayer.testutil.waitAdvancingClock
import com.biskyt.myplexplayer.testutil.waitForText
import com.biskyt.myplexplayer.ui.NavHost
import com.biskyt.myplexplayer.ui.Screen
import com.biskyt.myplexplayer.ui.ScreenContent
import com.biskyt.myplexplayer.ui.theme.MyPlexTheme
import com.biskyt.myplexplayer.ui.theme.Palette
import com.biskyt.myplexplayer.ui.theme.autoFindsTv
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Touch on the phone layouts (the owner, 2026-11-25): what a long press does, and rows that share a channel. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestApp::class, qualifiers = "w390dp-h844dp-xxhdpi")
class PhoneTouchUiTest {
    @get:Rule val compose = createUiComposeRule()

    @get:Rule(order = Navigators.ORDER)
    val navigators = Navigators()

    private val plex = FakePlex()
    private val app = ApplicationProvider.getApplicationContext<TestApp>()
    private var pid = 0L

    @Before
    fun setUp() = runBlocking {
        shadowOf(app.packageManager).setSystemFeature(PackageManager.FEATURE_TOUCHSCREEN, true)
        autoFindsTv = { false } // the phone layout (TestApp sets the TV's)
        pid = app.container.addSyncedHomePlex(plex)
    }

    @After
    fun tearDown() = plex.close()

    private fun show(screen: Screen) {
        val navigator = navigators.new(screen)
        compose.setContent {
            MyPlexTheme {
                Box(Modifier.fillMaxSize().background(Palette.background)) { NavHost(remember { navigator }) { ScreenContent(it) } }
            }
        }
    }

    @Test
    fun `a favourite channel that was also watched lately shows in both rows of Home`() {
        runBlocking {
            app.container.channels.setFavourite(pid, plexChannel("1"), true)
            app.container.channels.markWatched(pid, plexChannel("1"))
        }
        show(Screen.Home)
        compose.waitForText("Recently watched", stepMs = 50)
        // Live TV is last, under the films' and series' rows: the page scrolls down to it.
        compose.onNode(hasScrollToKeyAction() and hasAnyDescendant(hasText("Recently watched")))
            .performScrollToKey("recent-${plexChannel("1")}")
        compose.waitAdvancingClock(stepMs = 50) {
            compose.onAllNodes(hasText("One")).fetchSemanticsNodes().size == 2
        }
    }

    @Test
    fun `Home has the films added lately, and a tap on one opens its page`() {
        show(Screen.Home)
        compose.waitForText("Recently added movies", stepMs = 50)
        compose.onNode(hasText("Big Buck Bunny") and hasClickAction()).performClick()
        compose.waitForText("A giant rabbit takes on three bullies.", stepMs = 50)
    }

    @Test
    fun `hiding a category that is not shown leaves the list shown as it is`() {
        show(Screen.Live(ChannelFilter.All))
        compose.waitForText("One", stepMs = 50)
        // The chips scroll sideways: the row is brought to the category's chip first.
        val category = hasText("No category") and hasClickAction()
        compose.onNode(hasScrollAction() and hasAnyDescendant(hasText("All channels"))).performScrollToNode(category)
        compose.onNode(category).performTouchInput { longClick() }
        compose.waitForText("Hide category", stepMs = 50)
        compose.onNode(hasText("Hide category") and hasClickAction()).performClick()
        compose.waitAdvancingClock(stepMs = 50) { compose.onAllNodes(hasText("No category")).fetchSemanticsNodes().isEmpty() }
        assertTrue(compose.onAllNodes(hasText("All channels") and isSelected()).fetchSemanticsNodes().isNotEmpty())
    }

    @Test
    fun `the films have the TV's Category, Filter and Sort pills`() {
        show(Screen.Movies())
        compose.waitForText("Big Buck Bunny", stepMs = 50)
        compose.onNode(hasText("Filter", substring = true) and hasClickAction()).performClick()
        compose.waitForText("Unwatched", stepMs = 50)
    }

    @Test
    fun `Search is drawn for touch, with the phone's tab bar (every screen has a phone layout)`() {
        show(Screen.Search())
        compose.waitForText("Movies", stepMs = 50)
        // Only the phone's tab bar has Search in words (the TV's top bar has its icon).
        assertTrue(compose.onAllNodes(hasText("Search")).fetchSemanticsNodes().isNotEmpty())
        // At the phone's own density (3 px a dp) the tab is over 60 px high; on the TV's 1280-unit canvas it would be
        // about 30 px.
        val tab = compose.onNode(hasText("Movies")).fetchSemanticsNode()
        assertTrue("${tab.size.height} px", tab.size.height > 60)
    }
}
