package com.biskyt.myplexplayer.core.plex

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlexMediaInfoTest {
    private fun film(video: PlexStreamInfo, vararg others: PlexStreamInfo, resolution: String = "4k") = PlexItem(
        ratingKey = "1",
        media = listOf(
            PlexMedia(
                videoResolution = resolution,
                bitrate = 46_000,
                container = "mkv",
                parts = listOf(PlexPart(size = 38_600_000_000, container = "mkv", streams = listOf(video) + others)),
            )
        ),
    )

    private val atmos =
        PlexStreamInfo(
            streamType = 2,
            codec = "truehd",
            channels = 8,
            displayTitle = "English (TRUEHD 7.1)",
            extendedDisplayTitle = "English (TrueHD Atmos 7.1)"
        )

    @Test
    fun `badges and technical info for a dolby vision film with atmos`() {
        val video =
            PlexStreamInfo(
                streamType = 1,
                codec = "hevc",
                profile = "main 10",
                width = 3840,
                height = 2160,
                frameRate = 23.976,
                colorTrc = "smpte2084",
                doviPresent = true,
                doviProfile = 8,
                doviCompatId = 1
            )
        val subs = listOf(
            PlexStreamInfo(streamType = 3, language = "English", codec = "pgs"),
            PlexStreamInfo(streamType = 3, language = "French", codec = "srt", forced = true)
        )
        val ac3 = PlexStreamInfo(streamType = 2, codec = "ac3", channels = 6, language = "French", bitrate = 640)
        val info = PlexMediaInfo.of(film(video, atmos.copy(bitrate = 4_200), ac3, *subs.toTypedArray()))!!
        assertEquals(listOf("4K", "Dolby Vision", "Dolby Atmos 7.1"), info.badges)
        assertEquals(PlexVideoTrack("HEVC Main 10", 3840, 2160, 23.976, listOf("Dolby Vision 8.1", "HDR10")), info.video)
        assertEquals(
            listOf(PlexAudioTrack(null, "TrueHD Atmos", "7.1", 4_200), PlexAudioTrack("French", "Dolby Digital", "5.1", 640)),
            info.audioTracks
        )
        assertEquals(listOf(PlexSubtitleTrack("English", "PGS", false), PlexSubtitleTrack("French", "SRT", true)), info.subtitleTracks)
        assertEquals("MKV", info.container)
        assertEquals(38_600_000_000, info.sizeBytes)
        assertEquals(46_000, info.bitrateKbps)
    }

    @Test
    fun `a picture's dynamic range alone, and an avc codec by its usual name`() {
        fun video(v: PlexStreamInfo) = PlexMediaInfo.of(film(v))!!.video!!
        assertEquals(listOf("HLG"), video(PlexStreamInfo(streamType = 1, colorTrc = "arib-std-b67")).dynamicRange)
        assertEquals(listOf("Dolby Vision 5"), video(PlexStreamInfo(streamType = 1, doviPresent = true, doviProfile = 5)).dynamicRange)
        assertEquals("AVC High", video(PlexStreamInfo(streamType = 1, codec = "h264", profile = "high")).codec)
        assertEquals(emptyList<String>(), video(PlexStreamInfo(streamType = 1)).dynamicRange)
    }

    @Test
    fun `hdr10, hlg and none, with plain audio`() {
        fun badges(trc: String?, codec: String = "eac3", channels: Int = 6) = PlexMediaInfo.of(
            film(
                PlexStreamInfo(streamType = 1, colorTrc = trc),
                PlexStreamInfo(streamType = 2, codec = codec, channels = channels),
                resolution = "1080"
            )
        )!!.badges
        assertEquals(listOf("1080p", "HDR10", "Dolby Digital+ 5.1"), badges("smpte2084"))
        assertEquals(listOf("1080p", "HLG", "AAC"), badges("arib-std-b67", "aac", 2))
        assertEquals(listOf("1080p", "Dolby Digital 5.1"), badges(null, "ac3"))
        assertNull(PlexMediaInfo.of(PlexItem(ratingKey = "1")))
    }

    @Test
    fun `links from plex's ids, for a film and a show`() {
        val guids = listOf(PlexGuid("imdb://tt0118799"), PlexGuid("tmdb://637"), PlexGuid("tvdb://1234"))
        assertEquals(
            listOf(
                "https://www.imdb.com/title/tt0118799/",
                "https://www.themoviedb.org/movie/637",
                "https://thetvdb.com/dereferrer/movie/1234"
            ),
            titleLinks(PlexItem(ratingKey = "1", type = "movie", guids = guids)).map { it.url }
        )
        assertEquals(
            listOf("TMDB" to "https://www.themoviedb.org/tv/637", "TVDB" to "https://thetvdb.com/dereferrer/series/1234"),
            titleLinks(PlexItem(ratingKey = "2", type = "show", guids = guids.drop(1))).map { it.name to it.url }
        )
    }
}
