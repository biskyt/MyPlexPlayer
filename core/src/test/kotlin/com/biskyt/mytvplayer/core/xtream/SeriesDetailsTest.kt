package com.biskyt.mytvplayer.core.xtream

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class SeriesDetailsTest {
    @Test
    fun `a season listed twice is shown once`() {
        // The app lists seasons in a row keyed by number: the same number twice would crash it.
        val details = Json.parseToJsonElement(
            """{"seasons":[{"season_number":1,"name":"Season 1"},{"season_number":1,"name":"Series 1"},{"season_number":2}],
               "episodes":{"1":[{"id":11,"episode_num":1}],"2":[{"id":21,"episode_num":1}]}}"""
        ).toSeriesDetails()!!
        assertEquals(listOf(1, 2), details.seasons.map { it.number })
        assertEquals("Season 1", details.seasons.first().name) // the first one listed
    }
}
