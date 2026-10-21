package com.biskyt.mytvplayer

import android.content.pm.ActivityInfo
import android.os.Bundle
import android.view.Window
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.biskyt.mytvplayer.ui.AppRoot
import com.biskyt.mytvplayer.ui.common.hasTouchScreen

class MainActivity : ComponentActivity() {
    private val touch by lazy { hasTouchScreen() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (touch) {
            // A phone or tablet: the screens are laid out for a TV, so they're shown in landscape, full screen, the
            // same way on every Android version.
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            enableEdgeToEdge()
            window.hideSystemBars()
        }
        setContent { AppRoot() }
    }

    // Back from a picker or another app, the bars may have come back.
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && touch) window.hideSystemBars()
    }
}

/**
 * Full screen on a phone or tablet: otherwise the status bar lies over the top of the app and takes its taps. A swipe
 * from the edge brings the bars back for a moment.
 */
fun Window.hideSystemBars() = WindowCompat.getInsetsController(this, decorView).run {
    systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    hide(WindowInsetsCompat.Type.systemBars())
}
