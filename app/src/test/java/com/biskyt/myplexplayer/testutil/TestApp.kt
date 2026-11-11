package com.biskyt.myplexplayer.testutil

import android.os.SystemClock
import com.biskyt.myplexplayer.MyPlexApp
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.robolectric.shadows.ShadowLooper

/** The real app wiring, minus the background scheduler (WorkManager isn't set up under Robolectric). */
class TestApp : MyPlexApp() {
    override fun onCreate() {
        lookUpNioClasses() // before any image is loaded
        super.onCreate()
    }

    override fun scheduleDailyRefresh() = Unit

    // A process-wide handler: each test's app would wrap the last one's (tests that need it install it themselves).
    override fun installCrashLog() = Unit

    /**
     * Robolectric calls this after each test. Each test gets a new app, but the database and coroutine threads are
     * shared by the whole run: a test's syncs and queries left running would hold them up in the next test (seen as a
     * Room query that never answers, now and then, on busy runners).
     */
    override fun onTerminate() {
        try {
            stopMainThreadEffects()
            runMainLooperDown()
            // Its calls first, so that no work waits on one; then the work, waited for (as long as is fair): a Room query
            // still running (the Live TV flow reads the database all along) would otherwise meet the closed database,
            // and fail the next test in this JVM.
            container.http.dispatcher.cancelAll()
            val stopped = runBlocking { withTimeoutOrNull(APP_WORK_STOP_MS) { container.appScope.coroutineContext.job.cancelAndJoin() } }
            // Said, so that a failure in the next test (the database closed under the work) has its cause in the log.
            if (stopped == null) System.err.println("TestApp: the app's work did not stop in $APP_WORK_STOP_MS ms")
            // Again, for what stopping posted (before the database it may still use is closed).
            runMainLooperDown()
            container.db.close()
        } finally {
            try {
                resetSettings(this)
            } finally {
                super.onTerminate()
            }
        }
    }

    /**
     * Runs what's posted to the main thread, frame by frame, until nothing is due within a frame. Robolectric then
     * empties the main looper and resets the Choreographer, dropping whatever is left. Compose's main-thread dispatcher
     * (AndroidUiDispatcher.Main) is a lazy that lives as long as the test process: once its posted run is dropped it
     * never posts again, and every later test's paged lists (collectAsLazyPagingItems runs there) stay empty.
     */
    private fun runMainLooperDown() {
        val looper = ShadowLooper.shadowMainLooper()
        fun dueSoon(): Boolean {
            val next = looper.nextScheduledTaskTime.toMillis()
            return !looper.isIdle || (next != 0L && next <= SystemClock.uptimeMillis() + FRAME_MS)
        }
        var frames = 0
        while (dueSoon()) {
            check(++frames <= MAX_FRAMES) { "The main thread still had work due after $MAX_FRAMES frames" }
            looper.idleFor(FRAME_MS, TimeUnit.MILLISECONDS)
        }
    }

    private companion object {
        /** A display frame, a little over 16ms. */
        const val FRAME_MS = 17L
        const val MAX_FRAMES = 100
    }
}

/** How long a test's end waits for the app's own work to stop (see TestApp.onTerminate). */
private const val APP_WORK_STOP_MS = 5_000L
