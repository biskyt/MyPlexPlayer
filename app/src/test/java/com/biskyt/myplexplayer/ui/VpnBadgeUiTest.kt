package com.biskyt.myplexplayer.ui

import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.compose.runtime.remember
import androidx.compose.ui.test.hasText
import androidx.test.core.app.ApplicationProvider
import com.biskyt.myplexplayer.testutil.FakePlex
import com.biskyt.myplexplayer.testutil.Navigators
import com.biskyt.myplexplayer.testutil.TestApp
import com.biskyt.myplexplayer.testutil.addHomePlex
import com.biskyt.myplexplayer.testutil.createUiComposeRule
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
import org.robolectric.shadows.ShadowNetwork
import org.robolectric.shadows.ShadowNetworkCapabilities

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

    /** What Android tells the app as the default network changes: [vpn], or the network gone. */
    private fun network(vpn: Boolean?) {
        val network = ShadowNetwork.newInstance(1)
        shadowOf(connectivity).networkCallbacks.forEach { callback ->
            if (vpn == null) {
                callback.onLost(network)
            } else {
                val caps = ShadowNetworkCapabilities.newInstance()
                if (vpn) shadowOf(caps).addTransportType(NetworkCapabilities.TRANSPORT_VPN)
                callback.onCapabilitiesChanged(network, caps)
            }
        }
    }

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
        network(vpn = true)
        compose.waitForText("VPN on")
        network(vpn = null)
        compose.waitForText("VPN not connected")
    }
}
