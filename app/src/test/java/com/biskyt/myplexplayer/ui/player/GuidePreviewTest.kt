package com.biskyt.myplexplayer.ui.player

import androidx.test.core.app.ApplicationProvider
import com.biskyt.myplexplayer.core.player.RemoteKey
import com.biskyt.myplexplayer.data.ChannelFilter
import com.biskyt.myplexplayer.testutil.FakeProvider
import com.biskyt.myplexplayer.testutil.TestApp
import com.biskyt.myplexplayer.testutil.addHomeTv
import com.biskyt.myplexplayer.testutil.createUiComposeRule
import com.biskyt.myplexplayer.testutil.idleFrames
import com.biskyt.myplexplayer.testutil.waitAdvancingClock
import com.biskyt.myplexplayer.ui.Screen
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The player's guide panel: ◀ ▶ preview other channels, however quickly they're pressed. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestApp::class)
class GuidePreviewTest {
    @get:Rule val compose = createUiComposeRule()
    private val provider = FakeProvider()
    private val app = ApplicationProvider.getApplicationContext<TestApp>()

    private lateinit var ids: List<Long>

    @After
    fun tearDown() = provider.close()

    /** The player on the first channel, playing, with the channel list (for ◀ ▶) loaded. */
    private fun player(): PlayerViewModel {
        provider.streamsTrickle = true
        val playlistId = runBlocking { app.container.addHomeTv(provider) }
        ids = runBlocking { app.container.channels.ids(playlistId, ChannelFilter.All) }
        val vm = PlayerViewModel(app.container, app, Screen.Player(playlistId, ids[0], ChannelFilter.All))
        compose.waitAdvancingClock { vm.channel != null && vm.channelListLoaded }
        return vm
    }

    @Test
    fun `quick presses each move the preview on a channel`() {
        val vm = player()
        try {
            compose.runOnIdle {
                vm.openGuide()
                // Two presses before any lookup has answered.
                repeat(2) { vm.previewGuide(1) }
            }
            // Two channels on (a lost press would leave it on the first, and the wait would time out).
            compose.waitAdvancingClock { vm.guideChannel?.streamId == ids[2] }
        } finally {
            vm.engine?.release()
        }
    }

    @Test
    fun `ok straight after quick presses watches the channel they got to`() {
        val vm = player()
        try {
            compose.runOnIdle {
                vm.openGuide()
                repeat(2) { vm.previewGuide(1) }
                vm.watchGuideChannel() // before the panel has caught up
            }
            compose.waitAdvancingClock { vm.channel?.streamId == ids[2] } // watching the one before would time out
        } finally {
            vm.engine?.release()
        }
    }

    @Test
    fun `the guide opened straight after changing channel watches the new one`() {
        val vm = player()
        try {
            compose.runOnIdle {
                vm.onKey(RemoteKey.CHANNEL_DOWN) // the next channel
                vm.openGuide() // before the new channel is known
            }
            compose.waitAdvancingClock { vm.channel?.streamId == ids[1] }
            compose.runOnIdle { vm.watchGuideChannel() }
            // A channel is looked up off the main thread: give a wrong one time to arrive.
            repeat(5) {
                Thread.sleep(100)
                compose.idleFrames()
            }
            assertEquals(ids[1], vm.channel?.streamId)
        } finally {
            vm.engine?.release()
        }
    }
}
