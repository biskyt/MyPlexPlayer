package com.biskyt.mytvplayer.core.vod

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EpisodeNamesTest {
    @Test
    fun `the ways providers number episodes`() {
        assertEquals(EpisodeName("Breaking Bad", 1, 1, null), EpisodeNames.parse("Breaking Bad S01E01"))
        assertEquals(EpisodeName("EN - The Office (US)", 2, 3, null), EpisodeNames.parse("EN - The Office (US) S02 E03"))
        assertEquals(EpisodeName("Line of Duty", 6, 7, "Episode Seven"), EpisodeNames.parse("Line of Duty - S06E07 - Episode Seven"))
        assertEquals(EpisodeName("Friends", 10, 17, null), EpisodeNames.parse("Friends S10.E17"))
        assertEquals(EpisodeName("Doctor Who", 1, 5, "Rose"), EpisodeNames.parse("Doctor Who 1x05 Rose"))
        assertEquals(EpisodeName("Planet Earth", 1, 2, null), EpisodeNames.parse("Planet Earth Season 1 Episode 2"))
        assertEquals(EpisodeName("Caminandes", 1, 2, "Gran Dillama"), EpisodeNames.parse("Caminandes S01E02: Gran Dillama"))
        assertEquals(EpisodeName("Show", 1, 2, null), EpisodeNames.parse("Show (S01E02)"))
        assertEquals(EpisodeName("Show", 1, 5, null), EpisodeNames.parse("Show [1x05]"))
        assertEquals(EpisodeName("Show", 1, 1, "Double"), EpisodeNames.parse("Show S01E01E02 Double"))
    }

    @Test
    fun `file-style names get their spaces back, and release tags aren't titles`() {
        assertEquals(EpisodeName("Show Name", 3, 10, null), EpisodeNames.parse("Show.Name.S03E10.1080p.WEB-DL"))
        assertEquals(EpisodeName("Show Name", 3, 10, null), EpisodeNames.parse("Show_Name_S03E10"))
    }

    @Test
    fun `films stay films`() {
        listOf(
            "Dune (2021)", "EN - Big Buck Bunny (2008)", "2001: A Space Odyssey (1968)", "Se7en (1995)", "Ocean's 11 (2001)",
            "1917 (2019)", "S1m0ne (2002)", "Mission: Impossible - Dead Reckoning (2023)", "Seasons of Love", "The Episode",
        ).forEach { assertNull(it, EpisodeNames.parse(it)) }
    }

    @Test
    fun `a number with no show before it isn't an episode`() {
        assertNull(EpisodeNames.parse("S01E01"))
    }

    private fun entry(name: String, type: String? = null, series: String? = null, season: Int? = null, episode: Int? = null) =
        com.biskyt.mytvplayer.core.m3u.M3uEntry(
            name, "http://x", null, null, null, null, null, null, type = type, seriesName = series, season = season, episode = episode
        )

    @Test
    fun `what the playlist says comes first, then the name`() {
        // A film named like an episode, said to be a film.
        assertNull(EpisodeNames.of(entry("Flash Gordon S01E02", type = "movie"), seriesFeed = true))
        assertEquals(false, EpisodeNames.isEpisode(entry("Flash Gordon S01E02", type = "movie"), seriesFeed = true))
        // Said to be an episode: its series and numbers, and the title from the name.
        assertEquals(
            EpisodeName("Bonanza", 1, 21, "Episode 21"),
            EpisodeNames.of(entry("Bonanza S01E21 - Episode 21", "series", "Bonanza", 1, 21), seriesFeed = false)
        )
        assertEquals(EpisodeName("Bonanza", 2, null, null), EpisodeNames.of(entry("The Ride", "series", "Bonanza", season = 2), false))
        // Nothing said: by the name, or a series playlist's.
        assertEquals(EpisodeName("Show", 1, 2, null), EpisodeNames.of(entry("Show S01E02"), seriesFeed = false))
        assertNull(EpisodeNames.of(entry("Dune (2021)"), seriesFeed = false))
        assertEquals(EpisodeName("Llama Drama", 1, null, null), EpisodeNames.of(entry("Llama Drama"), seriesFeed = true))
        assertEquals(true, EpisodeNames.isEpisode(entry("Llama Drama", type = "live"), seriesFeed = true))
    }
}
