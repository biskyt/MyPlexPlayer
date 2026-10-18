package com.biskyt.mytvplayer.testutil

import com.biskyt.mytvplayer.MyTvApp

/** The real app wiring, minus the background scheduler (WorkManager isn't set up under Robolectric). */
class TestApp : MyTvApp() {
    override fun scheduleDailyRefresh() = Unit
}
