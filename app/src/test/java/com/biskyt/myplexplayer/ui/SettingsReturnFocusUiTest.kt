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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Back in Settings from a screen one of its rows opened, that row has focus (the owner, 2026-11-05). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestApp::class, qualifiers = "+w960dp-h540dp-tvdpi")
class SettingsReturnFocusUiTest {
    @get:Rule val compose = createUiComposeRule()

    @get:Rule(order = Navigators.ORDER)
    val navigators = Navigators()

    private val plex = FakePlex()
    private val app = ApplicationProvider.getApplicationContext<TestApp>()

    @Before
    fun setUp() = runBlocking<Unit> { app.container.addHomePlex(plex) }

    @After
    fun tearDown() = plex.close()

    @Test
    fun `back from Appearance, its row has focus`() {
        // A TV: Compose then scrolls lists as on one.
        shadowOf(app.packageManager).setSystemFeature(PackageManager.FEATURE_LEANBACK, true)
        val navigator = navigators.new(Screen.Settings)
        compose.setContent { MyPlexTheme { NavHost(remember { navigator }) { ScreenContent(it) } } }
        compose.waitForText("Playlists", stepMs = 50)
        compose.waitAdvancingClock(stepMs = 50) { compose.focusedText().isNotEmpty() }

        repeat(MAX_KEYS) { if (ROW !in compose.focusedText()) compose.pressAndRead(Key.DirectionDown) }
        assertTrue(compose.focusedText(), ROW in compose.focusedText())
        compose.pressAndRead(Key.DirectionCenter)
        compose.waitForText("Accent colour", stepMs = 50)

        compose.runOnUiThread { navigator.pop() }
        compose.waitAdvancingClock(stepMs = 50) { compose.focusedText().isNotEmpty() }
        assertTrue("Back in Settings, focus is on: ${compose.focusedText()}", ROW in compose.focusedText())
    }

    private companion object {
        const val ROW = "Appearance"
        const val MAX_KEYS = 10
    }
}
