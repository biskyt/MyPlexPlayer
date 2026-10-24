package com.biskyt.mytvplayer.ui.common

import com.biskyt.mytvplayer.ui.settings.SupportLink
import com.google.zxing.BinaryBitmap
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QrCodeTest {
    @Test
    fun `each support link's code reads back as its address`() {
        SupportLink.entries.forEach { link -> assertEquals(link.url, decode(link.url)) }
    }

    @Test
    fun `the README and GitHub's funding file name the same support pages`() {
        val readme = java.io.File("../README.md").readText()
        val funding = java.io.File("../.github/FUNDING.yml").readText()
        SupportLink.entries.forEach { link -> assertTrue(link.url, link.url in readme) }
        // GitHub's own keys for the two platforms.
        assertEquals(setOf("github: biskyt", "ko_fi: biskyt"), funding.lines().filter { it.isNotBlank() }.toSet())
    }

    /** Draws the code as [QrCode] does (black on white, a few pixels a module) and reads it back. */
    private fun decode(text: String): String {
        val matrix = encode(text)
        val scale = 4
        val side = matrix.width * scale
        val pixels = IntArray(side * side) { i ->
            if (matrix[(i % side) / scale, (i / side) / scale]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
        }
        val bitmap = BinaryBitmap(HybridBinarizer(RGBLuminanceSource(side, side, pixels)))
        return QRCodeReader().decode(bitmap).text
    }
}
