package com.biskyt.myplexplayer.ui

import android.net.ConnectivityManager
import androidx.compose.runtime.remember
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.core.app.ApplicationProvider
import com.biskyt.myplexplayer.testutil.FakePlex
import com.biskyt.myplexplayer.testutil.Navigators
import com.biskyt.myplexplayer.testutil.TestApp
import com.biskyt.myplexplayer.testutil.addHomePlex
import com.biskyt.myplexplayer.testutil.createUiComposeRule
import com.biskyt.myplexplayer.testutil.idleFrames
import com.biskyt.myplexplayer.testutil.reportNetwork
import com.biskyt.myplexplayer.testutil.settle
import com.biskyt.myplexplayer.testutil.waitAdvancingClock
import com.biskyt.myplexplayer.testutil.waitForText
import com.biskyt.myplexplayer.ui.theme.MyPlexTheme
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** "Warn if VPN is not connected": the top bar says whether Android reports a VPN, as it changes; nothing when off. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestApp::class, qualifiers = "+w960dp-h540dp-tvdpi")
class VpnBadgeUiTest {
    @get:Rule val compose = createUiComposeRule()

    @get:Rule(order = Navigators.ORDER)
    val navigators = Navigators()

    private val plex = FakePlex()
    private val app = ApplicationProvider.getApplicationContext<TestApp>()
    private val connectivity = app.getSystemService(ConnectivityManager::class.java)

    @Before
    fun setUp() = runBlocking<Unit> {
        app.container.addHomePlex(plex)
    }

    @After
    fun tearDown() = plex.close()

    private fun show() {
        val navigator = navigators.new(Screen.Live())
        compose.setContent { MyPlexTheme { NavHost(remember { navigator }) { ScreenContent(it) } } }
        compose.waitForText("Live TV")
    }

    private fun shown(text: String) = compose.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun `off by default, with nothing in the top bar`() {
        show()
        compose.settle()
        assertFalse(shown("VPN on"))
        assertFalse(shown("VPN not connected"))
        assertTrue("nothing listens to the network", shadowOf(connectivity).networkCallbacks.isEmpty())
    }

    @Test
    fun `on, the top bar says whether a VPN is connected as it changes`() {
        runBlocking { app.container.settings.update { it.copy(warnVpn = true) } }
        show()
        compose.waitForText("VPN not connected")
        compose.waitAdvancingClock { shadowOf(connectivity).networkCallbacks.isNotEmpty() }
        connectivity.reportNetwork(vpn = true)
        compose.waitForText("VPN on")
        connectivity.reportNetwork(vpn = null)
        compose.waitForText("VPN not connected")
    }

    @Test
    fun `the setting is in the main settings list`() {
        val navigator = navigators.new(Screen.Settings)
        compose.setContent { MyPlexTheme { NavHost(remember { navigator }) { ScreenContent(it) } } }
        compose.waitForText("Playlists")
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Warn if VPN is not connected"))
        compose.onNode(hasText("Warn if VPN is not connected") and hasClickAction()).performSemanticsAction(SemanticsActions.OnClick)
        compose.waitAdvancingClock { runBlocking { app.container.settings.current().warnVpn } }
        compose.waitForText("VPN not connected")
    }

    @Test
    fun `the external IP setting shows only while the VPN warning is on, and is off by default`() {
        val navigator = navigators.new(Screen.Settings)
        compose.setContent { MyPlexTheme { NavHost(remember { navigator }) { ScreenContent(it) } } }
        compose.waitForText("Playlists")
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Warn if VPN is not connected"))
        assertFalse(shown("Show your external IP address"))
        compose.onNode(hasText("Warn if VPN is not connected") and hasClickAction()).performSemanticsAction(SemanticsActions.OnClick)
        // The row comes only once the setting is saved and back on the screen: the save is not done on the click.
        compose.waitAdvancingClock { runBlocking { app.container.settings.current().warnVpn } }
        compose.idleFrames()
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Show your external IP address"))
        assertFalse(runBlocking { app.container.settings.current().showExternalIp })
        compose.onNode(hasText("Show your external IP address") and hasClickAction()).performSemanticsAction(SemanticsActions.OnClick)
        compose.waitAdvancingClock { runBlocking { app.container.settings.current().showExternalIp } }
    }
}
