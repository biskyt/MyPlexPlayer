package com.biskyt.mytvplayer.ui.player

import androidx.test.core.app.ApplicationProvider
import com.biskyt.mytvplayer.core.player.RemoteKey
import com.biskyt.mytvplayer.data.ChannelFilter
import com.biskyt.mytvplayer.testutil.FakeProvider
import com.biskyt.mytvplayer.testutil.TestApp
import com.biskyt.mytvplayer.testutil.addHomeTv
import com.biskyt.mytvplayer.testutil.createUiComposeRule
import com.biskyt.mytvplayer.testutil.idleFrames
import com.biskyt.mytvplayer.testutil.waitAdvancingClock
import com.biskyt.mytvplayer.ui.Screen
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
        compose.waitAdvancingClock { vm.channel != null }
        // The channel list is looked up (off the main thread) after tuning starts, and nothing says when it's there.
        repeat(5) {
            Thread.sleep(100)
            compose.idleFrames()
        }
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
            compose.waitAdvancingClock { vm.guideChannel?.streamId != ids[0] }
            compose.idleFrames()
            assertEquals(ids[2], vm.guideChannel?.streamId)
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
            compose.waitAdvancingClock { vm.channel?.streamId != ids[0] }
            compose.idleFrames()
            assertEquals(ids[2], vm.channel?.streamId)
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
