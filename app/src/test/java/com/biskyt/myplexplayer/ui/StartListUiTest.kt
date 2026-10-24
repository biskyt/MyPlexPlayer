package com.biskyt.myplexplayer.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.core.app.ApplicationProvider
import com.biskyt.myplexplayer.data.db.CAT_MOVIE
import com.biskyt.myplexplayer.testutil.FakeProvider
import com.biskyt.myplexplayer.testutil.Navigators
import com.biskyt.myplexplayer.testutil.TestApp
import com.biskyt.myplexplayer.testutil.addHomeTv
import com.biskyt.myplexplayer.testutil.createUiComposeRule
import com.biskyt.myplexplayer.testutil.waitAdvancingClock
import com.biskyt.myplexplayer.testutil.waitForText
import com.biskyt.myplexplayer.ui.theme.MyPlexTheme
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Live TV, Movies and Series open on Favourites, and on the list picked earlier while the app is open. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestApp::class, qualifiers = "+w960dp-h540dp-tvdpi")
class StartListUiTest {
    @get:Rule val compose = createUiComposeRule()

    @get:Rule(order = Navigators.ORDER)
    val navigators = Navigators()

    private val provider = FakeProvider()
    private val app = ApplicationProvider.getApplicationContext<TestApp>()
    private var playlistId = 0L
    private lateinit var navigator: Navigator

    @Before
    fun setUp() = runBlocking {
        playlistId = app.container.addHomeTv(provider, vod = true)
    }

    @After
    fun tearDown() = provider.close()

    private var shown by mutableStateOf<Navigator?>(null)

    /** The app opened (again): a new navigator, as each time the app starts. */
    private fun open(screen: Screen) {
        navigator = navigators.new(screen)
        if (shown == null) {
            shown = navigator
            compose.setContent { MyPlexTheme { shown?.let { n -> key(n) { NavHost(n) { ScreenContent(it) } } } } }
        } else {
            compose.runOnUiThread { shown = navigator }
        }
        compose.waitForIdle()
    }

    private fun go(screen: Screen) {
        compose.runOnUiThread { navigator.reset(screen) }
        compose.waitForIdle()
    }

    private fun waitFor(text: String) = compose.waitForText(text, stepMs = 50)

    private fun waitGone(text: String) = compose.waitAdvancingClock(stepMs = 50) {
        compose.onAllNodes(hasText(text)).fetchSemanticsNodes().isEmpty()
    }

    private fun pick(category: String) {
        compose.onAllNodes(hasText(category, substring = true) and hasClickAction())[0].performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
    }

    @Test
    fun `Live TV opens on all channels until there are favourites, then on Favourites`() {
        open(Screen.Live())
        waitFor("UK: KITE KIDZ")
        runBlocking { app.container.channels.setFavourite(playlistId, 101, true) }
        go(Screen.Home)
        go(Screen.Live())
        waitFor("UK: CHANNEL ONE HD")
        waitGone("UK: KITE KIDZ")
    }

    @Test
    fun `a Live TV list picked is kept while the app is open, and not once it has been closed`() {
        runBlocking { app.container.channels.setFavourite(playlistId, 101, true) }
        open(Screen.Live())
        waitFor("UK: CHANNEL ONE HD")
        pick("SPORT")
        waitFor("UK: ARENA SPRTS 1 FHD")
        go(Screen.Home)
        go(Screen.Live())
        waitFor("UK: ARENA SPRTS 1 FHD")
        waitGone("UK: CHANNEL ONE HD")

        open(Screen.Live())
        waitFor("UK: CHANNEL ONE HD")
        waitGone("UK: ARENA SPRTS 1 FHD")
    }

    @Test
    fun `Movies open on Favourites, and on the list picked while the app is open`() {
        runBlocking { app.container.vod.setFavourite(playlistId, CAT_MOVIE, 502, true) }
        open(Screen.Movies())
        waitFor("Paddington 2")
        waitGone("Dune")
        pick("All movies")
        waitFor("Dune")
        go(Screen.Home)
        go(Screen.Movies())
        waitFor("Dune")

        open(Screen.Movies())
        waitFor("Paddington 2")
        waitGone("Dune")
    }
}
