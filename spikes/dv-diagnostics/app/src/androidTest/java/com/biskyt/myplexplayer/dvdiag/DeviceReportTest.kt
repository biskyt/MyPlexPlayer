package com.biskyt.myplexplayer.dvdiag

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** The report on a real Android (an Android TV 10 emulator, as the owner's TV): every part is there, nothing failed. */
@RunWith(AndroidJUnit4::class)
class DeviceReportTest {
    @Test
    fun the_report_covers_the_device_display_decoders_and_media3() {
        val report = DeviceReport.collect(InstrumentationRegistry.getInstrumentation().targetContext)
        listOf("== Device", "== Display", "== Video decoders", "video/hevc", "== Media3's choice", "Dolby Vision 8.1")
            .forEach { assertTrue("missing $it in:\n$report", it in report) }
        assertTrue("an error in:\n$report", "error:" !in report)
    }
}
