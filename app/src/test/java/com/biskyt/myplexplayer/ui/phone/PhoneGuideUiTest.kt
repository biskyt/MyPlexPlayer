package com.biskyt.myplexplayer.ui.phone

import android.content.pm.PackageManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import com.biskyt.myplexplayer.data.ChannelFilter
import com.biskyt.myplexplayer.data.plexChannelId
import com.biskyt.myplexplayer.testutil.FakePlex
import com.biskyt.myplexplayer.testutil.Navigators
import com.biskyt.myplexplayer.testutil.TestApp
import com.biskyt.myplexplayer.testutil.addSyncedHomePlex
import com.biskyt.myplexplayer.testutil.createUiComposeRule
import com.biskyt.myplexplayer.testutil.waitAdvancingClock
import com.biskyt.myplexplayer.testutil.waitForText
import com.biskyt.myplexplayer.ui.NavHost
import com.biskyt.myplexplayer.ui.Navigator
import com.biskyt.myplexplayer.ui.Screen
import com.biskyt.myplexplayer.ui.ScreenContent
import com.biskyt.myplexplayer.ui.theme.MyPlexTheme
import com.biskyt.myplexplayer.ui.theme.Palette
import com.biskyt.myplexplayer.ui.theme.autoFindsTv
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

/**
 * The TV guide on a phone (the owner, 2026-11-26: every screen has a phone layout), MyTVPlayer's PhoneGuideUiTest on
 * FakePlex: a tap plays the channel, a long press opens the programme's options (with the recording ones), and a
 * programme set to record has its mark.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestApp::class, qualifiers = "w390dp-h844dp-xxhdpi")
class PhoneGuideUiTest {
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
        autoFindsTv = { false } // the phone layout (TestApp sets the TV's)
        // Channel Two's film, on next, is set to record.
        plex.rules += FakePlex.Rule("1", 4, "plex://episode/ch-2-1", emptyMap())
        source = app.container.addSyncedHomePlex(plex)
        app.container.recordings.refresh(source)
    }

    @After
    fun tearDown() = plex.close()

    private fun show(screen: Screen) {
        navigator = navigators.new(screen)
        compose.setContent {
            MyPlexTheme {
                Box(Modifier.fillMaxSize().background(Palette.background)) { NavHost(remember { navigator }) { ScreenContent(it) } }
            }
        }
    }

    /** The channel list, then the guide opened from its header's button, with channel One's programme on now shown. */
    private fun openGuide() {
        show(Screen.Live(ChannelFilter.All))
        compose.waitForText("One", stepMs = 50)
        compose.onNode(hasText("TV guide") and hasClickAction()).performClick()
        compose.waitAdvancingClock(stepMs = 50) { navigator.current.screen == Screen.Live(ChannelFilter.All, guide = true) }
        compose.waitForText("Show 0", stepMs = 50)
    }

    @Test
    fun `the guide on a phone is drawn for touch, with a back arrow and the category chips`() {
        openGuide()
        // The TV's guide (drawn as on a TV) has neither.
        compose.onNode(hasContentDescription("Back")).assertExists()
        compose.onNode(hasText("All channels") and hasClickAction()).assertExists()
        // Each programme is a block of its own, with its times.
        compose.onNode(hasText("Film 0") and hasClickAction()).assertExists()
    }

    @Test
    fun `a programme set to record has its mark`() {
        openGuide()
        compose.waitForText("Film 1", stepMs = 50)
        compose.onNode(hasContentDescription("Set to record")).assertExists()
    }

    @Test
    fun `a tap on a programme on now plays its channel`() {
        openGuide()
        compose.onNode(hasText("Show 0") and hasClickAction()).performClick()
        compose.waitAdvancingClock(stepMs = 50) { navigator.current.screen is Screen.Player }
        assertEquals(Screen.Player(source, plexChannelId("ch-1"), ChannelFilter.All), navigator.current.screen)
    }

    @Test
    fun `the back arrow returns to the channel list`() {
        openGuide()
        compose.onNode(hasContentDescription("Back")).performClick()
        compose.waitAdvancingClock(stepMs = 50) { navigator.current.screen == Screen.Live(ChannelFilter.All) }
        assertEquals(1, navigator.stack.size)
    }

    @Test
    fun `a long press opens the programme's options, with Record, and Switch to channel list returns to the list`() {
        openGuide()
        compose.onNode(hasText("Film 0") and hasClickAction()).performTouchInput { longClick() }
        compose.waitForText("Switch to channel list", stepMs = 50)
        compose.onNode(hasText("Record") and hasClickAction()).assertExists()
        // The last option: the sheet scrolls to it first.
        compose.onNode(hasText("Switch to channel list") and hasClickAction()).performScrollTo().performClick()
        compose.waitAdvancingClock(stepMs = 50) { navigator.current.screen == Screen.Live(ChannelFilter.All) }
        assertEquals(1, navigator.stack.size)
    }
}
