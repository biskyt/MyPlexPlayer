package com.biskyt.mytvplayer.core.epg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CategoryGroupsTest {
    @Test
    fun `a playlist entry's country, or else its language, gives its group`() {
        assertEquals("SE", CategoryLanguageDetector.groupFor("se", "Swedish"))
        assertEquals("UK", CategoryLanguageDetector.groupFor("GB", null))
        assertEquals("UK", CategoryLanguageDetector.groupFor("UK", null))
        assertEquals("US", CategoryLanguageDetector.groupFor("usa", null)) // codes providers use too
        assertEquals("DE", CategoryLanguageDetector.groupFor(null, "ger"))
        assertEquals("ID", CategoryLanguageDetector.groupFor("ID", null)) // any country, not just the usual ones
        // No usable country: the language's main group, by name or ISO code; else the language's own name.
        assertEquals("FR", CategoryLanguageDetector.groupFor("INT", "French"))
        assertEquals("UK", CategoryLanguageDetector.groupFor(null, "eng"))
        assertEquals("DE", CategoryLanguageDetector.groupFor(null, "de"))
        assertEquals("Hungarian", CategoryLanguageDetector.groupFor(null, "Hungarian"))
        assertNull(CategoryLanguageDetector.groupFor("INT", null))
        assertNull(CategoryLanguageDetector.groupFor(null, "Klingonese"))
    }
}
