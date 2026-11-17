package com.biskyt.myplexplayer.data

import com.biskyt.myplexplayer.data.db.VodCard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Which of the server's films or series is a TMDB title: the same title (or original title), and for a film a year
 * either way. MyTVPlayer's PersonLibraryTest cases, asked of the matcher itself.
 */
class PlaylistMatcherTest {
    private val films = PlaylistMatcher(
        listOf(
            VodCard(1, "The Green Mile", 1999, null),
            VodCard(2, "Heat", 1995, null),
            VodCard(3, "Dune", 1984, null),
            VodCard(4, "Dune", 2021, null)
        ),
        series = false
    )
    private val shows = PlaylistMatcher(listOf(VodCard(77, "Line of Duty", 2012, null)), series = true)

    @Test
    fun `a film with the server's title and a year either way is found`() {
        assertEquals(2L, films.find("Heat", null, 1996)?.id)
        assertNull(films.find("Heat", null, 1997))
        // Two films of one name: the one of its year.
        assertEquals(4L, films.find("Dune", null, 2021)?.id)
    }

    @Test
    fun `a title in the app's language is found by its original title`() {
        assertEquals(1L, films.find("La Ligne verte", "The Green Mile", 1999)?.id)
    }

    @Test
    fun `a series is found whatever its year`() {
        assertEquals(77L, shows.find("Line of Duty", null, 2016)?.id)
        assertNull("a film is not looked for among the series", films.find("Line of Duty", null, 2012))
    }
}
