package com.biskyt.myplexplayer.ui.player

import org.junit.Assert.assertEquals
import org.junit.Test

class PictureModeTest {
    // A 1920×1080 screen.
    private fun size(mode: PictureMode, aspect: Float?) = mode.size(1920, 1080, aspect)

    @Test
    fun `a 4-3 picture has bars at the sides, filled when zoomed, or stretched`() {
        assertEquals(1440 to 1080, size(PictureMode.FIT, 4f / 3))
        assertEquals(1920 to 1440, size(PictureMode.ZOOM, 4f / 3)) // top and bottom cut off
        assertEquals(1920 to 1080, size(PictureMode.STRETCH, 4f / 3))
    }

    @Test
    fun `a wide film has bars above and below, filled when zoomed`() {
        assertEquals(1920 to 800, size(PictureMode.FIT, 2.4f))
        assertEquals(2592 to 1080, size(PictureMode.ZOOM, 2.4f))
    }

    @Test
    fun `fixed shapes ignore the shape the stream says it has`() {
        assertEquals(1920 to 1080, size(PictureMode.WIDE, 4f / 3)) // a widescreen picture flagged 4:3
        assertEquals(1440 to 1080, size(PictureMode.STANDARD, 16f / 9))
    }

    @Test
    fun `before the picture's shape is known, it fills the screen`() {
        assertEquals(1920 to 1080, size(PictureMode.FIT, null))
    }

    @Test
    fun `the button goes through every mode and back`() {
        assertEquals(PictureMode.entries, generateSequence(PictureMode.FIT) { it.next }.take(PictureMode.entries.size).toList())
        assertEquals(PictureMode.FIT, PictureMode.STANDARD.next)
    }

    @Test
    fun `no screen yet, or a picture with no shape, doesn't break the sums`() {
        assertEquals(0 to 0, PictureMode.FIT.size(0, 0, null))
        assertEquals(1920 to 1080, size(PictureMode.FIT, 0f))
    }
}
