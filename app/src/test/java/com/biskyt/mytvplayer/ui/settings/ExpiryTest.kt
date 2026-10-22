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
        assertTrue(far.kind == ExpiryKind.LATER && !far.soon && far.date.isNotEmpty())
        val soon = expiry(now / 1000 + 5 * day + 60, now)!!
        assertTrue(soon.kind == ExpiryKind.SOON && soon.days == 5 && soon.soon)
        val past = expiry(now / 1000 - day, now)!!
        assertTrue(past.kind == ExpiryKind.EXPIRED && past.soon)
        // Calendar days: late in the evening, a few hours before tomorrow's date is still "in 1 day", not "today".
        val utc = java.time.ZoneOffset.UTC
        val evening = java.time.LocalDateTime.of(2027, 3, 1, 22, 0).toInstant(utc).toEpochMilli()
        val earlyTomorrow = java.time.LocalDateTime.of(2027, 3, 2, 2, 0).toEpochSecond(utc)
        val tomorrow = expiry(earlyTomorrow, evening, utc)!!
        assertEquals(ExpiryKind.SOON to 1, tomorrow.kind to tomorrow.days)
        assertEquals(ExpiryKind.TODAY, expiry(earlyTomorrow, evening + 3 * 3_600_000L, utc)!!.kind) // after midnight: today
    }
}
