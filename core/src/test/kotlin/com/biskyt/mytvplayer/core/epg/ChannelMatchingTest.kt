package com.biskyt.mytvplayer.core.epg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChannelMatchingTest {

    @Test
    fun `normalize strips provider noise`() {
        val cases = mapOf(
            "UK: ARENA SPRTS 1 FHD *RAW*" to "arena sprts 1",
            "UK| MOTOR TV ᴴᴰ" to "motor tv",
            "UK - COOK&CO" to "cook and co",
            "|EN| Doc Planet +1" to "doc planet plus1",
            "[UK] Kite Kids (Backup)" to "kite kids",
            "Channel One HD" to "channel one",
            "Télé Évasion 4K" to "tele evasion",
            "Arena Sports 1 HEVC 50fps" to "arena sports 1",
        )
        cases.forEach { (input, expected) -> assertEquals(input, expected, ChannelNames.normalize(input)) }
    }

    private fun candidate(xmlId: String, name: String, source: Long = 1) = EpgCandidate(source, xmlId, name, ChannelNames.normalize(name))

    private val candidates = listOf(
        candidate("arena1.uk", "Arena Sports 1 HD"),
        candidate("arena10.uk", "Arena Sports 10"),
        candidate("arena2.uk", "Arena Sports 2"),
        candidate("docplanet.uk", "Doc Planet"),
        candidate("docplanet1.uk", "Doc Planet +1"),
        candidate("kite.uk", "Kite Kids"),
        candidate("one.uk", "Channel One"),
    )
    private val matcher = ChannelMatcher(candidates)

    @Test
    fun `provider epg id wins`() {
        val m = matcher.autoMatch("something unrelated", "KITE.uk")!!
        assertEquals("kite.uk", m.candidate.xmlId)
        assertEquals(1.0, m.score, 0.0)
    }

    @Test
    fun `exact normalized name auto-matches`() {
        assertEquals("arena1.uk", matcher.autoMatch("UK: ARENA SPORTS 1 FHD", null)!!.candidate.xmlId)
        assertEquals("docplanet1.uk", matcher.autoMatch("UK| DOC PLANET+1", null)!!.candidate.xmlId)
        assertEquals("one.uk", matcher.autoMatch("UK| CH ONE HD", null)?.candidate?.xmlId ?: matcher.suggestions("UK| CH ONE HD", null).first().candidate.xmlId)
    }

    @Test
    fun `numbers must agree`() {
        val best = matcher.suggestions("Arena Sports 1", null)
        assertEquals("arena1.uk", best.first().candidate.xmlId)
        val ten = best.firstOrNull { it.candidate.xmlId == "arena10.uk" }
        assertTrue(ten == null || ten.score < ChannelMatcher.AUTO_THRESHOLD)
    }

    @Test
    fun `typos give suggestions but not automatic matches when unsure`() {
        val suggestions = matcher.suggestions("UK: ARENA SPRTS 1 FHD *RAW*", null)
        assertEquals("arena1.uk", suggestions.first().candidate.xmlId)
        assertNull(matcher.autoMatch("Totally Different", null))
    }

    @Test
    fun `jaro winkler basics`() {
        assertEquals(1.0, ChannelMatcher.jaroWinkler("abc", "abc"), 0.0)
        assertEquals(0.0, ChannelMatcher.jaroWinkler("", "abc"), 0.0)
        assertTrue(ChannelMatcher.jaroWinkler("martha", "marhta") > 0.95)
    }

    @Test
    fun `language detector reads common prefixes`() {
        val cases = mapOf(
            "UK | SPORTS" to "UK", "UK| NEWS" to "UK", "UK - KIDS" to "UK", "|EN| ENTERTAINMENT" to "UK",
            "FR: CINEMA" to "FR", "[DE] Kinder" to "DE", "AR | MBC" to "AR", "USA: NBA" to "US", "EX-YU | Sport" to null,
            "EXYU | Sport" to "EXYU", "FRENCH MOVIES" to "FR", "Movies 24/7" to null, "NEWS" to null,
        )
        cases.forEach { (input, expected) -> assertEquals(input, expected, CategoryLanguageDetector.detect(input)) }
        assertEquals("English (UK)", CategoryLanguageDetector.label("UK"))
        assertEquals("Other", CategoryLanguageDetector.label(null))
    }
}
