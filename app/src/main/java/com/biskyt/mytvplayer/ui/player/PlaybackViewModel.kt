package com.biskyt.mytvplayer.ui.player

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.layout.ContentScale
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import com.biskyt.mytvplayer.AppContainer
import com.biskyt.mytvplayer.core.player.KeyActionMapper
import com.biskyt.mytvplayer.core.player.OverlayState
import com.biskyt.mytvplayer.core.player.PlayerAction
import com.biskyt.mytvplayer.core.player.RemoteKey
import com.biskyt.mytvplayer.data.Settings
import com.biskyt.mytvplayer.player.PlaybackEngine
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * What every player screen shares, live TV or on demand: the engine, the overlay and side sheets, remote keys,
 * skipping, retries, the sleep timer and stopping when the app goes to the background.
 */
abstract class PlaybackViewModel(protected val c: AppContainer, private val appContext: Context) : ViewModel() {
    var engine by mutableStateOf<PlaybackEngine?>(null)
        private set
    var overlay by mutableStateOf(OverlayState.CONTROLS)
        protected set
    var isPlaying by mutableStateOf(true)
        private set
    var buffering by mutableStateOf(true)
        private set
    var error by mutableStateOf<String?>(null)
        protected set
    var message by mutableStateOf<String?>(null)
        private set
    var tracks by mutableStateOf(Tracks.EMPTY)
        private set
    var audioLabel by mutableStateOf("")
        private set
    var contentScale by mutableStateOf(ContentScale.Fit)
    var sleepMinutes by mutableStateOf(0)
        private set

    /** Which side sheet is open over the video (null = none). */
    var sheet by mutableStateOf<Sheet?>(null)

    /** Set when playback should end and the screen close. */
    var exit by mutableStateOf(false)
        private set

    enum class Sheet { AUDIO, SUBTITLES, MORE }

    protected lateinit var settings: Settings
    private lateinit var mapper: KeyActionMapper
    private var hideJob: Job? = null
    private var retryJob: Job? = null
    private var messageJob: Job? = null
    private var sleepJob: Job? = null
    private val retries = RetryBudget(MAX_RETRIES)

    // How long the stream has played since its last failure: once it has really played a while, failures get fresh
    // retries; one that starts and fails again straight away would otherwise retry for ever ("Loading…", blank…).
    private var playedMs = 0L
    private var playingSince = 0L

    /** Live TV zaps with up/down and has a guide; on-demand video doesn't. */
    protected abstract val isLive: Boolean

    /** Shown when a stream keeps failing. */
    protected abstract val failureMessage: String

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(playing: Boolean) {
            isPlaying = playing
            val now = System.currentTimeMillis()
            if (playing) {
                playingSince = now
            } else if (playingSince > 0) {
                playedMs += now - playingSince
                playingSince = 0
            }
            onPlayingChanged(playing)
        }

        override fun onPlaybackStateChanged(state: Int) {
            buffering = state == Player.STATE_BUFFERING
            if (state == Player.STATE_READY) error = null
            if (state == Player.STATE_ENDED) onEnded()
        }

        override fun onTracksChanged(t: Tracks) {
            tracks = t
            engine?.applyDefaultSubtitles(t)
            audioLabel = engine?.audioOutputLabel().orEmpty()
        }

        override fun onPlayerError(e: PlaybackException) {
            val player = engine?.player ?: return
            if (engine?.retryAsHls(e) == true) return
            // Streams drop out now and then; retry a few times before showing an error.
            if (playedSinceFailure() >= STEADY_MS) retries.reset()
            playedMs = 0
            playingSince = 0
            if (!retries.take()) {
                error = failureMessage
                return
            }
            if (e.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                player.seekToDefaultPosition()
                player.prepare()
                return
            }
            retryJob?.cancel()
            retryJob = viewModelScope.launch {
                delay(1_000L * retries.used)
                player.prepare()
            }
        }
    }

    /** Reads the settings and creates the engine; subclasses call this first when they start. */
    protected suspend fun startEngine(): PlaybackEngine {
        settings = c.settings.current()
        mapper = KeyActionMapper(settings.skipBackSec * 1_000L, settings.skipForwardSec * 1_000L)
        return PlaybackEngine(appContext, settings).also {
            it.player.addListener(listener)
            it.onUnsupported = { error = "This uses a stream format MyTVPlayer can't play." }
            engine = it
        }
    }

    /** Forgets the previous stream's failures before playing another. */
    protected fun resetForNewStream() {
        retryJob?.cancel()
        error = null
        retries.reset()
        playedMs = 0
        playingSince = 0
    }

    private fun playedSinceFailure() = playedMs + if (playingSince > 0) System.currentTimeMillis() - playingSince else 0

    protected open fun onPlayingChanged(playing: Boolean) = Unit

    protected open fun onEnded() = Unit

    // Live TV overrides these; on demand they do nothing.
    protected open fun zap(step: Int) = Unit

    protected open fun typeDigit(digit: Int) = Unit

    open fun openGuide() = Unit

    open fun closeGuide() {
        overlay = OverlayState.NONE
    }

    fun onKey(key: RemoteKey, digit: Int = 0): Boolean {
        if (!::mapper.isInitialized) return false
        val action = mapper.map(key, overlay, isLive, digit)
        if (overlay != OverlayState.NONE) showControls(keepOverlay = true)
        return perform(action)
    }

    private fun perform(action: PlayerAction): Boolean {
        val player = engine?.player
        when (action) {
            is PlayerAction.SeekBy -> seekBy(action.ms)
            is PlayerAction.Zap -> zap(action.step)
            is PlayerAction.Digit -> typeDigit(action.value)
            PlayerAction.ToggleOverlay -> if (overlay == OverlayState.NONE) showControls() else hideOverlay()
            PlayerAction.HideOverlay -> hideOverlay()
            PlayerAction.TogglePlayPause -> player?.let { if (it.isPlaying) it.pause() else it.play() }
            PlayerAction.Play -> player?.play()
            PlayerAction.Pause -> player?.pause()
            PlayerAction.StopAndExit -> stop()
            PlayerAction.OpenGuide -> openGuide()
            PlayerAction.CloseGuide -> closeGuide()
            PlayerAction.OpenMenu -> sheet = Sheet.MORE
            PlayerAction.None -> return false
        }
        return true
    }

    /** Back closes the topmost overlay, otherwise stops playback (also safe while the player is still starting). */
    fun back() {
        if (!::mapper.isInitialized) stop() else perform(mapper.map(RemoteKey.BACK, overlay, isLive))
    }

    /** Called when the app goes to the background / comes back: nothing is left streaming in the background. */
    open fun onAppHidden() {
        engine?.player?.stop()
    }

    open fun onAppShown() {
        engine?.player?.let {
            if (it.playbackState == Player.STATE_IDLE && !exit) {
                it.prepare()
                it.play()
            }
        }
    }

    open fun stop() {
        engine?.player?.stop()
        exit = true
    }

    fun togglePlayPause() = perform(PlayerAction.TogglePlayPause)

    open fun seekBy(ms: Long) {
        val p = engine?.player ?: return
        if (!p.isCurrentMediaItemSeekable) {
            flash("Skipping isn't available here")
            return
        }
        p.seekTo((p.currentPosition + ms).coerceAtLeast(0))
        flash(if (ms < 0) "−${-ms / 1000}s" else "+${ms / 1000}s")
    }

    fun showControls(keepOverlay: Boolean = false) {
        if (!keepOverlay || overlay == OverlayState.NONE) overlay = OverlayState.CONTROLS
        hideJob?.cancel()
        hideJob = viewModelScope.launch {
            delay(OVERLAY_TIMEOUT_MS)
            if (overlay == OverlayState.CONTROLS && sheet == null) overlay = OverlayState.NONE
        }
    }

    fun hideOverlay() {
        hideJob?.cancel()
        overlay = OverlayState.NONE
    }

    /** Keeps the controls up (e.g. while the guide panel is open) until something else hides them. */
    protected fun cancelAutoHide() {
        hideJob?.cancel()
    }

    open fun retry() {
        resetForNewStream()
        engine?.player?.prepare()
    }

    fun setSleepTimer(minutes: Int) {
        sleepJob?.cancel()
        sleepMinutes = minutes
        if (minutes > 0) {
            sleepJob = viewModelScope.launch {
                delay(minutes * 60_000L)
                stop()
            }
            flash("Sleep timer: $minutes min")
        }
    }

    protected fun flash(text: String) {
        message = text
        messageJob?.cancel()
        messageJob = viewModelScope.launch {
            delay(1_500)
            message = null
        }
    }

    override fun onCleared() {
        engine?.player?.removeListener(listener)
        engine?.release()
    }

    private companion object {
        const val MAX_RETRIES = 3

        /** Having played this long (in all) since the last failure counts as working: later failures get fresh retries. */
        const val STEADY_MS = 20_000L
        const val OVERLAY_TIMEOUT_MS = 5_000L
    }
}
