package com.biskyt.mytvplayer.core.vod

import org.junit.Assert.assertEquals
import org.junit.Test

class VodTitlesTest {
    @Test
    fun `provider decorations are removed and the year kept`() {
        val cases = mapOf(
            "EN - The Matrix (1999)" to CleanTitle("The Matrix", 1999),
            "|UK| Line of Duty" to CleanTitle("Line of Duty", null),
            "[4K] Dune [2021]" to CleanTitle("Dune", 2021),
            "|EN| [4K] Oppenheimer - 2023" to CleanTitle("Oppenheimer", 2023),
            "FR: Amélie (2001)" to CleanTitle("Amélie", 2001),
            "Blade Runner 2049" to CleanTitle("Blade Runner 2049", null),
            "1917 (2019)" to CleanTitle("1917", 2019),
            "Up" to CleanTitle("Up", null),
            "Star Wars: A New Hope" to CleanTitle("Star Wars: A New Hope", null),
        )
        cases.forEach { (input, expected) -> assertEquals(input, expected, VodTitles.clean(input)) }
    }
}
