package com.biskyt.myplexplayer.core.plex

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SkipMarkersTest {
    private val intro = PlexMarker(PlexMarker.INTRO, 60_000, 90_000)
    private val credits = PlexMarker(PlexMarker.CREDITS, 2_300_000, 2_400_000, final = true)
    private val markers = listOf(intro, credits, PlexMarker("commercial", 1_000_000, 1_100_000))

    /** Playing on to [ms] (half a second after the last look), or sought there from [from]. */
    private fun at(ms: Long, delay: Int?, from: Long? = ms - 500) = skipPill(markers, ms, from) { delay }

    @Test
    fun `without an automatic skip, the pill shows while the marker plays`() {
        assertNull(at(59_999, null))
        assertEquals(SkipPill(intro), at(60_000, null))
        assertEquals(SkipPill(intro), at(89_999, null))
        assertNull(at(90_000, null))
        assertEquals(SkipPill(credits), at(2_350_000, null))
        assertNull("not a commercial", at(1_050_000, null))
    }

    @Test
    fun `with 3 seconds, the pill shows a second before the marker and it skips 2 seconds after its start`() {
        assertNull(at(58_999, 3))
        assertEquals(SkipPill(intro, countdownSec = 3), at(59_000, 3))
        assertEquals(SkipPill(intro, countdownSec = 2), at(60_000, 3))
        assertEquals(SkipPill(intro, countdownSec = 1), at(61_999, 3))
        assertEquals(SkipPill(intro, due = true), at(62_000, 3))
        assertNull(at(90_000, 3))
    }

    @Test
    fun `with 5 seconds, the pill shows 3 seconds before the marker`() {
        assertNull(at(56_999, 5))
        assertEquals(SkipPill(intro, countdownSec = 5), at(57_000, 5))
        assertEquals(SkipPill(intro, due = true), at(62_000, 5))
    }

    @Test
    fun `under 2 seconds the margin shrinks to the delay, and with 0 it skips at the marker with no pill`() {
        assertEquals(SkipPill(intro, countdownSec = 1), at(60_000, 1))
        assertEquals(SkipPill(intro, due = true), at(61_000, 1))
        assertNull(at(59_999, 0))
        assertEquals(SkipPill(intro, due = true), at(60_000, 0))
        assertEquals("first look just past the start", SkipPill(intro, due = true), at(60_400, 0, from = 59_900))
    }

    @Test
    fun `each marker has its own delay`() {
        val introByHand: (PlexMarker) -> Int? = { if (it.type == PlexMarker.INTRO) null else 3 }
        assertEquals(SkipPill(intro), skipPill(markers, 60_000, 59_500, introByHand))
        assertEquals(SkipPill(credits, due = true), skipPill(markers, 2_302_000, 2_301_500, introByHand))
    }

    @Test
    fun `sought into a marker, or starting in one, it waits to be pressed`() {
        assertEquals("from before it", SkipPill(intro), at(70_000, 3, from = 30_000))
        assertEquals("back into it", SkipPill(intro), at(70_000, 3, from = 1_200_000))
        assertEquals("started there", SkipPill(intro), at(70_000, 3, from = null))
        assertEquals("sought into its countdown", SkipPill(intro, countdownSec = 2), at(60_000, 3, from = 10_000))
    }
}
