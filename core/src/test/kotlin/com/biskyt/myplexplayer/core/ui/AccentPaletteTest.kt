package com.biskyt.myplexplayer.core.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class AccentPaletteTest {
    @Test
    fun `derives readable shades`() {
        val yellow = AccentPalette.from(AccentPalette.DEFAULT)
        assertEquals(AccentPalette.DEFAULT, yellow.accent)
        assertEquals(0xFF0F1114.toInt(), yellow.onAccent)
        assertEquals(0x29, yellow.tint ushr 24)
        assertEquals(0xE5A00D, yellow.tint and 0xFFFFFF)

        val navy = AccentPalette.from(0xFF1A237E.toInt())
        assertEquals(0xFFFFFFFF.toInt(), navy.onAccent)
        // Soft shade is lighter in every channel.
        listOf(16, 8, 0).forEach { shift -> assert((navy.soft shr shift and 0xFF) > (navy.accent shr shift and 0xFF)) }
    }

    @Test
    fun `alpha in input is ignored`() {
        assertEquals(AccentPalette.DEFAULT, AccentPalette.from(0x00E5A00D).accent)
    }
}
