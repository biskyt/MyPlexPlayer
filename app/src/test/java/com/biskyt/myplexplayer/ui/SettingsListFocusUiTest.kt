package com.biskyt.myplexplayer.ui

import android.content.pm.PackageManager
import androidx.compose.runtime.remember
import androidx.compose.ui.input.key.Key
import androidx.test.core.app.ApplicationProvider
import com.biskyt.myplexplayer.testutil.FakePlex
import com.biskyt.myplexplayer.testutil.Navigators
import com.biskyt.myplexplayer.testutil.TestApp
import com.biskyt.myplexplayer.testutil.addHomePlex
import com.biskyt.myplexplayer.testutil.createUiComposeRule
import com.biskyt.myplexplayer.testutil.focusedText
import com.biskyt.myplexplayer.testutil.pressAndRead
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
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Settings on a screen too short for all its rows: the remote's Up from the bottom stops on each row in turn, as Down
 * did. Its first row takes focus as the page opens (rememberFirstFocus); scrolled back into view, it must not take
 * focus again and skip the rows between (MyTVPlayer 1.13.2's GuideSourcesFocusUiTest, on a MyPlexPlayer list).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestApp::class, qualifiers = "+w960dp-h360dp-tvdpi")
class SettingsListFocusUiTest {
    @get:Rule val compose = createUiComposeRule()

    @get:Rule(order = Navigators.ORDER)
    val navigators = Navigators()

    private val plex = FakePlex()
    private val app = ApplicationProvider.getApplicationContext<TestApp>()

    @Before
    fun setUp() = runBlocking<Unit> { app.container.addHomePlex(plex) }

    @After
    fun tearDown() = plex.close()

    /** The setting's name: the first line of what has focus. */
    private fun row(focused: String) = focused.substringBefore(" | ").substringBefore('\n').trim()

    @Test
    fun `up from the bottom stops on every row, as down did`() {
        // A TV: Compose then scrolls lists as on one (the focused item about a third of the way down).
        shadowOf(app.packageManager).setSystemFeature(PackageManager.FEATURE_LEANBACK, true)
        val navigator = navigators.new(Screen.Settings)
        compose.setContent { MyPlexTheme { NavHost(remember { navigator }) { ScreenContent(it) } } }
        compose.waitForText("Playlists", stepMs = 50)
        compose.waitAdvancingClock(stepMs = 50) { compose.focusedText().isNotEmpty() }

        // Down to the bottom, noting each row, until focus stays where it is.
        val down = mutableListOf(row(compose.focusedText()))
        repeat(MAX_KEYS) {
            val now = row(compose.pressAndRead(Key.DirectionDown))
            if (now != down.last()) down += now
        }
        // Then up, noting each stop, until focus stays (on the first row, or the top bar).
        val up = mutableListOf(down.last())
        repeat(MAX_KEYS) {
            val now = row(compose.pressAndRead(Key.DirectionUp))
            if (now != up.last()) up += now
        }
        assertEquals("Up from the bottom: $up", down.reversed(), up.take(down.size))
    }

    private companion object {
        const val MAX_KEYS = 14
    }
}
