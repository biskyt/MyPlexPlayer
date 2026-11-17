package com.biskyt.myplexplayer.core.plex

import org.junit.Assert.assertEquals
import org.junit.Test

/** How "Other trailers" names a server's trailer or teaser ([PlexTrailer.label]). */
class PlexTrailerTest {
    private fun label(title: String?, teaser: Boolean = false, trailerWord: String = "Trailer", teaserWord: String = "Teaser") =
        PlexTrailer(1, title, teaser).label(trailerWord, teaserWord)

    @Test
    fun `a title that names its kind is shown as it is`() {
        assertEquals("Official Trailer", label("Official Trailer"))
        assertEquals("First teaser", label("First teaser", teaser = true))
    }

    @Test
    fun `a title that does not name its kind is followed by it`() {
        assertEquals("Spot · Teaser", label("Spot", teaser = true))
        assertEquals("Spot · Trailer", label("Spot"))
    }

    @Test
    fun `a trailer with no title is named by its kind`() {
        assertEquals("Trailer", label(null))
        assertEquals("Teaser", label(null, teaser = true))
    }

    @Test
    fun `in another language, the app's word or the English one counts`() {
        assertEquals("Official Trailer", label("Official Trailer", trailerWord = "Bande-annonce"))
        assertEquals("Bande-annonce finale", label("Bande-annonce finale", trailerWord = "Bande-annonce"))
        assertEquals("Spot · Teaser FR", label("Spot", teaser = true, teaserWord = "Teaser FR"))
        assertEquals("Spot · Avance", label("Spot", teaser = true, teaserWord = "Avance"))
    }
}
