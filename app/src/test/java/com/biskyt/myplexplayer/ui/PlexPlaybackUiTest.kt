package com.biskyt.myplexplayer.ui

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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A film from Plex in a container the player can't read: the server streams it as it is, or the player says why not. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestApp::class, qualifiers = "+w960dp-h540dp-tvdpi")
class PlexPlaybackUiTest {
    @get:Rule val compose = createUiComposeRule()

    @get:Rule(order = Navigators.ORDER)
    val navigators = Navigators()

    private val plex = FakePlex()
    private val app = ApplicationProvider.getApplicationContext<TestApp>()
    private var source = 0L

    @Before
    fun setUp() = runBlocking {
        plex.unreadableFiles = true
        source = app.container.addHomePlex(plex)
        app.container.vod.sync(app.container.db.playlists().get(source)!!)
    }

    @After
    fun tearDown() = plex.close()

    private fun play(ratingKey: Long) {
        val navigator = navigators.new(Screen.VodPlayer(source, ratingKey))
        compose.setContent { MyPlexTheme { NavHost(navigator) { ScreenContent(it) } } }
    }

    @Test
    fun `a file the player can't read is streamed by the server, repackaged`() {
        play(11)
        compose.waitAdvancingClock(stepMs = 100) { plex.streamed.isNotEmpty() }
    }

    @Test
    fun `a file the server could only convert isn't converted`() {
        plex.copies = false
        play(11)
        compose.waitForText("converts it", stepMs = 100)
        assertTrue("nothing streamed", plex.streamed.isEmpty())
    }
}
