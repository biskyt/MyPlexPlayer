package com.biskyt.myplexplayer.ui

import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import com.biskyt.myplexplayer.R
import com.biskyt.myplexplayer.appString
import com.biskyt.myplexplayer.testutil.FakePlex
import com.biskyt.myplexplayer.testutil.Navigators
import com.biskyt.myplexplayer.testutil.TestApp
import com.biskyt.myplexplayer.testutil.addHomePlex
import com.biskyt.myplexplayer.testutil.createUiComposeRule
import com.biskyt.myplexplayer.testutil.waitAdvancingClock
import com.biskyt.myplexplayer.testutil.waitForText
import com.biskyt.myplexplayer.ui.player.VodPlayerViewModel
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

/** What the player tells about a film that fails: why, and its address (ported from MyTVPlayer 1.8.9). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestApp::class, qualifiers = "+w960dp-h540dp-tvdpi")
class StreamDiagnosticsUiTest {
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

    @Test
    fun `an empty film says the server sent an empty file, and shows its address`() {
        plex.emptyFiles = true
        val navigator = navigators.new(Screen.VodPlayer(source, 11))
        compose.setContent { MyPlexTheme { NavHost(navigator) { ScreenContent(it) } } }
        val vm = ViewModelProvider(navigator.current)[VodPlayerViewModel::class.java]
        // The player retries a failed load a few times first: let its (simulated) time pass.
        compose.waitAdvancingClock(500) { vm.error != null }
        assertTrue(vm.error.toString(), vm.error?.reason.orEmpty().startsWith(appString(R.string.player_error_empty)))
        val address = vm.error?.address.orEmpty()
        assertTrue(address, address.contains("/library/parts/"))
        compose.waitForText(address)
    }
}
