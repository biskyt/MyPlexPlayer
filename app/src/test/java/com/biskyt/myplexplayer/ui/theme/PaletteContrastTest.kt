package com.biskyt.myplexplayer.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertTrue
import org.junit.Test

class PaletteContrastTest {
    private fun contrast(a: Color, b: Color): Float {
        val (light, dark) = listOf(a.luminance(), b.luminance()).sortedDescending()
        return (light + 0.05f) / (dark + 0.05f)
    }

    @Test
    fun `every text colour is readable on every surface text sits on (at least 4,5 to 1)`() {
        val texts = mapOf("text" to Palette.text, "textMuted" to Palette.textMuted, "textDim" to Palette.textDim)
        val surfaces = mapOf(
            "background" to Palette.background,
            "surface" to Palette.surface,
            "surfaceHigh" to Palette.surfaceHigh,
            "surfaceLow" to Palette.surfaceLow
        )
        for ((t, fg) in texts) {
            for ((s, bg) in surfaces) {
                val ratio = contrast(fg, bg)
                assertTrue("$t on $s is only $ratio to 1", ratio >= 4.5f)
            }
        }
    }
}
