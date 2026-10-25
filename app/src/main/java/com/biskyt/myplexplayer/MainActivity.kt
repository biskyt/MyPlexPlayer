package com.biskyt.myplexplayer

import android.content.Intent
import android.content.pm.ActivityInfo
import android.net.Uri
import android.os.Bundle
import android.view.Window
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.content.IntentCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.biskyt.myplexplayer.ui.AppRoot
import com.biskyt.myplexplayer.ui.common.hasTouchScreen
import kotlinx.coroutines.launch

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
        // Only when first opened with it: recreated, or reopened from Recents, it's the same file, already dealt with.
        if (savedInstanceState == null && intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY == 0) takeOpenedFile(intent)
        setContent { InAppLanguage { AppRoot() } }
    }

    // Already running (it's the only one, singleTask): a file opened with it arrives here.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        takeOpenedFile(intent)
    }

    /** A backup file opened with MyPlexPlayer from another app, for Backup & restore to offer (see AppRoot). */
    private fun takeOpenedFile(intent: Intent) {
        intent.openedFile()?.let { container.openedFile.value = it }
    }

    // Back in the app, from another app or opened again: what played elsewhere meanwhile (another Plex app) shows here.
    // The first start is the app opening (or this screen made again), which starts a sync if one is due.
    override fun onStart() {
        super.onStart()
        val syncIfDue = started
        started = true
        container.appScope.launch { container.sync.refreshPlayState(container.settings.current().activePlaylistId, syncIfDue) }
    }

    private var started = false

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

/** The file in a file manager's "Open with" (VIEW) or "Share" (SEND). */
fun Intent.openedFile(): Uri? = when (action) {
    Intent.ACTION_VIEW -> data
    Intent.ACTION_SEND -> IntentCompat.getParcelableExtra(this, Intent.EXTRA_STREAM, Uri::class.java)
    else -> null
}
