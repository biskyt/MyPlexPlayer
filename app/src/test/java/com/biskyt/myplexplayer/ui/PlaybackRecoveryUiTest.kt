package com.biskyt.myplexplayer.ui

import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import com.biskyt.myplexplayer.testutil.FakePlex
import com.biskyt.myplexplayer.testutil.FileReply
import com.biskyt.myplexplayer.testutil.Navigators
import com.biskyt.myplexplayer.testutil.TestApp
import com.biskyt.myplexplayer.testutil.addHomePlex
import com.biskyt.myplexplayer.testutil.createUiComposeRule
import com.biskyt.myplexplayer.testutil.waitAdvancingClock
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

/** How the player gets a film going when its first tries fail (ported from MyTVPlayer 1.34.8, on FakePlex). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestApp::class, qualifiers = "+w960dp-h540dp-tvdpi")
class PlaybackRecoveryUiTest {
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

    private fun fileRequests() = plex.paths.count { it.startsWith("/library/parts/") }

    @Test
    fun `a film whose server does not answer at first is tried again after about 20 seconds`() {
        // The owner, 2026-11-23: an old episode on slow storage plays at the second or third try. Waiting 60 seconds
        // for each try made it sit for minutes, then fail.
        plex.files = FileReply.Silent
        val navigator = navigators.new(Screen.VodPlayer(source, 11))
        compose.setContent { MyPlexTheme { NavHost(navigator) { ScreenContent(it) } } }
        compose.waitAdvancingClock(stepMs = 0) { fileRequests() > 0 }
        val start = SystemClock.uptimeMillis()
        compose.waitAdvancingClock(stepMs = 2_000) { fileRequests() > 1 }
        val waited = SystemClock.uptimeMillis() - start
        assertTrue("tried again after $waited ms", waited in 19_000L..30_000L) // the stall wait, not an early error
    }
}
