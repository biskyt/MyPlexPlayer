package com.biskyt.myplexplayer.testutil

import android.content.Context
import com.biskyt.myplexplayer.data.Settings
import com.biskyt.myplexplayer.data.SettingsRepository
import kotlinx.coroutines.runBlocking

/**
 * Puts the settings back to their defaults, the formats remembered per playlist included. The settings file outlives
 * the app (DataStore keeps one per process), so TestApp and PlainApp do this as each test ends.
 */
fun resetSettings(context: Context) = runBlocking {
    SettingsRepository(context).run {
        update { Settings() }
        setStreamFormat(Settings().streamFormat)
    }
}
