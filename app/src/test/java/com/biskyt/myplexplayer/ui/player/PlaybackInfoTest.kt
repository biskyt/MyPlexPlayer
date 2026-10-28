package com.biskyt.myplexplayer.ui.player

import androidx.media3.common.C
import androidx.media3.common.ColorInfo
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.TrackGroup
import androidx.media3.common.Tracks
import com.biskyt.myplexplayer.testutil.PlainApp
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** What Playback info says about the picture and the sound. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = PlainApp::class)
class PlaybackInfoTest {
    private fun playing(vararg formats: Format) = Tracks(
        formats.map { Tracks.Group(TrackGroup(it), false, intArrayOf(C.FORMAT_HANDLED), booleanArrayOf(true)) }
    )

    private val dolbyVision = Format.Builder().setSampleMimeType(
        MimeTypes.VIDEO_DOLBY_VISION
    ).setCodecs("dvhe.08.06").setWidth(3840).setHeight(2160)
        .setFrameRate(23.976f).build()
    private val hdr10 = Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H265).setWidth(3840).setHeight(1600).setFrameRate(25f)
        .setColorInfo(ColorInfo.Builder().setColorTransfer(C.COLOR_TRANSFER_ST2084).build()).build()
    private val sound = Format.Builder().setSampleMimeType(MimeTypes.AUDIO_E_AC3).setChannelCount(6).setLanguage("en").build()

    @Test
    fun `the picture says its size, codec, dynamic range and frame rate`() {
        assertEquals("3840×2160 · HEVC · Dolby Vision 8 · 23.976 fps", video(dolbyVision))
        assertEquals("3840×1600 · HEVC · HDR10 · 25 fps", video(hdr10))
    }

    @Test
    fun `the sound says its codec, channels and language`() {
        assertEquals("Dolby Digital Plus · 6ch · English", audio(sound))
        val aac = Format.Builder().setSampleMimeType(MimeTypes.AUDIO_AAC).setLanguage(C.LANGUAGE_UNDETERMINED)
        assertEquals("AAC · Stereo", audio(aac.setChannelCount(2).build()))
        assertEquals("AAC · 1ch", audio(aac.setChannelCount(1).build()))
    }

    @Test
    fun `the panel lists the playback format, the picture, its decoder, the sound, its output and dropped frames`() {
        val lines = playbackInfo(playing(hdr10, sound), "Direct Play", "OMX.hevc", "Passthrough", dropped = 2)
        assertEquals(
            listOf(
                "Playback format: Direct Play",
                "Video: 3840×1600 · HEVC · HDR10 · 25 fps",
                "Video decoder: OMX.hevc",
                "Audio: Dolby Digital Plus · 6ch · English",
                "Audio output: Passthrough",
                "Dropped frames: 2",
            ),
            lines
        )
    }

    @Test
    fun `before anything plays, the picture is unknown and the rest left out`() {
        assertEquals(listOf("Video: unknown"), playbackInfo(Tracks.EMPTY, null, null, "", null))
    }
}
