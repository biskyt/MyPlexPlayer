package com.biskyt.myplexplayer.testutil

import android.app.Application
import com.biskyt.myplexplayer.AppText

/** A bare application (none of the app's wiring) whose code can still use the app's words. */
class PlainApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppText.init(this)
    }

    override fun onTerminate() {
        try {
            resetSettings(this)
        } finally {
            super.onTerminate()
        }
    }
}
