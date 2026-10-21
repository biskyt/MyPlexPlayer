package com.biskyt.mytvplayer.testutil

import com.biskyt.mytvplayer.MyTvApp
import kotlinx.coroutines.cancel

/** The real app wiring, minus the background scheduler (WorkManager isn't set up under Robolectric). */
class TestApp : MyTvApp() {
    override fun scheduleDailyRefresh() = Unit

    // A process-wide handler: each test's app would wrap the last one's (tests that need it install it themselves).
    override fun installCrashLog() = Unit

    /**
     * Robolectric calls this after each test. Each test gets a new app, but the database and coroutine threads are
     * shared by the whole run: a test's syncs and queries left running would hold them up in the next test (seen as a
     * Room query that never answers, now and then, on busy runners).
     */
    override fun onTerminate() {
        container.appScope.cancel()
        container.http.dispatcher.cancelAll()
        container.db.close()
        super.onTerminate()
    }
}
