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
        // Calendar days: late in the evening, a few hours before tomorrow's date is still "in 1 day", not "today".
        val utc = java.time.ZoneOffset.UTC
        val evening = java.time.LocalDateTime.of(2027, 3, 1, 22, 0).toInstant(utc).toEpochMilli()
        val earlyTomorrow = java.time.LocalDateTime.of(2027, 3, 2, 2, 0).toEpochSecond(utc)
        assertTrue(expiry(earlyTomorrow, evening, utc)!!.first.endsWith("in 1 day"))
    }
}
