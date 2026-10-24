package com.biskyt.myplexplayer.core.epg

import java.io.ByteArrayInputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.kxml2.io.KXmlParser

class ProgrammeKindTest {
    private fun kind(vararg categories: String, hasEpisode: Boolean = false) =
        ProgrammeKind.finish(categories.fold(ProgrammeKind.UNKNOWN, ProgrammeKind::fold), hasEpisode)

    @Test
    fun `categories and episode numbers decide the kind`() {
        assertEquals(ProgrammeKind.MOVIE, kind("Movie"))
        assertEquals(ProgrammeKind.MOVIE, kind("Drama", "Film", "Series"))
        assertEquals(ProgrammeKind.MOVIE, kind("Movie", hasEpisode = true))
        assertEquals(ProgrammeKind.SERIES, kind("Sitcom"))
        assertEquals(ProgrammeKind.SERIES, kind("Sports", "Series")) // a sports series is still a series
        assertEquals(ProgrammeKind.SERIES, kind("Drama", hasEpisode = true))
        assertEquals(ProgrammeKind.OTHER, kind("News"))
        assertEquals(ProgrammeKind.OTHER, kind(" Sport "))
        assertEquals(ProgrammeKind.UNKNOWN, kind("Drama"))
        assertEquals(ProgrammeKind.MOVIE, kind("Movie / Drama")) // DVB-style genres
        assertEquals(ProgrammeKind.OTHER, kind("Sports - Football"))
        assertEquals(ProgrammeKind.OTHER, kind("News / Current affairs"))
        assertEquals(ProgrammeKind.UNKNOWN, kind())
    }

    private fun episode(system: String, value: String) =
        ProgrammeKind.parseEpisode(system, value)?.let { ProgrammeKind.season(it) to ProgrammeKind.episode(it) }

    @Test
    fun `reads xmltv_ns and onscreen episode numbers`() {
        assertEquals(1 to 5, episode("xmltv_ns", "0.4."))
        assertEquals(2 to 3, episode("xmltv_ns", "1.2/10.0/1"))
        assertEquals(2 to 3, episode("xmltv_ns", " 1 . 2 . "))
        assertEquals(null to 5, episode("xmltv_ns", ".4.")) // no season: still an episode
        assertNull(episode("xmltv_ns", "3"))
        assertEquals(2 to 5, episode("onscreen", "S02E05"))
        assertEquals(2 to 5, episode("onscreen", "S2 E5"))
        assertEquals(2 to 5, episode("onscreen", "Season 2 Episode 5"))
        assertEquals(null to 5, episode("onscreen", "Episode 5"))
        assertNull(episode("onscreen", "Season 5"))
        assertNull(episode("dd_progid", "EP012345.0001"))
    }

    @Test
    fun `titles compare without case, punctuation, year or a leading the`() {
        val key = com.biskyt.myplexplayer.core.vod.VodTitles::key
        assertEquals(key("Grey's Anatomy"), key("Greys Anatomy"))
        assertEquals(key("The Simpsons"), key("|EN| Simpsons (1989)"))
        assertEquals("the", key("The"))
        assertTrue(key("Dune") != key("Dune: Part Two"))
    }

    @Test
    fun `the parser reads the kind of programmes it keeps`() {
        val xml = """
            <tv>
              <programme start="20261018180000 +0000" stop="20261018190000 +0000" channel="a">
                <title>Line of Duty</title><category>Drama</category><episode-num system="xmltv_ns">0.1.</episode-num>
              </programme>
              <programme start="20261018190000 +0000" stop="20261018210000 +0000" channel="a">
                <title>Dune</title><category lang="en">Movie</category>
              </programme>
              <programme start="20261018210000 +0000" stop="20261018213000 +0000" channel="a">
                <title>News at Nine</title><category>News</category>
              </programme>
            </tv>
        """.trimIndent()
        val kept = mutableListOf<XmltvProgramme>()
        runBlocking { XmltvParser({ KXmlParser() }).parse(ByteArrayInputStream(xml.toByteArray()), { true }, {}, { kept += it }) }
        assertEquals(listOf(ProgrammeKind.SERIES, ProgrammeKind.MOVIE, ProgrammeKind.OTHER), kept.map { it.kind })
        assertEquals(1, kept[0].season)
        assertEquals(2, kept[0].episode)
        assertNull(kept[1].season)
    }

    /**
     * Guide imports are the heaviest job on a slow box: reading categories and episode numbers must stay cheap, under a
     * third more than parsing without them (by what parsing allocates, see below; today about a quarter). Compares the
     * same guide (every programme kept, the worst case) with those tags read, and with the same bytes under names the
     * parser skips, as it did before it read them.
     */
    @Test
    fun `reading the kind adds little to parsing`() {
        fun guide(category: String, episodeNum: String) = buildString {
            append("<tv>")
            for (i in 0 until PROGRAMMES) {
                append("<programme start=\"20261018180000 +0000\" stop=\"20261018190000 +0000\" channel=\"c${i % 300}\">")
                append("<title>Programme $i</title><desc>A description of programme $i, as long as guides usually have.</desc>")
                append("<$category>Drama</$category><$category>Series</$category>")
                append("<$episodeNum system=\"xmltv_ns\">2.${i % 20}.</$episodeNum>")
                append("</programme>")
            }
            append("</tv>")
        }.toByteArray()
        // What this thread allocates stands in for the cost: reading the tags is mostly making strings. Unlike CPU time,
        // it doesn't depend on how busy the machine is (with other tests alongside, the extra time grew up to 1.7x, as
        // the string-making suffers most from sharing memory and caches). Work that allocates nothing isn't counted.
        val thread = java.lang.management.ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        assertTrue("this JVM doesn't count allocations", thread.isThreadAllocatedMemoryEnabled)
        fun allocated(guide: ByteArray): Long {
            var count = 0
            val start = thread.currentThreadAllocatedBytes
            runBlocking { XmltvParser({ KXmlParser() }).parse(ByteArrayInputStream(guide), { true }, {}, { count++ }) }
            assertEquals(PROGRAMMES, count)
            return thread.currentThreadAllocatedBytes - start
        }
        val skipped = guide("categorx", "episode-nux")
        val read = guide("category", "episode-num")
        assertEquals(skipped.size, read.size)
        // Warmed up first, so one-time set-up (classes loaded, ProgrammeKind's word lists) isn't counted.
        repeat(5) {
            allocated(skipped)
            allocated(read)
        }
        val before = allocated(skipped)
        val after = allocated(read)
        println("Parsing $PROGRAMMES programmes: ${before / 1024} KB allocated skipping the tags, ${after / 1024} KB reading them")
        assertTrue("reading allocates ${after / 1024} KB vs skipping ${before / 1024} KB", after < before * 1.3)
    }

    private companion object {
        const val PROGRAMMES = 50_000
    }
}
