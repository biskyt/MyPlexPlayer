package com.biskyt.mytvplayer.core.m3u

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class M3uParserTest {
    private val playlist = """
        ﻿#EXTM3U x-tvg-url="https://epg.example.org/a.xml.gz,https://epg.example.org/b.xml.gz"
        #EXTINF:-1 tvg-id="BBCOne.uk@SD" tvg-logo="https://logo/one.png" group-title="United Kingdom",BBC One
        https://stream.example/one.m3u8

        #EXTINF:-1 tvg-id="Kite.uk" tvg-name="Kite Kids" tvg-chno="7" http-user-agent="Agent/1.0" group-title="Kids, Family",
        #EXTVLCOPT:http-referrer=https://ref.example/
        http://stream.example/kite.ts
        #EXTINF:-1,No Attributes, Really
        #EXTVLCOPT:http-user-agent=VLC/3
        http://stream.example/plain
        #EXTINF:-1 tvg-id="" group-title="",Orphan info with no url
    """.trimIndent()

    @Test
    fun `parses attributes, names and options`() = runBlocking {
        val entries = mutableListOf<M3uEntry>()
        val guides = M3uParser.parse(playlist.reader().buffered()) { entries += it }
        assertEquals(listOf("https://epg.example.org/a.xml.gz", "https://epg.example.org/b.xml.gz"), guides)
        assertEquals(3, entries.size)

        val one = entries[0]
        assertEquals("BBC One", one.name)
        assertEquals("BBCOne.uk@SD", one.tvgId)
        assertEquals("United Kingdom", one.group)
        assertEquals("https://logo/one.png", one.logo)

        val kite = entries[1]
        assertEquals("Kite Kids", kite.name) // empty display name falls back to tvg-name
        assertEquals("Kids, Family", kite.group) // comma inside quotes isn't the name separator
        assertEquals(7, kite.number)
        assertEquals("Agent/1.0", kite.userAgent)
        assertEquals("https://ref.example/", kite.referrer)

        val plain = entries[2]
        assertEquals("No Attributes, Really", plain.name)
        assertNull(plain.tvgId)
        assertEquals("VLC/3", plain.userAgent)
    }

    @Test
    fun `header after a blank line still counts`() = runBlocking {
        val guides = M3uParser.parse("\n#EXTM3U url-tvg=\"http://g\"\n#EXTINF:-1,A\nhttp://a\n".reader().buffered()) {}
        assertEquals(listOf("http://g"), guides)
    }

    @Test
    fun `header without guides`() {
        assertEquals(emptyList<String>(), M3uParser.headerGuideUrls("#EXTM3U"))
        assertEquals(listOf("http://g"), M3uParser.headerGuideUrls("#EXTM3U url-tvg=\"http://g\""))
    }
}
