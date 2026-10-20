package com.biskyt.mytvplayer.core.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class AccentPaletteTest {
    @Test
    fun `derives readable shades`() {
        val mint = AccentPalette.from(AccentPalette.DEFAULT)
        assertEquals(AccentPalette.DEFAULT, mint.accent)
        assertEquals(0xFF0F1114.toInt(), mint.onAccent)
        assertEquals(0x29, mint.tint ushr 24)
        assertEquals(0x2BC4B0, mint.tint and 0xFFFFFF)

        val navy = AccentPalette.from(0xFF1A237E.toInt())
        assertEquals(0xFFFFFFFF.toInt(), navy.onAccent)
        // Soft shade is lighter in every channel.
        listOf(16, 8, 0).forEach { shift -> assert((navy.soft shr shift and 0xFF) > (navy.accent shr shift and 0xFF)) }
    }

    @Test
    fun `alpha in input is ignored`() {
        assertEquals(AccentPalette.DEFAULT, AccentPalette.from(0x002BC4B0).accent)
    }
}
