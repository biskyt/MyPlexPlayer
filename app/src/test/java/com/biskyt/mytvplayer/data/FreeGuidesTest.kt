package com.biskyt.mytvplayer.data

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FreeGuidesTest {
    @Test
    fun `the TV's country comes first, the rest by name, with iptv-epg addresses`() {
        val uk = FreeGuides.all(Locale.UK, Locale.UK)
        assertEquals("gb", uk.first().code)
        assertEquals("https://iptv-epg.org/files/epg-gb.xml.gz", uk.first().url)
        assertEquals("United Kingdom (iptv-epg.org)", uk.first().sourceName)
        val rest = uk.drop(1).map { it.country }
        assertEquals(rest.sorted(), rest)
        assertEquals("us", FreeGuides.all(Locale.US, Locale.UK).first().code)
        assertTrue(FreeGuides.all(Locale("", "AQ"), Locale.UK).first().code == "al") // no guide for the TV's country: alphabetical
    }
}
