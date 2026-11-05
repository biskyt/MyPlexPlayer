package com.biskyt.myplexplayer.core.plex

import java.util.Locale

/**
 * What a film's or episode's file is, from its details: the [badges] shown while it has focus ("4K", "Dolby Vision",
 * "Dolby Atmos 7.1") and the facts that Technical info lists. Unknown parts are null.
 */
data class PlexMediaInfo(
    val badges: List<String>,
    val video: PlexVideoTrack?,
    /** Every soundtrack, in the file's order. */
    val audioTracks: List<PlexAudioTrack>,
    /** Every subtitle track, in the file's order. */
    val subtitleTracks: List<PlexSubtitleTrack>,
    val sizeBytes: Long?,
    val bitrateKbps: Int?,
    val container: String?,
) {
    companion object {
        private const val VIDEO = 1
        private const val AUDIO = 2
        private const val SUBTITLES = 3

        fun of(item: PlexItem): PlexMediaInfo? {
            val media = item.media.firstOrNull() ?: return null
            val part = media.parts.firstOrNull()
            val streams = part?.streams.orEmpty()
            return PlexMediaInfo(
                badges = badges(media, streams),
                video = streams.firstOrNull { it.streamType == VIDEO }?.let(::videoTrack),
                audioTracks = streams.filter { it.streamType == AUDIO }.map { a ->
                    PlexAudioTrack(a.language ?: a.title, soundName(a.codec, isAtmos(a)), a.channels?.let(::layout), a.bitrate)
                },
                subtitleTracks = streams.filter { it.streamType == SUBTITLES }.map { s ->
                    PlexSubtitleTrack(s.language ?: s.title ?: s.displayTitle, s.codec?.uppercase(Locale.ROOT), s.forced)
                },
                sizeBytes = part?.size,
                bitrateKbps = media.bitrate,
                container = containerOf(item),
            )
        }

        /** Only [item]'s badges ("4K", "HDR10", "Dolby Digital+ 5.1"), as an episode's row needs them. */
        fun badgesOf(item: PlexItem): List<String> {
            val media = item.media.firstOrNull() ?: return emptyList()
            return badges(media, media.parts.firstOrNull()?.streams.orEmpty())
        }

        /** Only [item]'s container ("MKV"), as Playback info names it. */
        fun containerOf(item: PlexItem): String? {
            val media = item.media.firstOrNull() ?: return null
            return (media.parts.firstOrNull()?.container ?: media.container)?.uppercase(Locale.ROOT)
        }

        private fun badges(media: PlexMedia, streams: List<PlexStreamInfo>): List<String> {
            val video = streams.firstOrNull { it.streamType == VIDEO }
            val audio = streams.firstOrNull { it.streamType == AUDIO }
            val sound = if (audio?.let(::isAtmos) == true) "Dolby Atmos" else codecName(audio?.codec ?: media.audioCodec)
            return listOfNotNull(
                resolution(media.videoResolution),
                video?.let { if (it.doviPresent) "Dolby Vision" else baseRange(it) },
                sound?.let { listOfNotNull(it, (audio?.channels ?: media.audioChannels)?.let(::layout)).joinToString(" ") },
            )
        }

        /** "4K", "1080p", "SD", from the server's videoResolution ("4k", "1080", "sd"). */
        fun resolution(value: String?): String? = when (val v = value?.lowercase(Locale.ROOT)) {
            null, "" -> null
            "4k" -> "4K"
            "sd" -> "SD"
            else -> if (v.all(Char::isDigit)) "${v}p" else value
        }

        /** The high dynamic range the picture's base layer has ("HDR10+", "HDR10", "HLG"), if any. */
        private fun baseRange(video: PlexStreamInfo): String? = when {
            listOfNotNull(video.displayTitle, video.extendedDisplayTitle).any { it.contains("HDR10+", ignoreCase = true) } -> "HDR10+"
            video.colorTrc == "smpte2084" -> "HDR10"
            video.colorTrc == "arib-std-b67" -> "HLG"
            else -> null
        }

        private fun isAtmos(audio: PlexStreamInfo) =
            listOfNotNull(audio.displayTitle, audio.extendedDisplayTitle, audio.title).any { it.contains("Atmos", ignoreCase = true) }

        /**
         * A soundtrack's format, by the same names as its badge ([codecName]): Atmos is added to its codec's name
         * ("TrueHD Atmos"), where the badge says only "Dolby Atmos".
         */
        private fun soundName(codec: String?, atmos: Boolean): String? =
            codecName(codec)?.let { if (atmos) "$it Atmos" else it } ?: if (atmos) "Dolby Atmos" else null

        /** The one table of sound codecs' names, for the badges and Technical info alike. */
        private fun codecName(codec: String?): String? = when (codec?.lowercase(Locale.ROOT)) {
            null, "" -> null
            "truehd" -> "TrueHD"
            "eac3" -> "Dolby Digital+"
            "ac3" -> "Dolby Digital"
            "dca", "dts" -> "DTS"
            "dca-ma" -> "DTS-HD MA"
            else -> codec.uppercase(Locale.ROOT)
        }

        /** "7.1", "5.1"; nothing for stereo or mono (words the app would have to translate, and the usual case). */
        private fun layout(channels: Int): String? = if (channels > 2) "${channels - 1}.1" else null

        private fun videoTrack(v: PlexStreamInfo) = PlexVideoTrack(
            codec = listOfNotNull(v.codec?.let(::videoCodecName), v.profile?.let(::profileName)).joinToString(" ").ifEmpty { null },
            width = v.width,
            height = v.height,
            frameRate = v.frameRate,
            dynamicRange = dynamicRange(v),
        )

        /** The usual name of a video codec, as the player's Playback info writes it ("HEVC", "AVC"). */
        private fun videoCodecName(codec: String): String = when (codec.lowercase(Locale.ROOT)) {
            "h264" -> "AVC"
            "h265" -> "HEVC"
            "mpeg2video" -> "MPEG-2"
            else -> codec.uppercase(Locale.ROOT)
        }

        /** "main 10" as "Main 10". */
        private fun profileName(profile: String): String =
            profile.split(' ').filter { it.isNotEmpty() }.joinToString(" ") { w -> w.replaceFirstChar { it.titlecase(Locale.ROOT) } }

        /**
         * Each high dynamic range the picture has: Dolby Vision with its profile ("Dolby Vision 8.1"), then what its
         * base layer is ("HDR10"), or the one alone.
         */
        private fun dynamicRange(v: PlexStreamInfo): List<String> {
            if (!v.doviPresent) return listOfNotNull(baseRange(v))
            val profile = v.doviProfile?.let { p -> " $p" + (v.doviCompatId?.takeIf { it > 0 }?.let { ".$it" } ?: "") }.orEmpty()
            return listOfNotNull("Dolby Vision$profile", baseRange(v))
        }
    }
}

/**
 * A file's picture: [codec] with its profile ("HEVC Main 10"), its size and frame rate, and each [dynamicRange] it has
 * ("Dolby Vision 8.1", "HDR10").
 */
data class PlexVideoTrack(
    val codec: String?,
    val width: Int?,
    val height: Int?,
    val frameRate: Double?,
    val dynamicRange: List<String>,
)

/**
 * A soundtrack: its [language] as the server names it ("English"), its [format] by the badges' names ("TrueHD Atmos"),
 * its [layout] ("7.1"; null for stereo or mono), and its [bitrateKbps].
 */
data class PlexAudioTrack(val language: String?, val format: String?, val layout: String?, val bitrateKbps: Int?)

/** A subtitle track: its [language] ("English"), its [format] ("SRT", "PGS") and whether it is [forced]. */
data class PlexSubtitleTrack(val language: String?, val format: String?, val forced: Boolean)
