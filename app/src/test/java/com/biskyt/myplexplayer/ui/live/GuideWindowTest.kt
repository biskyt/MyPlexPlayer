package com.biskyt.myplexplayer.ui.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The guide runs from the half hour it's in to eight days ahead, as Plex's own guide. */
class GuideWindowTest {
    private val halfHour = 30 * 60_000L
    private val hour = 2 * halfHour

    @Test
    fun `the guide doesn't go back before now`() {
        val clock = System.currentTimeMillis()
        val window = GuideWindow { clock }
        val opened = window.start
        assertEquals(clock / halfHour * halfHour, opened)
        assertFalse(window.move(-1, null))
        assertEquals(opened, window.start)
        assertTrue(window.move(1, null))
        assertEquals(opened + hour, window.start)
        assertTrue(window.move(-1, null))
        assertEquals(opened, window.start)
        assertFalse(window.move(-1, null))
    }

    @Test
    fun `moving on after it was left open goes no earlier than now`() {
        var clock = System.currentTimeMillis()
        val window = GuideWindow { clock }
        clock += 3 * hour // left open for three hours
        assertTrue(window.move(1, null)) // an hour on from where it opened would be two hours ago
        assertEquals(clock / halfHour * halfHour, window.start)
    }

    @Test
    fun `going back after it was left open doesn't move it later`() {
        var clock = System.currentTimeMillis()
        val window = GuideWindow { clock }
        val opened = window.start
        clock += 3 * hour
        assertFalse(window.move(-1, null))
        assertEquals(opened, window.start)
    }

    @Test
    fun `the guide goes eight days ahead`() {
        val clock = System.currentTimeMillis()
        val window = GuideWindow { clock }
        var moves = 0
        while (window.move(1, null)) moves++
        assertEquals(clock / halfHour * halfHour + 8 * 24 * hour, window.start)
        assertEquals(8 * 24, moves)
    }
}
