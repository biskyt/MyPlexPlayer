package com.biskyt.myplexplayer

import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.ActivityInfo
import android.net.Uri
import android.os.Bundle
import android.view.KeyEvent
import android.view.Window
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.ui.input.key.Key
import androidx.core.content.IntentCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.biskyt.myplexplayer.ui.AppRoot
import com.biskyt.myplexplayer.ui.common.hasTouchScreen
import com.biskyt.myplexplayer.ui.common.isActing
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
        // While shown: recordings starting and stopping (toasts, see AppRoot).
        container.recordings.startWatching(container.settings.activePlaylistId)
    }

    override fun onStop() {
        container.recordings.stopWatching()
        super.onStop()
    }

    private var started = false

    /** OK, Options and Guide presses that began in this window (see [dispatchKeyEvent]). */
    private val pressedHere = mutableSetOf<Int>()

    /**
     * The rest of an OK, Options or Guide press that began in another window is kept from the TV and from this window:
     * a panel (a window of its own) acted on that press and closed, e.g. Options pressed in the player's Audio panel.
     * The TV would act on its release (Options: its own menu), and this window on its repeats.
     */
    // (Activity's own, public method: lint takes androidx's ComponentActivity override of it for a restricted one.)
    @SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (Key(event.keyCode).isActing()) {
            when {
                event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0 -> pressedHere += event.keyCode
                event.action == KeyEvent.ACTION_DOWN -> if (event.keyCode !in pressedHere) return true
                event.action == KeyEvent.ACTION_UP -> if (!pressedHere.remove(event.keyCode)) return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    // Back from a picker or another app, the bars may have come back.
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // Another window (a panel) has the keys now: a press begun here ends there, so it's no longer this window's.
        if (!hasFocus) pressedHere.clear()
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
