package com.biskyt.mytvplayer.core.player

import com.biskyt.mytvplayer.core.player.OverlayState.CONTROLS
import com.biskyt.mytvplayer.core.player.OverlayState.GUIDE
import com.biskyt.mytvplayer.core.player.OverlayState.NONE
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlayerLogicTest {
    private val mapper = KeyActionMapper(skipBackMs = 10_000, skipForwardMs = 30_000)

    @Test
    fun `left and right skip by configured amounts`() {
        assertEquals(PlayerAction.SeekBy(-10_000), mapper.map(RemoteKey.LEFT, NONE, isLive = true))
        assertEquals(PlayerAction.SeekBy(30_000), mapper.map(RemoteKey.RIGHT, CONTROLS, isLive = false))
        assertEquals(PlayerAction.SeekBy(-5_000), KeyActionMapper(5_000, 60_000).map(RemoteKey.REWIND, NONE, isLive = false))
    }

    @Test
    fun `up and down zap only on live tv with nothing open`() {
        assertEquals(PlayerAction.Zap(-1), mapper.map(RemoteKey.UP, NONE, isLive = true))
        assertEquals(PlayerAction.Zap(1), mapper.map(RemoteKey.DOWN, NONE, isLive = true))
        assertEquals(PlayerAction.None, mapper.map(RemoteKey.DOWN, CONTROLS, isLive = true))
        assertEquals(PlayerAction.None, mapper.map(RemoteKey.UP, NONE, isLive = false))
    }

    @Test
    fun `back closes overlays first then stops playback`() {
        assertEquals(PlayerAction.CloseGuide, mapper.map(RemoteKey.BACK, GUIDE, isLive = true))
        assertEquals(PlayerAction.HideOverlay, mapper.map(RemoteKey.BACK, CONTROLS, isLive = true))
        assertEquals(PlayerAction.StopAndExit, mapper.map(RemoteKey.BACK, NONE, isLive = true))
        assertEquals(PlayerAction.StopAndExit, mapper.map(RemoteKey.STOP, GUIDE, isLive = true))
    }

    @Test
    fun `guide, ok and digits`() {
        assertEquals(PlayerAction.OpenGuide, mapper.map(RemoteKey.GUIDE, NONE, isLive = true))
        assertEquals(PlayerAction.None, mapper.map(RemoteKey.GUIDE, NONE, isLive = false))
        assertEquals(PlayerAction.None, mapper.map(RemoteKey.LEFT, GUIDE, isLive = true))
        assertEquals(PlayerAction.ToggleOverlay, mapper.map(RemoteKey.OK, NONE, isLive = true))
        assertEquals(PlayerAction.ToggleOverlay, mapper.map(RemoteKey.OK, CONTROLS, isLive = true))
        assertEquals(PlayerAction.Digit(7), mapper.map(RemoteKey.DIGIT, NONE, isLive = true, digit = 7))
    }

    private val tracks = listOf(
        TextTrack("full-en", "en", forced = false),
        TextTrack("forced-en", "eng", forced = true),
        TextTrack("full-fr", "fr-FR", forced = false),
        TextTrack("forced-fr", "fra", forced = true)
    )

    @Test
    fun `subtitle policy`() {
        assertNull(SubtitlePolicy.choose(tracks, SubtitleMode.OFF, "en"))
        assertEquals("forced-en", SubtitlePolicy.choose(tracks, SubtitleMode.FORCED_ONLY, "en-GB")?.id)
        assertEquals("forced-fr", SubtitlePolicy.choose(tracks, SubtitleMode.FORCED_ONLY, "fr")?.id)
        assertNull(SubtitlePolicy.choose(tracks, SubtitleMode.FORCED_ONLY, "de"))
        assertEquals("full-en", SubtitlePolicy.choose(tracks, SubtitleMode.ALWAYS, "en")?.id)
        assertEquals("forced-x", SubtitlePolicy.choose(listOf(TextTrack("forced-x", null, true)), SubtitleMode.FORCED_ONLY, "en")?.id)
        assertEquals("forced-en", SubtitlePolicy.choose(tracks.drop(2) + tracks[1], SubtitleMode.ALWAYS, "en")?.id)
    }
}
