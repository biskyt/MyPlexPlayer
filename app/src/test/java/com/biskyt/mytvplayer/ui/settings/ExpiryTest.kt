package com.biskyt.mytvplayer.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExpiryTest {
    private val now = 1_800_000_000_000L
    private val day = 86_400L

    @Test
    fun `the expiry date warns when it's close or past, and is absent when unknown`() {
        assertNull(expiry(null, now))
        assertNull(expiry(0, now))
        val far = expiry(now / 1000 + 100 * day, now)!!
        assertTrue(far.first.startsWith("Expires ") && !far.second)
        val soon = expiry(now / 1000 + 5 * day + 60, now)!!
        assertTrue(soon.first.endsWith("in 5 days") && soon.second)
        assertEquals(true, expiry(now / 1000 - day, now)!!.first.startsWith("Expired on"))
    }
}
