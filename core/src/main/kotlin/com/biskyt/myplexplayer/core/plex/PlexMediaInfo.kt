package com.biskyt.myplexplayer.core.plex

import java.util.Locale

/**
 * What a film's or episode's file is, from its details: the badges shown while it has focus ([resolution], [hdr],
 * [audio]: "4K", "Dolby Vision", "Dolby Atmos 7.1") and the File info lines. Unknown parts are null.
 */
data class PlexMediaInfo(
    val resolution: String?,
    val hdr: String?,
    val audio: String?,
    /** "3840 × 2160 · HEVC · Dolby Vision 8 · 23.976 fps". */
    val picture: String?,
    /** The main soundtrack as the server names it ("English (TRUEHD 7.1)"), and how many others there are. */
    val sound: String?,
    val otherSoundtracks: Int,
    /** Subtitle languages (or names), and whether each is forced. */
    val subtitles: List<Pair<String, Boolean>>,
    val sizeBytes: Long?,
    val bitrateKbps: Int?,
    val container: String?,
) {
    val badges: List<String> get() = listOfNotNull(resolution, hdr, audio)

    companion object {
        private const val VIDEO = 1
        private const val AUDIO = 2
        private const val SUBTITLES = 3

        fun of(item: PlexItem): PlexMediaInfo? {
            val media = item.media.firstOrNull() ?: return null
            val part = media.parts.firstOrNull()
            val streams = part?.streams.orEmpty()
            val video = streams.firstOrNull { it.streamType == VIDEO }
            val audios = streams.filter { it.streamType == AUDIO }
            val audio = audios.firstOrNull()
            return PlexMediaInfo(
                resolution = resolution(media.videoResolution),
                hdr = video?.let(::hdr),
                audio = audioBadge(audio?.codec ?: media.audioCodec, audio?.channels ?: media.audioChannels, audio?.let(::isAtmos) == true),
                picture = video?.let(::picture),
                sound = audio?.displayTitle,
                otherSoundtracks = (audios.size - 1).coerceAtLeast(0),
                subtitles = streams.filter { it.streamType == SUBTITLES }
                    .mapNotNull { s -> (s.language ?: s.displayTitle)?.let { it to s.forced } }.distinct(),
                sizeBytes = part?.size,
                bitrateKbps = media.bitrate,
                container = (part?.container ?: media.container)?.uppercase(Locale.ROOT),
            )
        }

        /** "4K", "1080p", "SD", from the server's videoResolution ("4k", "1080", "sd"). */
        fun resolution(value: String?): String? = when (val v = value?.lowercase(Locale.ROOT)) {
            null, "" -> null
            "4k" -> "4K"
            "sd" -> "SD"
            else -> if (v.all(Char::isDigit)) "${v}p" else value
        }

        private fun hdr(video: PlexStreamInfo): String? {
            val named = listOfNotNull(video.displayTitle, video.extendedDisplayTitle).joinToString(" ")
            return when {
                video.doviPresent -> "Dolby Vision"
                named.contains("HDR10+", ignoreCase = true) -> "HDR10+"
                video.colorTrc == "smpte2084" -> "HDR10"
                video.colorTrc == "arib-std-b67" -> "HLG"
                else -> null
            }
        }

        private fun isAtmos(audio: PlexStreamInfo) =
            listOfNotNull(audio.displayTitle, audio.extendedDisplayTitle, audio.title).any { it.contains("Atmos", ignoreCase = true) }

        private fun audioBadge(codec: String?, channels: Int?, atmos: Boolean): String? {
            val name = if (atmos) "Dolby Atmos" else codecName(codec) ?: return null
            return listOfNotNull(name, channels?.let(::layout)).joinToString(" ")
        }

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

        private fun picture(video: PlexStreamInfo): String? = listOfNotNull(
            if (video.width != null && video.height != null) "${video.width} × ${video.height}" else null,
            video.codec?.uppercase(Locale.ROOT)?.replace("H264", "H.264"),
            hdr(video)?.let { name -> video.doviProfile?.takeIf { video.doviPresent }?.let { "$name $it" } ?: name },
            video.frameRate?.let { "%s fps".format(Locale.ROOT, it.toBigDecimal().stripTrailingZeros().toPlainString()) },
        ).takeIf { it.isNotEmpty() }?.joinToString(" · ")
    }
}
