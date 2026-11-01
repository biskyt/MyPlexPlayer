package com.biskyt.myplexplayer.ui.player

import android.content.Context
import android.util.Log
import androidx.annotation.VisibleForTesting
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.viewModelScope
import com.biskyt.myplexplayer.AppContainer
import com.biskyt.myplexplayer.R
import com.biskyt.myplexplayer.appString
import com.biskyt.myplexplayer.core.player.OverlayState
import com.biskyt.myplexplayer.core.plex.PlexException
import com.biskyt.myplexplayer.data.PlexLive
import com.biskyt.myplexplayer.data.db.ChannelEntity
import com.biskyt.myplexplayer.data.db.ChannelProgramme
import com.biskyt.myplexplayer.data.db.PlaylistEntity
import com.biskyt.myplexplayer.data.userMessage
import com.biskyt.myplexplayer.ui.Screen
import com.biskyt.myplexplayer.ui.common.ItemAction
import com.biskyt.myplexplayer.ui.common.NumberEntry
import com.biskyt.myplexplayer.ui.common.channelActions
import com.biskyt.myplexplayer.ui.common.cycle
import com.biskyt.myplexplayer.ui.common.favouriteFlash
import com.biskyt.myplexplayer.ui.common.guideNowNext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/** Live TV: zapping through the list it was started from, number entry, now/next and the channel guide panel. */
class PlayerViewModel(c: AppContainer, appContext: Context, private val screen: Screen.Player) : PlaybackViewModel(c, appContext) {
    var channel by mutableStateOf<ChannelEntity?>(null)
        private set

    /** Channel previewed in the guide panel (may differ from the one playing). */
    var guideChannel by mutableStateOf<ChannelEntity?>(null)
        private set

    override val isLive = true

    override val failureMessage = R.string.player_failure_live

    val nowNext = guideNowNext(viewModelScope, c) { screen.playlistId }

    /** What's on now, with its description once read (for the controls and About); read again as the programme changes. */
    var nowDetails by mutableStateOf<ChannelProgramme?>(null)
        private set
    override val about: ChannelProgramme? get() = nowDetails

    init {
        viewModelScope.launch {
            // Read again after a guide update too (a description may be new).
            combine(snapshotFlow { channel?.let { nowNext.entries[it.streamId]?.now } }, c.epg.changes) { p, _ -> p }.collectLatest { p ->
                // In one step, so the About button doesn't go and come back meanwhile.
                nowDetails = p?.let { details(it) }
                // Nothing on (a gap in the guide): nothing to tell of.
                if (p == null && sheet == Sheet.ABOUT) sheet = null
            }
        }
    }

    /** [p] with its description, or as it is if that can't be read (e.g. the guide being replaced just then). */
    @Suppress("TooGenericExceptionCaught")
    private suspend fun details(p: ChannelProgramme) = try {
        c.epg.withDetails(screen.playlistId, p)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w("Player", "Programme details", e)
        p
    }

    /** This playlist's favourite channels, for the More menu's "Add to / Remove from favourites". */
    private var favouriteIds by mutableStateOf(emptySet<Long>())

    override fun menuActions(): List<ItemAction> {
        val ch = channel ?: return emptyList()
        // MyPlexPlayer's own: from the start of a recording, and back to live from there.
        val where = when {
            playingFromStart -> ItemAction.Run(appString(R.string.dvr_watch_live)) {
                sheet = null
                watchLive()
            }
            startOffered() -> ItemAction.Run(appString(R.string.dvr_watch_from_start)) {
                sheet = null
                watchFromStart()
            }
            else -> null
        }
        return listOfNotNull(where) + channelActions(ch.streamId in favouriteIds) { favourite ->
            viewModelScope.launch {
                val saved = c.channels.setFavourite(ch.playlistId, ch.streamId, favourite)
                flash(if (saved) favouriteFlash(favourite) else appString(R.string.common_favourites_not_saved))
            }
        }
    }

    /**
     * Whether "Watch from start" is offered (MyPlexPlayer's own): the channel records its programme now, and the
     * tune's capture buffer holds more than a minute. Asked each time, as a recording can start while it plays.
     */
    private fun startOffered(): Boolean {
        val ch = channel ?: return false
        return live?.buffer?.offersStart == true &&
            c.recordings.current(screen.playlistId).recordingOn(ch.providerEpgId) != null
    }

    /** The choice of live or from the start, offered once when the picture starts; shown when nothing else is open. */
    var startChoice by mutableStateOf(false)
        private set

    /** Whether this tune's picture has started (the choice is offered only the first time). */
    private var pictureSeen = false

    /** Played from the start of the capture buffer: see [keepsWindowStart]. */
    private var playingFromStart = false
    override val keepsWindowStart: Boolean get() = playingFromStart

    override fun onPlayingChanged(playing: Boolean) {
        if (playing) pictureStarted()
    }

    /** The picture started: offers where to watch from, if it applies (see [startOffered]). */
    @VisibleForTesting
    internal fun pictureStarted() {
        if (pictureSeen) return
        pictureSeen = true
        startChoice = startOffered()
    }

    /** Watches live: from the choice, carries on as it is; from the start of the recording, goes back to live. */
    fun watchLive() {
        startChoice = false
        if (playingFromStart) restream(fromStart = false)
    }

    /** Plays the channel's session again from the start of its capture buffer, with no new tune. */
    fun watchFromStart() {
        startChoice = false
        restream(fromStart = true)
    }

    private fun restream(fromStart: Boolean) {
        val tuner = live ?: return
        viewModelScope.launch {
            val stream = try {
                tuner.restream(fromStart)
            } catch (e: PlexException) {
                if (live === tuner) fail(e.userMessage())
                return@launch
            }
            if (live !== tuner) return@launch // another channel meanwhile
            // The stream playing stops first: its failures mustn't reach the retries of this one.
            engine?.player?.stop()
            playingFromStart = fromStart
            resetForNewStream()
            engine?.play(stream.url, hls = true, headers = stream.headers, fromWindowStart = fromStart)
        }
    }

    /** The Plex server: its channels come from its tuner ([live]). */
    private var plexSource: PlaylistEntity? = null
    private var live: PlexLive? = null

    /** A Plex channel comes through the server's tuner, never as a file of its own: always a direct stream. */
    override val playsHow: String?
        get() = live?.let { appString(R.string.player_info_direct_stream_live) }
    private var closing: Job? = null
    private var heartbeat: Job? = null

    private var ids: List<Long> = emptyList()

    /** Whether the channel list (for zapping and the guide panel's ◀ ▶) has loaded: it comes after tuning starts. */
    internal val channelListLoaded: Boolean get() = ids.isNotEmpty()

    private var tuneJob: Job? = null

    /** The channel being tuned to; updated immediately so rapid zapping steps through channels one by one. */
    private var targetStreamId: Long = screen.streamId

    init {
        viewModelScope.launch { c.channels.observeFavouriteIds(screen.playlistId).collect { favouriteIds = it.toSet() } }
        viewModelScope.launch {
            startEngine()
            plexSource = c.playlists.get(screen.playlistId)
            tuneTo(screen.streamId)
            // Only needed for changing channel: not worth delaying the first picture for.
            ids = c.channels.ids(screen.playlistId, screen.filter)
        }
    }

    /** Typed digits switch to that channel number. */
    val numberEntry = NumberEntry(viewModelScope) { n ->
        val ch = c.channels.byNumber(screen.playlistId, n)
        if (ch != null) tuneTo(ch.streamId) else flash(appString(R.string.player_no_channel, n))
    }

    override fun typeDigit(digit: Int) = numberEntry.type(digit)

    /** Starts tuning to [streamId], cancelling any tune still in progress so the last request always wins. */
    private fun tuneTo(streamId: Long) {
        targetStreamId = streamId
        tuneJob?.cancel()
        tuneJob = viewModelScope.launch { tune(streamId) }
    }

    private suspend fun tune(streamId: Long) {
        val ch = c.channels.get(screen.playlistId, streamId) ?: return
        channel = ch
        guideChannel = ch
        previewStreamId = ch.streamId
        resetForNewStream()
        // Its name and what's on show straight away, while a Plex tuner takes its time.
        nowNext.refresh(ch.streamId)
        showControls()
        if (!playPlex(plexSource ?: return fail(), ch)) return
        // Only remember channels actually watched: zapping past one shouldn't write to storage.
        delay(WATCHED_AFTER_MS)
        c.channels.markWatched(ch.playlistId, ch.streamId)
        c.settings.update { it.copy(lastChannelId = ch.streamId) }
    }

    /**
     * A Plex channel: the last one's tuner is let go first (there may be only one), then this one is tuned, once (a
     * second tune can take a second tuner), and its live session played; "Tuning…" shows until the picture starts.
     * False when it couldn't be tuned (the error shows).
     */
    private suspend fun playPlex(p: PlaylistEntity, ch: ChannelEntity): Boolean {
        // The last channel's stream stops first (its session is ending: its retries would fail while this one tunes).
        engine?.player?.stop()
        letTunerGo()
        closing?.join() // also one let go on leaving the app, or the screen remade
        val key = ch.providerEpgId ?: return false.also { fail() }
        tuning = true
        pictureSeen = false
        val (tuner, stream) = try {
            val tuner = c.plex.live(p, key).also { live = it }
            tuner to tuner.start()
        } catch (e: PlexException) {
            fail(e.userMessage())
            return false
        }
        engine?.play(stream.url, hls = true, headers = stream.headers)
        heartbeat = viewModelScope.launch {
            while (true) {
                delay(HEARTBEAT_MS)
                tuner.heartbeat()
            }
        }
        return true
    }

    /** Lets the Plex tuner go, in the app's scope (the screen's own may be ending): [closing] till it has. */
    private fun letTunerGo() {
        heartbeat?.cancel()
        startChoice = false
        playingFromStart = false
        val tuner = live ?: return
        live = null
        closing = c.appScope.launch { tuner.close() }
    }

    /** In the background nothing streams: a Plex channel's tuner goes too, and coming back tunes it again. */
    override fun onAppHidden() {
        super.onAppHidden()
        tuneJob?.cancel()
        letTunerGo()
    }

    override fun resume() = tuneTo(targetStreamId)

    override fun stop() {
        tuneJob?.cancel() // a tune under way goes no further
        letTunerGo()
        super.stop()
    }

    override fun onCleared() {
        letTunerGo()
        super.onCleared()
    }

    /** Asked for: tuned again. */
    override fun retry() = tuneTo(targetStreamId)

    override fun zap(step: Int) {
        if (ids.isNotEmpty()) tuneTo(ids.cycle(targetStreamId, step))
    }

    override fun openGuide() {
        cancelAutoHide()
        previewJob?.cancel()
        guideChannel = channel
        previewStreamId = channel?.streamId
        overlay = OverlayState.GUIDE
    }

    /** The channel the guide panel is moving to: set at once, so quick presses each count (as zapping's [targetStreamId]). */
    private var previewStreamId: Long? = null
    private var previewJob: Job? = null

    /** Moves the guide panel's preview to the previous/next channel without changing what is playing. */
    fun previewGuide(step: Int) {
        val current = previewStreamId ?: return
        if (ids.isEmpty()) return
        val next = ids.cycle(current, step)
        previewStreamId = next
        previewJob?.cancel()
        previewJob = viewModelScope.launch { guideChannel = c.channels.get(screen.playlistId, next) }
    }

    fun watchGuideChannel() {
        val target = previewStreamId ?: return
        closeGuide()
        if (target != targetStreamId) tuneTo(target)
    }

    private companion object {
        const val WATCHED_AFTER_MS = 3_000L

        /** A live Plex session ends unless it's reported this often. */
        const val HEARTBEAT_MS = 10_000L
    }
}
