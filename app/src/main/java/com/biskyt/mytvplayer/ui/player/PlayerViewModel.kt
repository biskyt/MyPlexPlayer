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
import com.biskyt.mytvplayer.core.xtream.XtreamClient
import com.biskyt.mytvplayer.data.Settings
import com.biskyt.mytvplayer.data.StreamFormat
import com.biskyt.mytvplayer.data.db.ChannelEntity
import com.biskyt.mytvplayer.player.PlaybackEngine
import com.biskyt.mytvplayer.ui.Screen
import com.biskyt.mytvplayer.ui.common.NowNextLoader
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class PlayerViewModel(private val c: AppContainer, appContext: Context, private val screen: Screen.Player) : ViewModel() {
    var engine by mutableStateOf<PlaybackEngine?>(null)
        private set
    var channel by mutableStateOf<ChannelEntity?>(null)
        private set
    var overlay by mutableStateOf(OverlayState.CONTROLS)
        private set
    var isPlaying by mutableStateOf(true)
        private set
    var buffering by mutableStateOf(true)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var message by mutableStateOf<String?>(null)
        private set
    var typedNumber by mutableStateOf("")
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

    /** Channel previewed in the guide panel (may differ from the one playing). */
    var guideChannel by mutableStateOf<ChannelEntity?>(null)
        private set

    /** Set when playback should end and the screen close. */
    var exit by mutableStateOf(false)
        private set

    val nowNext = NowNextLoader(viewModelScope, c.epg.changes) { ids, from, to -> c.epg.programmes(screen.playlistId, ids, from, to) }

    private lateinit var settings: Settings
    private lateinit var mapper: KeyActionMapper
    private var client: XtreamClient? = null
    private var ids: List<Long> = emptyList()
    private var hideJob: Job? = null
    private var numberJob: Job? = null
    private var messageJob: Job? = null
    private var sleepJob: Job? = null
    private var tuneJob: Job? = null
    private var retries = 0

    /** The channel being tuned to; updated immediately so rapid zapping steps through channels one by one. */
    private var targetStreamId: Long = screen.streamId

    enum class Sheet { AUDIO, SUBTITLES, MORE }

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(playing: Boolean) {
            isPlaying = playing
        }

        override fun onPlaybackStateChanged(state: Int) {
            buffering = state == Player.STATE_BUFFERING
            if (state == Player.STATE_READY) {
                error = null
                retries = 0
            }
        }

        override fun onTracksChanged(t: Tracks) {
            tracks = t
            engine?.applyDefaultSubtitles(t)
            audioLabel = engine?.audioOutputLabel().orEmpty()
        }

        override fun onPlayerError(e: PlaybackException) {
            val player = engine?.player ?: return
            if (e.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                player.seekToDefaultPosition()
                player.prepare()
                return
            }
            // Live streams drop out now and then; retry a few times before showing an error.
            if (retries < MAX_RETRIES) {
                retries++
                viewModelScope.launch {
                    delay(1_000L * retries)
                    player.prepare()
                }
            } else {
                error = "This channel isn't playing right now. Try again later or pick another channel."
            }
        }
    }

    init {
        viewModelScope.launch {
            settings = c.settings.current()
            mapper = KeyActionMapper(settings.skipBackSec * 1_000L, settings.skipForwardSec * 1_000L)
            client = c.playlists.get(screen.playlistId)?.let(c.playlists::client)
            ids = c.channels.ids(screen.playlistId, screen.filter)
            engine = PlaybackEngine(appContext, settings).also { it.player.addListener(listener) }
            tuneTo(screen.streamId)
        }
    }

    /** Starts tuning to [streamId], cancelling any tune still in progress so the last request always wins. */
    private fun tuneTo(streamId: Long) {
        targetStreamId = streamId
        tuneJob?.cancel()
        tuneJob = viewModelScope.launch { tune(streamId) }
    }

    private suspend fun tune(streamId: Long) {
        val ch = c.channels.get(screen.playlistId, streamId) ?: return
        val client = client ?: return
        channel = ch
        guideChannel = ch
        error = null
        retries = 0
        val hls = settings.streamFormat == StreamFormat.HLS
        engine?.play(client.liveStreamUrl(ch.streamId, if (hls) "m3u8" else "ts"), hls)
        nowNext.refresh(ch.streamId)
        showControls()
        c.channels.markWatched(ch.playlistId, ch.streamId)
        c.settings.update { it.copy(lastChannelId = ch.streamId) }
    }

    fun onKey(key: RemoteKey, digit: Int = 0): Boolean {
        if (!::mapper.isInitialized) return false
        val action = mapper.map(key, overlay, isLive = true, digit = digit)
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
        if (!::mapper.isInitialized) stop() else perform(mapper.map(RemoteKey.BACK, overlay, isLive = true))
    }

    /** Called when the app goes to the background / comes back: live TV is stopped rather than left streaming. */
    fun onAppHidden() {
        engine?.player?.stop()
    }

    fun onAppShown() {
        engine?.player?.let {
            if (it.playbackState == Player.STATE_IDLE && !exit) {
                it.prepare()
                it.play()
            }
        }
    }

    fun stop() {
        engine?.player?.stop()
        exit = true
    }

    fun togglePlayPause() = perform(PlayerAction.TogglePlayPause)

    fun seekBy(ms: Long) {
        val p = engine?.player ?: return
        if (!p.isCurrentMediaItemSeekable) {
            flash("Skipping isn't available on this live channel")
            return
        }
        p.seekTo((p.currentPosition + ms).coerceAtLeast(0))
        flash(if (ms < 0) "−${-ms / 1000}s" else "+${ms / 1000}s")
    }

    fun zap(step: Int) {
        if (ids.isEmpty()) return
        val i = ids.indexOf(targetStreamId).let { if (it < 0) 0 else it }
        tuneTo(ids[(i + step).mod(ids.size)])
    }

    private fun typeDigit(d: Int) {
        if (typedNumber.length >= 5) return
        typedNumber += d
        numberJob?.cancel()
        numberJob = viewModelScope.launch {
            delay(1_500)
            val n = typedNumber.toIntOrNull()
            typedNumber = ""
            val ch = n?.let { c.channels.byNumber(screen.playlistId, it) }
            if (ch != null) tuneTo(ch.streamId) else flash("No channel $n")
        }
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

    fun openGuide() {
        hideJob?.cancel()
        guideChannel = channel
        overlay = OverlayState.GUIDE
    }

    fun closeGuide() {
        overlay = OverlayState.NONE
    }

    /** Moves the guide panel's preview to the previous/next channel without changing what is playing. */
    fun previewGuide(step: Int) {
        val current = guideChannel?.streamId ?: return
        if (ids.isEmpty()) return
        val i = ids.indexOf(current).let { if (it < 0) 0 else it }
        val next = ids[(i + step).mod(ids.size)]
        viewModelScope.launch { guideChannel = c.channels.get(screen.playlistId, next) }
    }

    fun watchGuideChannel() {
        val target = guideChannel ?: return
        closeGuide()
        if (target.streamId != targetStreamId) tuneTo(target.streamId)
    }

    fun retry() {
        error = null
        retries = 0
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

    private fun flash(text: String) {
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
        const val OVERLAY_TIMEOUT_MS = 5_000L
    }
}
