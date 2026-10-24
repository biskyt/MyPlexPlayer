package com.biskyt.myplexplayer.spike

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import java.io.File
import java.util.zip.ZipFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The app's runner on a device, against a real Plex Media Server: the one `.github/scripts/start-plex.sh` starts
 * on the machine running the emulator (10.0.2.2 from inside it). Signed in already (a saved state naming that
 * server), so it skips plex.tv's sign-in: the libraries and playback steps must pass, and the zip must be made.
 */
@RunWith(AndroidJUnit4::class)
class SpikeOnDeviceTest {
    /** What runner.py prints, collected. */
    class Collect {
        val text = StringBuilder()

        fun emit(piece: String) {
            synchronized(text) { text.append(piece) }
        }
    }

    @Test
    fun theSpikeRunsOnTheDevice() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        if (!Python.isStarted()) Python.start(AndroidPlatform(context))
        val files = context.filesDir
        val runner = Python.getInstance().getModule("runner")
        runner.callAttr("forget", files.absolutePath)
        val server = InstrumentationRegistry.getArguments().getString("plexServer") ?: "http://10.0.2.2:32400"
        File(files, ".myplexplayer-spike.json").writeText(
            """{"clientId":"device-test","token":"not-a-real-token","servers":[{"name":"test","uri":"$server","token":null}]}"""
        )

        val printed = Collect()
        val zip = runner.callAttr("run", files.absolutePath, printed).toString()

        println(printed.text) // in the test's log, for a failure
        assertTrue("the zip was made", zip.isNotEmpty())
        val names = ZipFile(zip).use { z -> z.entries().toList().map { it.name } }
        for (step in listOf("s3-library", "s4-playback")) {
            assertTrue("$step reported", "report-$step.md" in names)
            val report = File(files, "spike-out/report-$step.md").readText()
            assertFalse(report, report.contains("FAIL"))
        }
        assertTrue("sanitised responses are in", names.any { it.startsWith("sanitised/s3-library/") })
        assertEquals("no raw responses are shared", emptyList<String>(), names.filter { it.startsWith("raw/") })
    }
}
