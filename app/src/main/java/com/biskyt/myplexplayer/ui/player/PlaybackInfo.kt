@file:OptIn(UnstableApi::class)

package com.biskyt.myplexplayer.ui.player

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import com.biskyt.myplexplayer.R
import com.biskyt.myplexplayer.appString
import com.biskyt.myplexplayer.player.channelLayout
import com.biskyt.myplexplayer.player.encodedName
import com.biskyt.myplexplayer.player.selectedFormat
import com.biskyt.myplexplayer.ui.common.displayLanguage
import java.util.Locale

/**
 * What the Playback info panel lists, a line each: the playback format ([how]: Plex only), the picture and its
 * [decoder], the sound and where it goes ([audioOutput]), and frames [dropped].
 */
fun playbackInfo(tracks: Tracks, how: String?, decoder: String?, audioOutput: String, dropped: Int?): List<String> = listOfNotNull(
    how?.let { appString(R.string.player_info_playback, it) },
    selectedFormat(tracks, C.TRACK_TYPE_VIDEO)?.let { appString(R.string.player_info_video, video(it)) }
        ?: appString(R.string.player_video_unknown),
    decoder?.let { appString(R.string.player_info_decoder, it) },
    selectedFormat(tracks, C.TRACK_TYPE_AUDIO)?.let { appString(R.string.player_info_audio, audio(it)) },
    audioOutput.takeIf { it.isNotEmpty() }?.let { appString(R.string.player_audio_output, it) },
    dropped?.let { appString(R.string.player_info_dropped, it) },
)

/** "3840×2160 · HEVC · Dolby Vision · 23.976 fps". */
internal fun video(f: Format): String = listOfNotNull(
    "${f.width}×${f.height}".takeIf { f.width > 0 && f.height > 0 },
    codec(f),
    dynamicRange(f),
    f.frameRate.takeIf { it > 0 }?.let { "%.3f".format(Locale.ROOT, it).trimEnd('0').trimEnd('.') + " fps" },
).joinToString(" · ")

/** "Dolby Digital Plus · 6ch · English". */
internal fun audio(f: Format): String = listOfNotNull(
    encodedName(f.sampleMimeType) ?: codec(f),
    // "Stereo" for two, a count otherwise (mono is "1ch").
    f.channelCount.takeIf { it > 0 }?.let { if (it == 2) channelLayout(it) else appString(R.string.player_track_channels, it) },
    f.language?.takeIf { it != C.LANGUAGE_UNDETERMINED }?.let(::displayLanguage),
).joinToString(" · ")

/**
 * The codec's usual name, else its MIME subtype; for Dolby Vision, the codec it's built on (its codecs string: dvhe/dvh1
 * HEVC, dvav/dva1 AVC, dav1 AV1).
 */
private fun codec(f: Format): String? {
    val codecs = f.codecs.orEmpty()
    return when (f.sampleMimeType) {
        MimeTypes.VIDEO_DOLBY_VISION -> when {
            codecs.startsWith("dvh") -> "HEVC"
            codecs.startsWith("dva") -> "AVC"
            codecs.startsWith("dav1") -> "AV1"
            else -> null
        }
        MimeTypes.VIDEO_H264 -> "AVC"
        MimeTypes.VIDEO_VP9 -> "VP9"
        MimeTypes.VIDEO_AV1 -> "AV1"
        MimeTypes.AUDIO_AAC -> "AAC"
        MimeTypes.AUDIO_RAW -> "PCM"
        else -> f.sampleMimeType?.substringAfter('/')?.uppercase(Locale.ROOT)
    }
}

/** The picture's high dynamic range, if it has one (brand names, the same in every language). */
private fun dynamicRange(f: Format): String? = when {
    f.sampleMimeType == MimeTypes.VIDEO_DOLBY_VISION ->
        // With its profile, from the codecs string ("dvhe.08.06": 8).
        listOfNotNull("Dolby Vision", f.codecs?.split('.')?.getOrNull(1)?.toIntOrNull()?.toString()).joinToString(" ")
    f.colorInfo?.colorTransfer == C.COLOR_TRANSFER_ST2084 -> "HDR10"
    f.colorInfo?.colorTransfer == C.COLOR_TRANSFER_HLG -> "HLG"
    else -> null
}
