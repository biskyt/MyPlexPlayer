package com.biskyt.myplexplayer.data

import com.biskyt.myplexplayer.core.tmdb.TmdbSuggestion
import com.biskyt.myplexplayer.data.db.VodCard
import org.junit.Assert.assertEquals
import org.junit.Test

/** "More like this": TMDB's suggestions in TMDB's order, each with the server's card when it has the title. */
class SimilarPageTest {
    private fun suggestion(id: Int, title: String, year: Int?, original: String? = null, series: Boolean = false) =
        TmdbSuggestion(id, series, title, original, year, null, null)

    private val films = listOf(VodCard(1, "Heat", 1995, null), VodCard(2, "The Green Mile", 1999, null), VodCard(3, "Heat", 1995, null))

    private fun SimilarPage.found() = titles.map { it.suggestion.id to it.card?.id }

    @Test
    fun `suggestions are found by title or original title, in TMDB's order`() {
        val page = similarPage(
            listOf(
                suggestion(10, "Arrival", 2016),
                suggestion(11, "La Ligne verte", 1999, original = "The Green Mile"),
                suggestion(12, "Heat", 1996),
                suggestion(13, "Sicario", 2015),
            ),
            films,
            series = false
        )
        assertEquals(listOf(10 to null, 11 to 2L, 12 to 1L, 13 to null), page.found())
    }

    @Test
    fun `a film from another year is not the server's`() {
        assertEquals(listOf(14 to null), similarPage(listOf(suggestion(14, "Heat", 1986)), films, series = false).found())
    }

    @Test
    fun `a series is found whatever year the server gives it`() {
        // TMDB dates NCIS: Sydney from its first season (2023); sources often give the newest season's year.
        val shows = listOf(VodCard(7, "NCIS: Sydney (2025)", 2025, null), VodCard(8, "EN - NCIS", 2003, null))
        val page = similarPage(
            listOf(suggestion(20, "NCIS: Sydney", 2023, series = true), suggestion(21, "NCIS: Hawai'i", 2021, series = true)),
            shows,
            series = true
        )
        assertEquals(listOf(20 to 7L, 21 to null), page.found())
    }

    @Test
    fun `a card goes with the first suggestion that finds it`() {
        val page = similarPage(listOf(suggestion(30, "Heat", 1995), suggestion(31, "Heat", 1995)), listOf(films[0]), series = false)
        assertEquals(listOf(30 to 1L, 31 to null), page.found())
    }

    @Test
    fun `two series of one name each get their own card`() {
        // "The Office" from the UK and from the US: series years are not compared, so both match both cards.
        val shows = listOf(VodCard(7, "The Office", 2001, null), VodCard(8, "The Office", 2005, null))
        val page = similarPage(
            listOf(suggestion(40, "The Office", 2001, series = true), suggestion(41, "The Office", 2005, series = true)),
            shows,
            series = true
        )
        assertEquals(listOf(40 to 7L, 41 to 8L), page.found())
    }
}
