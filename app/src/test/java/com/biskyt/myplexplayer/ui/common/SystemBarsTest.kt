package com.biskyt.myplexplayer.ui.common

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** When a touch screen hides its system bars (see barsHiddenOn). */
class SystemBarsTest {
    @Test
    fun `while watching, always hidden`() {
        assertTrue(barsHiddenOn(playback = true, fullScreen = false))
        assertTrue(barsHiddenOn(playback = true, fullScreen = true))
    }

    @Test
    fun `on the other screens, shown unless the user chose full screen`() {
        assertFalse(barsHiddenOn(playback = false, fullScreen = false))
        assertTrue(barsHiddenOn(playback = false, fullScreen = true))
    }
}
