package com.biskyt.myplexplayer.ui

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.core.app.ApplicationProvider
import com.biskyt.myplexplayer.testutil.FakePlex
import com.biskyt.myplexplayer.testutil.Navigators
import com.biskyt.myplexplayer.testutil.TestApp
import com.biskyt.myplexplayer.testutil.addHomePlex
import com.biskyt.myplexplayer.testutil.createUiComposeRule
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
import org.robolectric.annotation.Config

/** Signing in to Plex with a code entered at plex.tv/link, on first launch and from Settings. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestApp::class, qualifiers = "+w960dp-h540dp-tvdpi")
class PlexSignInUiTest {
    @get:Rule val compose = createUiComposeRule()

    @get:Rule(order = Navigators.ORDER)
    val navigators = Navigators()

    private val plex = FakePlex()
    private val app = ApplicationProvider.getApplicationContext<TestApp>()
    private lateinit var navigator: Navigator

    @Before
    fun setUp() {
        app.container.plex.plexTv = plex.url
    }

    @After
    fun tearDown() = plex.close()

    private fun show(screen: Screen) {
        navigator = navigators.new(screen)
        compose.setContent { MyPlexTheme { NavHost(navigator) { ScreenContent(it) } } }
        compose.waitForIdle()
    }

    private fun press(text: String) {
        compose.onNode(hasText(text) and hasClickAction()).performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
    }

    /** Until the screen shown is [screen], with time passing (the code is checked every 2 seconds). */
    private fun waitForScreen(screen: Screen) = compose.waitAdvancingClock(stepMs = 500) { navigator.current.screen == screen }

    @Test
    fun `first launch signs in with a code, adds the servers that answer and opens Home`() {
        show(Screen.Welcome)
        press("Sign in to Plex")
        compose.waitForText("ABCD")
        compose.waitForText("Waiting for the code to be entered…")
        plex.codeCheckFailures = 1 // plex.tv doesn't answer once: it's asked again
        plex.claimed = true // entered at plex.tv/link
        // Friend's Plex is out of reach from here: said, then on.
        compose.waitForText("Couldn't reach: Friend's Plex", stepMs = 500)
        press("Continue")
        waitForScreen(Screen.Home)
        runBlocking {
            val sources = app.container.db.playlists().all()
            assertEquals(listOf("Home Plex"), sources.map { it.name })
            assertEquals(sources[0].id, app.container.settings.current().activePlaylistId)
        }
        // Its films load straight away.
        compose.runOnUiThread { navigator.reset(Screen.Movies()) }
        compose.waitForText("Big Buck Bunny", stepMs = 50)
    }

    @Test
    fun `signed in already, Settings only looks for the servers again`() {
        runBlocking { app.container.addHomePlex(plex) }
        show(Screen.Playlists)
        compose.waitForText("Home Plex")
        press("Add my Plex servers")
        compose.waitForText("Couldn't reach: Friend's Plex", stepMs = 500)
        press("Continue")
        waitForScreen(Screen.Playlists)
        runBlocking { assertEquals(1, app.container.db.playlists().all().size) }
    }

    @Test
    fun `a Plex server can be removed, and with none left, set-up starts again`() {
        runBlocking { app.container.addHomePlex(plex) }
        show(Screen.Playlists)
        compose.waitForText("Home Plex")
        press("Remove")
        waitForScreen(Screen.Welcome)
        runBlocking { assertEquals(0, app.container.db.playlists().all().size) }
    }

    @Test
    fun `when codes run out unused, it offers to try again`() {
        plex.codeSeconds = 4
        show(Screen.PlexSignIn(firstRun = true))
        compose.waitForText("ABCD")
        compose.waitForText("The code ran out before it was entered. Try again for a new one.", stepMs = 500)
        plex.codeSeconds = 900
        press("Try again")
        compose.waitForText("ABCD")
    }

    @Test
    fun `signed out on plex dot tv, it asks for a code again`() {
        runBlocking { app.container.addHomePlex(plex) }
        plex.tokenAccepted = false
        plex.claimed = false
        show(Screen.PlexSignIn())
        compose.waitForText("ABCD")
    }
}
