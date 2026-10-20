package com.biskyt.mytvplayer.ui.player

import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.text.Cue
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.SubtitleView
import com.biskyt.mytvplayer.testutil.TestApp
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@androidx.annotation.OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestApp::class)
class SubtitlesTest {
    @get:Rule val compose = createComposeRule()

    /** A player whose subtitle track is showing [text], as a stream's would. */
    private class SubtitledPlayer(var text: String) : SimpleBasePlayer(Looper.getMainLooper()) {
        override fun getState(): State = State.Builder()
            .setAvailableCommands(Player.Commands.Builder().addAll(Player.COMMAND_GET_TEXT).build())
            .setCurrentCues(CueGroup(listOf(Cue.Builder().setText(text).build()), 0))
            .build()

        override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> = Futures.immediateVoidFuture()

        fun show(next: String) {
            text = next
            invalidateState()
        }
    }

    /** The text of the cues the subtitle view was given. */
    private fun shown(root: View): List<String> {
        fun find(v: View): SubtitleView? =
            v as? SubtitleView ?: (v as? ViewGroup)?.let { g -> (0 until g.childCount).firstNotNullOfOrNull { find(g.getChildAt(it)) } }
        val view = checkNotNull(find(root)) { "No subtitle view" }
        val field = SubtitleView::class.java.getDeclaredField("cues").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        return (field.get(view) as List<Cue>).map { it.text.toString() }
    }

    @Test
    fun `the player's subtitles go to the subtitle view, and follow it`() {
        val player = SubtitledPlayer("¿Dónde está la biblioteca?")
        lateinit var root: View
        compose.setContent {
            root = LocalView.current
            Subtitles(player)
        }
        compose.waitForIdle()
        assertEquals(listOf("¿Dónde está la biblioteca?"), shown(root))
        compose.runOnUiThread { player.show("Está aquí.") }
        compose.waitForIdle()
        assertEquals(listOf("Está aquí."), shown(root))
    }
}
