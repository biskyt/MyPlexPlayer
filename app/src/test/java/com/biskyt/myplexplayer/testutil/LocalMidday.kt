package com.biskyt.myplexplayer.testutil

import java.time.ZoneOffset
import java.util.TimeZone
import org.junit.rules.ExternalResource

/**
 * Runs a test with the local time near midday, whenever it runs. The fake provider's catch-up is timed from now (one
 * programme 1–2 hours ago, "Earlier Today"), and the app files it by local day: just after midnight it would be
 * yesterday's. A time zone is picked whose offset puts now at about 12:00, and the real one is put back afterwards.
 */
class LocalMidday : ExternalResource() {
    private lateinit var previous: TimeZone

    override fun before() {
        previous = TimeZone.getDefault()
        val utcHour = (System.currentTimeMillis() / HOUR_MS % 24).toInt()
        // UTC-11 to UTC+12: offsets every zone allows.
        TimeZone.setDefault(TimeZone.getTimeZone(ZoneOffset.ofHours(12 - utcHour)))
    }

    override fun after() = TimeZone.setDefault(previous)

    private companion object {
        const val HOUR_MS = 3_600_000L
    }
}
