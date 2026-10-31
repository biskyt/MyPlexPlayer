package com.biskyt.myplexplayer.ui

import androidx.compose.runtime.remember
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.core.app.ApplicationProvider
import com.biskyt.myplexplayer.testutil.FakePlex
import com.biskyt.myplexplayer.testutil.Navigators
import com.biskyt.myplexplayer.testutil.TestApp
import com.biskyt.myplexplayer.testutil.addSyncedHomePlex
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

/** Search results: Back from a result puts the focus on it again (ported from MyTVPlayer 1.8.10). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestApp::class, qualifiers = "+w960dp-h540dp-tvdpi")
class SearchResultsUiTest {
    @get:Rule val compose = createUiComposeRule()

    @get:Rule(order = Navigators.ORDER)
    val navigators = Navigators()

    private val plex = FakePlex()
    private val app = ApplicationProvider.getApplicationContext<TestApp>()
    private lateinit var navigator: Navigator

    @Before
    fun setUp() = runBlocking {
        plex.movies = listOf(FakePlex.film("51", "Dune", 2021))
        app.container.addSyncedHomePlex(plex)
        Unit
    }

    @After
    fun tearDown() = plex.close()

    @Test
    fun `back from a film, the focus is on it again`() {
        navigator = navigators.new(Screen.Search("du"))
        compose.setContent { MyPlexTheme { NavHost(remember { navigator }) { ScreenContent(it) } } }
        compose.waitForText("Dune", stepMs = 50)
        val dune = hasText("Dune") and hasClickAction()
        compose.onNode(dune).performSemanticsAction(SemanticsActions.RequestFocus)
        compose.onNode(dune).performSemanticsAction(SemanticsActions.OnClick)
        compose.waitAdvancingClock { navigator.current.screen is Screen.MovieDetails }
        // Play it, then Back twice: from the player to the film, then to the results.
        compose.waitForText("Play", stepMs = 50)
        compose.onNode(hasText("Play") and hasClickAction()).performSemanticsAction(SemanticsActions.OnClick)
        compose.waitAdvancingClock { navigator.current.screen is Screen.VodPlayer }
        compose.runOnUiThread { navigator.pop() }
        compose.waitAdvancingClock { navigator.current.screen is Screen.MovieDetails }
        compose.runOnUiThread { navigator.pop() }
        compose.waitAdvancingClock { navigator.current.screen is Screen.Search }
        compose.waitAdvancingClock { runCatching { compose.onNode(dune).assertIsFocused() }.isSuccess }
    }
}
