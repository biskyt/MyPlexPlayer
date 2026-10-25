package com.biskyt.myplexplayer.ui

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.core.app.ApplicationProvider
import com.biskyt.myplexplayer.data.db.VOD_MOVIE
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
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A poster's options mark a film watched, then unwatched, on the server. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestApp::class, qualifiers = "+w960dp-h540dp-tvdpi")
class PlexWatchedUiTest {
    @get:Rule val compose = createUiComposeRule()

    @get:Rule(order = Navigators.ORDER)
    val navigators = Navigators()

    private val plex = FakePlex()
    private val app = ApplicationProvider.getApplicationContext<TestApp>()
    private var source = 0L

    @Before
    fun setUp() = runBlocking {
        source = app.container.addHomePlex(plex)
        app.container.vod.sync(app.container.db.playlists().get(source)!!)
    }

    @After
    fun tearDown() = plex.close()

    /** Holds OK on the film's poster and picks [action] from its options. */
    private fun choose(action: String) {
        compose.waitForText("Big Buck Bunny", stepMs = 50)
        compose.onAllNodes(hasText("Big Buck Bunny") and hasClickAction())[0].performSemanticsAction(SemanticsActions.OnLongClick)
        compose.waitForText(action, stepMs = 50)
        compose.onNode(hasText(action) and hasClickAction()).performSemanticsAction(SemanticsActions.OnClick)
    }

    private fun finished() = runBlocking {
        app.container.db.vod().progressOf(source, VOD_MOVIE, listOf(11L)).singleOrNull()?.finished == true
    }

    @Test
    fun `a film is marked watched, then unwatched`() {
        val navigator = navigators.new(Screen.Movies())
        compose.setContent { MyPlexTheme { NavHost(navigator) { ScreenContent(it) } } }
        choose("Mark as watched")
        // Told to the server, then its answer saved here.
        compose.waitAdvancingClock(stepMs = 50) { "11" in plex.watched && finished() }
        choose("Mark as unwatched")
        compose.waitAdvancingClock(stepMs = 50) { "11" !in plex.watched && !finished() }
    }
}
