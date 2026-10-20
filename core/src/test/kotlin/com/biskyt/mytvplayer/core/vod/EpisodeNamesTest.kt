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
}
