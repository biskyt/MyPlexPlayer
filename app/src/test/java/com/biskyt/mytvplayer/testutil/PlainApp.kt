package com.biskyt.mytvplayer.testutil

import android.app.Application
import com.biskyt.mytvplayer.AppText

/** A bare application (none of the app's wiring) whose code can still use the app's words. */
class PlainApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppText.init(this)
    }
}
