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
    fun `badges and file info for a dolby vision film with atmos`() {
        val video =
            PlexStreamInfo(
                streamType = 1,
                codec = "hevc",
                width = 3840,
                height = 2160,
                frameRate = 23.976,
                colorTrc = "smpte2084",
                doviPresent = true,
                doviProfile = 8
            )
        val subs =
            listOf(PlexStreamInfo(streamType = 3, language = "English"), PlexStreamInfo(streamType = 3, language = "French", forced = true))
        val info = PlexMediaInfo.of(film(video, atmos, PlexStreamInfo(streamType = 2, codec = "ac3", channels = 6), *subs.toTypedArray()))!!
        assertEquals(listOf("4K", "Dolby Vision", "Dolby Atmos 7.1"), info.badges)
        assertEquals("3840 × 2160 · HEVC · Dolby Vision 8 · 23.976 fps", info.picture)
        assertEquals("English (TRUEHD 7.1)", info.sound)
        assertEquals(1, info.otherSoundtracks)
        assertEquals(listOf("English" to false, "French" to true), info.subtitles)
        assertEquals("MKV", info.container)
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
