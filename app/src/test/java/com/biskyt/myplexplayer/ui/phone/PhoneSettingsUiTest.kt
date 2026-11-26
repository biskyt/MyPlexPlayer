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
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.biskyt.myplexplayer.data.db.CAT_MOVIE
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
import com.biskyt.myplexplayer.ui.common.LinkPanel
import com.biskyt.myplexplayer.ui.theme.MyPlexTheme
import com.biskyt.myplexplayer.ui.theme.Palette
import com.biskyt.myplexplayer.ui.theme.TvCanvas
import com.biskyt.myplexplayer.ui.theme.autoFindsTv
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

/** The settings pages on a phone (the owner, 2026-11-25): a back arrow, buttons under a card, lists for a finger. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestApp::class, qualifiers = "w390dp-h844dp-xxhdpi")
class PhoneSettingsUiTest {
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
        app.container.plex.plexTv = plex.url
        app.container.plex.plexGuide = plex.url
        plex.libraries = listOf(
            FakePlex.Library("1", "movie", "UK | FILMS"),
            FakePlex.Library("2", "show", "TV Programmes"),
            FakePlex.Library("3", "movie", "|AR| NEWS"),
        )
        source = runBlocking { app.container.addSyncedHomePlex(plex) }
    }

    @After
    fun tearDown() = plex.close()

    private fun show(vararg screens: Screen): Navigator {
        val navigator = navigators.new(screens.first())
        screens.drop(1).forEach(navigator::push)
        compose.setContent {
            MyPlexTheme {
                Box(Modifier.fillMaxSize().background(Palette.background)) { NavHost(remember { navigator }) { ScreenContent(it) } }
            }
        }
        return navigator
    }

    private fun tap(text: String) = compose.onNode(hasText(text) and hasClickAction()).performClick()

    private fun tapBack() = compose.onNode(hasContentDescription("Back") and hasClickAction()).performClick()

    private fun shows(text: String) = compose.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty()

    private fun hasBack() = compose.onAllNodes(hasContentDescription("Back") and hasClickAction()).fetchSemanticsNodes().isNotEmpty()

    private fun bounds(text: String) = compose.onNode(hasText(text)).fetchSemanticsNode().boundsInRoot

    private fun included() = runBlocking { app.container.db.categories().included(source, CAT_MOVIE) }.map { it.name }.toSet()

    @Test
    fun `the back arrow on a settings page goes back, and nothing takes focus`() {
        val navigator = show(Screen.Settings, Screen.PlaybackSettings)
        compose.waitForText("Skip back", stepMs = 50)
        // No remote: the first row does not take focus, which would show it in the accent colour as if chosen.
        assertTrue(compose.onAllNodes(isFocused()).fetchSemanticsNodes().isEmpty())
        tapBack()
        compose.waitAdvancingClock(stepMs = 50) { navigator.current.screen == Screen.Settings }
        compose.waitForText("Appearance", stepMs = 50)
    }

    @Test
    fun `a server's buttons are under its card, and Remove still removes it`() {
        val navigator = show(Screen.Settings, Screen.Playlists)
        compose.waitForText("Home Plex", stepMs = 50)
        val card = bounds("Home Plex")
        val remove = bounds("Remove")
        assertTrue("$card, $remove", remove.top > card.bottom)
        tap("Remove")
        compose.waitAdvancingClock(stepMs = 50) { navigator.current.screen == Screen.Welcome }
    }

    @Test
    fun `sign-in shows the code with no QR code, and no back arrow on first launch`() {
        runBlocking { app.container.settings.setPlexToken(null) }
        show(Screen.PlexSignIn(firstRun = true))
        compose.waitForText("ABCD", stepMs = 500)
        compose.waitForText("Open on this device", stepMs = 50)
        // The page opens on this device: a QR code to scan with the same phone is no use.
        assertFalse(shows("Or scan this with your phone’s camera."))
        // The page took Welcome's place: nothing to go back to.
        assertFalse(hasBack())
    }

    @Test
    fun `a tap on a country opens its categories under it, and another closes them`() {
        show(Screen.Categories(source))
        compose.waitForText("No category", stepMs = 50) // the Live TV list, first
        tap("Movies")
        // The first country (in alphabetical order) is open to start with.
        compose.waitForText("|AR| NEWS", stepMs = 50)
        tap("Arabic")
        compose.waitAdvancingClock(stepMs = 50) { !shows("|AR| NEWS") }
        tap("Arabic")
        compose.waitForText("|AR| NEWS", stepMs = 50)
        val before = included()
        tap("|AR| NEWS")
        compose.waitAdvancingClock(stepMs = 50) { included() != before }
        assertTrue(("|AR| NEWS" in before) != ("|AR| NEWS" in included()))
    }

    private fun qrCodes() = compose.onAllNodes(hasContentDescription("QR code", substring = true)).fetchSemanticsNodes()

    @Test
    fun `a link panel has no QR code to scan with the same phone`() {
        compose.setContent { MyPlexTheme { LinkPanel("Ko-fi", "https://ko-fi.com/biskyt") {} } }
        compose.waitForText("Open on this device", stepMs = 50)
        assertTrue(qrCodes().isEmpty())
    }

    @Test
    fun `a link panel on a page drawn as on a TV has no QR code either (MyTVPlayer 1_39_1)`() {
        // A screen with no phone layout (Search, the TV guide) is drawn as on a TV (TvCanvas), on the phone.
        compose.setContent { MyPlexTheme { TvCanvas { LinkPanel("Ko-fi", "https://ko-fi.com/biskyt") {} } } }
        compose.waitForText("Open on this device", stepMs = 50)
        assertTrue(qrCodes().isEmpty())
    }

    @Test
    fun `sign-in drawn as on a TV, on a phone, has no QR code`() {
        runBlocking { app.container.settings.setPlexToken(null) }
        val navigator = navigators.new(Screen.PlexSignIn(firstRun = false))
        compose.setContent { MyPlexTheme { TvCanvas { NavHost(remember { navigator }) { ScreenContent(it) } } } }
        compose.waitForText("ABCD", stepMs = 500)
        assertTrue(qrCodes().isEmpty())
    }
}
