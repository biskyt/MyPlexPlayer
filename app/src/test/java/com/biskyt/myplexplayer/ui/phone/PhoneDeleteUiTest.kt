package com.biskyt.myplexplayer.ui.phone

import android.content.pm.PackageManager
import androidx.compose.runtime.remember
import androidx.test.core.app.ApplicationProvider
import com.biskyt.myplexplayer.testutil.FakePlex
import com.biskyt.myplexplayer.testutil.Navigators
import com.biskyt.myplexplayer.testutil.OnAppBackground
import com.biskyt.myplexplayer.testutil.TestApp
import com.biskyt.myplexplayer.testutil.addSyncedHomePlex
import com.biskyt.myplexplayer.testutil.click
import com.biskyt.myplexplayer.testutil.createUiComposeRule
import com.biskyt.myplexplayer.testutil.waitAdvancingClock
import com.biskyt.myplexplayer.testutil.waitForText
import com.biskyt.myplexplayer.ui.NavHost
import com.biskyt.myplexplayer.ui.Screen
import com.biskyt.myplexplayer.ui.ScreenContent
import com.biskyt.myplexplayer.ui.theme.MyPlexTheme
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

/** On a phone, a series with no episodes is deleted only once the user says yes, as every delete asks first. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestApp::class, qualifiers = "w390dp-h844dp-xxhdpi")
class PhoneDeleteUiTest {
    @get:Rule val compose = createUiComposeRule()

    @get:Rule(order = Navigators.ORDER)
    val navigators = Navigators()

    private val plex = FakePlex()
    private val app = ApplicationProvider.getApplicationContext<TestApp>()
    private var source = 0L

    @Before
    fun setUp() {
        shadowOf(app.packageManager).setSystemFeature(PackageManager.FEATURE_TOUCHSCREEN, true)
        autoFindsTv = { false } // the phone layout (TestApp sets the TV's)
        source = runBlocking { app.container.addSyncedHomePlex(plex) }
        plex.episodes = emptyList() // gone from the server since the sync
    }

    @After
    fun tearDown() = plex.close()

    @Test
    fun `Delete the series asks first, and deletes nothing until confirmed`() {
        val navigator = navigators.new(Screen.Series())
        navigator.push(Screen.SeriesDetails(source, 21))
        compose.setContent { MyPlexTheme { OnAppBackground { NavHost(remember { navigator }) { ScreenContent(it) } } } }
        compose.waitForText("Delete the series", stepMs = 50)
        compose.click("Delete the series")
        compose.waitForText("Keep it", stepMs = 50)
        assertTrue(plex.deleted.isEmpty())
        compose.click("Keep it")
        compose.waitForText("Delete the series", stepMs = 50)
        assertTrue(plex.deleted.isEmpty())

        compose.click("Delete the series")
        compose.waitForText("Keep it", stepMs = 50)
        compose.click("Delete")
        compose.waitAdvancingClock(stepMs = 50) { plex.deleted == listOf("21") }
    }
}
