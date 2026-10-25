package com.biskyt.myplexplayer.ui

import androidx.compose.runtime.remember
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import com.biskyt.myplexplayer.data.ChannelFilter
import com.biskyt.myplexplayer.data.plexChannelId
import com.biskyt.myplexplayer.testutil.FakePlex
import com.biskyt.myplexplayer.testutil.Navigators
import com.biskyt.myplexplayer.testutil.TestApp
import com.biskyt.myplexplayer.testutil.addHomePlex
import com.biskyt.myplexplayer.testutil.createUiComposeRule
import com.biskyt.myplexplayer.testutil.topRoot
import com.biskyt.myplexplayer.testutil.waitAdvancingClock
import com.biskyt.myplexplayer.testutil.waitForText
import com.biskyt.myplexplayer.ui.player.PlayerViewModel
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

@OptIn(ExperimentalTestApi::class)
/** Live TV from a Plex server's tuner: tuned once, its session stopped when changing channel and when leaving. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestApp::class, qualifiers = "+w960dp-h540dp-tvdpi")
class PlexLiveUiTest {
    @get:Rule val compose = createUiComposeRule()

    @get:Rule(order = Navigators.ORDER)
    val navigators = Navigators()

    private val plex = FakePlex()
    private val app = ApplicationProvider.getApplicationContext<TestApp>()
    private var source = 0L
    private lateinit var vm: PlayerViewModel

    @Before
    fun setUp() {
        runBlocking {
            source = app.container.addHomePlex(plex)
            app.container.sync.refreshNow(source).getOrThrow()
        }
    }

    @After
    fun tearDown() = plex.close()

    private fun watch(channel: String) {
        val navigator = navigators.new(Screen.Player(source, plexChannelId(channel), ChannelFilter.All))
        compose.setContent { MyPlexTheme { NavHost(remember { navigator }) { ScreenContent(it) } } }
        vm = ViewModelProvider(navigator.current)[PlayerViewModel::class.java]
    }

    @Test
    fun `a channel is tuned once, says so till its picture starts, and changing channel lets its tuner go first`() {
        watch("ch-1")
        compose.waitForText("Tuning…", stepMs = 100)
        assertEquals(listOf("ch-1"), plex.tuned)

        compose.topRoot().performKeyInput { pressKey(Key.ChannelUp) }
        compose.waitAdvancingClock(stepMs = 100) { plex.tuned.size == 2 }
        // Stopped before the next tune: there may be one tuner.
        assertEquals(listOf("/livetv/sessions/1-ch-1"), plex.liveStopped.toList())
        assertEquals(listOf("ch-1", "ch-2"), plex.tuned.toList())

        compose.runOnIdle { vm.stop() }
        compose.waitAdvancingClock(stepMs = 100) { plex.liveStopped.size == 2 }
    }

    @Test
    fun `leaving the app lets the tuner go, and coming back tunes again after it has`() {
        watch("ch-1")
        compose.waitAdvancingClock(stepMs = 100) { plex.tuned.size == 1 }
        compose.runOnIdle {
            vm.onAppHidden()
            vm.onAppShown()
        }
        compose.waitAdvancingClock(stepMs = 100) { plex.tuned.size == 2 }
        assertEquals(listOf("/livetv/sessions/1-ch-1"), plex.liveStopped.toList())
    }

    @Test
    fun `a tune that fails says so, and isn't sent again`() {
        plex.tuneFails = true
        watch("ch-1")
        compose.waitForText("500", stepMs = 100) // the server's error
        repeat(30) { compose.waitAdvancingClock(1_000) { true } } // the player's own retries come and go
        assertEquals(listOf("ch-1"), plex.tuned.toList())
    }
}
