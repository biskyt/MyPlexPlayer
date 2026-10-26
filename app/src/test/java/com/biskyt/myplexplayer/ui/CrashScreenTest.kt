package com.biskyt.myplexplayer.ui

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.biskyt.myplexplayer.MainActivity
import com.biskyt.myplexplayer.testutil.TestApp
import com.biskyt.myplexplayer.testutil.createUiEmptyComposeRule
import com.biskyt.myplexplayer.testutil.waitForText
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** After a crash, the next start says so, offers to send it, and can start without updating. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestApp::class, qualifiers = "+w960dp-h540dp-tvdpi")
class CrashScreenTest {
    @get:Rule val compose = createUiEmptyComposeRule()

    private val app = ApplicationProvider.getApplicationContext<TestApp>()
    private val file get() = File(app.filesDir, "last-crash.txt")

    private fun waitFor(text: String) = compose.waitForText(text)

    @Test
    fun `a crash recorded last time is shown first, and continuing clears it`() {
        // As the app's handler records it (installed here only: it's process-wide).
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler(null)
        try {
            app.container.crashLog.install()
            Thread.getDefaultUncaughtExceptionHandler()!!.uncaughtException(Thread.currentThread(), IllegalStateException("boom"))
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(previous)
        }

        ActivityScenario.launch(MainActivity::class.java).use {
            waitFor("closed unexpectedly")
            waitFor("IllegalStateException: boom")
            compose.onNode(hasText("Continue without updating") and hasClickAction()).performSemanticsAction(SemanticsActions.OnClick)
            // No playlist yet: on to set-up.
            waitFor("Sign in to Plex")
            assertFalse(file.exists())
        }
    }
}
