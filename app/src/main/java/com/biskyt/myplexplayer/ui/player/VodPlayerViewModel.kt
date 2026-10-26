package com.biskyt.myplexplayer.ui.player

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewModelScope
import androidx.media3.common.PlaybackException
import com.biskyt.myplexplayer.AppContainer
import com.biskyt.myplexplayer.R
import com.biskyt.myplexplayer.appString
import com.biskyt.myplexplayer.core.player.OverlayState
import com.biskyt.myplexplayer.core.plex.PlexException
import com.biskyt.myplexplayer.core.plex.PlexMarker
import com.biskyt.myplexplayer.core.plex.SkipPill
import com.biskyt.myplexplayer.core.plex.skipPill
import com.biskyt.myplexplayer.core.vod.CastMember
import com.biskyt.myplexplayer.core.vod.EpisodeInfo
import com.biskyt.myplexplayer.data.Settings
import com.biskyt.myplexplayer.data.VodStream
import com.biskyt.myplexplayer.data.db.CAT_MOVIE
import com.biskyt.myplexplayer.data.db.CAT_SERIES
import com.biskyt.myplexplayer.data.db.PlaylistEntity
import com.biskyt.myplexplayer.data.db.VOD_EPISODE
import com.biskyt.myplexplayer.data.db.VOD_MOVIE
import com.biskyt.myplexplayer.data.db.WatchProgressEntity
import com.biskyt.myplexplayer.data.userMessage
import com.biskyt.myplexplayer.ui.Screen
import com.biskyt.myplexplayer.ui.common.ItemAction
import com.biskyt.myplexplayer.ui.common.favouriteFlash
import com.biskyt.myplexplayer.ui.common.favouriteLabel
import com.biskyt.myplexplayer.ui.common.formatDuration
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * A movie or an episode: resumes where the user left off, saves progress as it plays, and moves on to the next episode
 * when one finishes.
 */
class VodPlayerViewModel(c: AppContainer, appContext: Context, private val screen: Screen.VodPlayer) : PlaybackViewModel(c, appContext) {
    var title by mutableStateOf("")
        private set

    /** "Series 1 · Episode 2 · The Pilot", or the year for a movie. */
    var subtitle by mutableStateOf("")
        private set

    /** Whole seconds, so the time shown changes once a second (and only while the controls are up). */
    var positionSec by mutableLongStateOf(0)
        private set
    var durationSec by mutableLongStateOf(0)
        private set

    /** The Skip pill for the intro or credits coming or playing (counting down to skipping by itself, or not). */
    var skip by mutableStateOf<SkipPill?>(null)
        private set

    /** Whether OK skips now: the pill shows on its own, with nothing over the picture. */
    val skipsOnOk: Boolean get() = skip != null && overlay == OverlayState.NONE && sheet == null && !dimmed

    /** This title's markers skipped, or whose automatic skip was cancelled: offered only by hand from then on. */
    private val skipHandled = mutableSetOf<PlexMarker>()

    /** On to the next episode ([playNext]) until it plays: nothing more is skipped, nor moved on from, meanwhile. */
    private var movingOn = false

    /** Where playback was at the last tick (null: just started), to tell playing on from seeking. */
    private var lastTickMs: Long? = null

    /** The episode after this one, if any. */
    var nextEpisode by mutableStateOf<EpisodeInfo?>(null)
        private set

    override val isLive = false
    override val failureMessage = R.string.player_failure_vod

    private var playlist: PlaylistEntity? = null
    private var current: Playing? = null
    private var tickJob: Job? = null

    /** What is playing, with what its progress row needs, and where it plays from. */
    private data class Playing(
        val kind: String,
        val itemId: Long,
        val title: String,
        val imageUrl: String?,
        val stream: VodStream,
        val episode: EpisodeInfo? = null
    )

    /** The movie, or the series of the episode: what "Add to favourites" applies to. */
    private val series = screen.seriesId
    private val favouriteType = if (series == null) CAT_MOVIE else CAT_SERIES
    private val favouriteId = series ?: screen.itemId
    private var favourite by mutableStateOf(false)

    override fun menuActions(): List<ItemAction> = listOf(
        ItemAction.Run(appString(R.string.vod_cast_title)) { showCast() },
        ItemAction.Run(favouriteLabel(favourite)) {
            val add = !favourite
            viewModelScope.launch {
                c.vod.setFavourite(screen.playlistId, favouriteType, favouriteId, add)
                flash(favouriteFlash(add))
            }
        },
        // In place of the player: playing from there starts a new player, not a second one over this.
        if (series == null) {
            ItemAction.Open(appString(R.string.common_about_movie), Screen.MovieDetails(screen.playlistId, screen.itemId), replace = true)
        } else {
            val episode = current?.episode
            ItemAction.Open(
                appString(R.string.common_all_episodes),
                Screen.SeriesDetails(screen.playlistId, series, episode?.season, episode?.number),
                replace = true
            )
        }
    )

    /** The cast shown over the player (opened from More), and whose, once read; null while it's closed. */
    var cast by mutableStateOf<Pair<String, List<CastMember>>?>(null)

    /** The film's cast; for an episode its own, else its series' (read from the server only now). */
    private fun showCast() {
        sheet = null
        viewModelScope.launch {
            val playing = current ?: return@launch
            val own = playing.stream.plex?.cast.orEmpty()
            cast = playing.title to own.ifEmpty { series?.let { c.vod.cast(screen.playlistId, it) }.orEmpty() }
        }
    }

    override val playsHow: String?
        get() = current?.stream?.plex?.let {
            appString(if (it.streaming) R.string.player_info_direct_stream else R.string.player_info_direct_play)
        }

    override fun beforeLeaving() = save(stopped = true)

    init {
        viewModelScope.launch { c.vod.observeFavourite(screen.playlistId, favouriteType, favouriteId).collect { favourite = it } }
        viewModelScope.launch {
            startEngine()
            val p = c.playlists.get(screen.playlistId) ?: return@launch stop()
            playlist = p
            try {
                val seriesId = screen.seriesId
                if (seriesId == null) startMovie(p) else startEpisode(p, seriesId, screen.itemId, screen.fromStart, screen.continueSeries)
            } catch (e: java.io.IOException) {
                fail(e.message)
            }
        }
    }

    private suspend fun startMovie(p: PlaylistEntity) {
        val m = c.vod.movie(p.id, screen.itemId) ?: return stop()
        title = m.title
        subtitle = m.year?.toString().orEmpty()
        play(p, Playing(VOD_MOVIE, m.streamId, m.title, m.posterUrl, c.vod.movieStream(p, m)), screen.fromStart)
    }

    /** [continueSeries]: coming from "Carry on watching", where [episodeId] may be one already finished: play the next. */
    private suspend fun startEpisode(
        p: PlaylistEntity,
        seriesId: Long,
        episodeId: Long,
        fromStart: Boolean,
        continueSeries: Boolean = false
    ) {
        val show = c.vod.series(p.id, seriesId)
        val episodes = c.vod.seriesDetails(p, seriesId, withState = false).episodes // playback brings the episode's own
        var index = episodes.indexOfFirst { it.id == episodeId }.takeIf { it >= 0 } ?: return stop()
        if (continueSeries && c.vod.progress(p.id, VOD_EPISODE, episodeId)?.finished == true && index < episodes.lastIndex) index++
        val e = episodes[index]
        title = show?.title ?: episodeName(e)
        subtitle = episodeLabel(e)
        nextEpisode = episodes.getOrNull(index + 1)
        // Saved for Carry on watching: the provider's words only, never the app's (they'd stay in today's language).
        val stream = c.vod.episodeStream(p, e.id)
        play(p, Playing(VOD_EPISODE, e.id, show?.title ?: e.title, show?.coverUrl ?: e.imageUrl, stream, e), fromStart)
    }

    /**
     * A file from Plex in a container the player can't read is streamed by the server instead, repackaged with its
     * picture and sound as they are (once), from where it got to. One the server could only convert stops with why.
     */
    override fun playAnotherWay(e: PlaybackException): Boolean {
        val plex = current?.stream?.plex
        val engine = engine
        // Once only: not when the server's stream is the one that failed.
        val fileUnreadable = e.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED && plex?.streaming == false
        if (plex == null || engine == null || !fileUnreadable) return false
        val position = engine.player.currentPosition
        viewModelScope.launch {
            val stream = try {
                plex.directStream()
            } catch (x: PlexException) {
                return@launch fail(x.userMessage())
            }
            // Moved on to another title meanwhile: this one's stream isn't wanted.
            if (current?.stream?.plex !== plex) return@launch plex.close()
            engine.play(stream.url, hls = true, headers = stream.headers)
            if (position > 0) engine.player.seekTo(position)
        }
        return true
    }

    private suspend fun play(p: PlaylistEntity, playing: Playing, fromStart: Boolean) {
        current = playing
        movingOn = false
        lastTickMs = null
        skipHandled.clear()
        skip = null
        resetForNewStream()
        val engine = engine ?: return
        engine.play(playing.stream.url, hls = null, headers = playing.stream.plex?.stream?.headers.orEmpty())
        // Resume unless it was nearly finished (or the user asked to start over). From Plex, the row has the server's point.
        val saved = if (fromStart) null else c.vod.progress(p.id, playing.kind, playing.itemId)
        if (saved != null && !saved.finished && saved.positionMs > RESUME_AFTER_MS) {
            engine.player.seekTo(saved.positionMs)
            flash(appString(R.string.player_resuming_from, formatDuration(saved.positionMs)))
        }
        showControls()
        startTicking()
    }

    /** Updates the time shown while the controls are up, and saves progress every so often while playing. */
    private fun startTicking() {
        tickJob?.cancel()
        tickJob = viewModelScope.launch {
            var sinceSave = 0L
            while (isActive) {
                val player = engine?.player ?: break
                updateSkip()
                if (overlay == OverlayState.CONTROLS) {
                    positionSec = player.currentPosition / 1000
                    durationSec = player.duration.coerceAtLeast(0) / 1000
                }
                if (player.isPlaying) {
                    sinceSave += TICK_MS
                    if (sinceSave >= SAVE_EVERY_MS) {
                        sinceSave = 0
                        save()
                    }
                }
                delay(TICK_MS)
            }
        }
    }

    /** Pausing (or buffering, or leaving the app) saves where playback got to. */
    override fun onPlayingChanged(playing: Boolean) {
        if (!playing) save()
    }

    /** Where playback is now, as a progress row (null before the duration is known). */
    private fun snapshot(): WatchProgressEntity? {
        val p = playlist
        val item = current
        val player = engine?.player
        val duration = player?.duration?.takeIf { it > 0 }
        if (p == null || item == null || player == null || duration == null) return null
        return WatchProgressEntity(
            playlistId = p.id,
            kind = item.kind,
            itemId = item.itemId,
            positionMs = player.currentPosition.coerceAtMost(duration),
            durationMs = duration,
            updatedAt = System.currentTimeMillis(),
            title = item.title,
            imageUrl = item.imageUrl,
            seriesId = screen.seriesId,
            season = item.episode?.season,
            episode = item.episode?.number,
            episodeTitle = item.episode?.let(::providerTitle),
            hasNext = nextEpisode != null
        )
    }

    /** Taken now and written in the app's scope, so it completes even as the screen closes ([stopped]: playback ends). */
    private fun save(stopped: Boolean = false) {
        // The server's stream ends with playback, saved or not (before it has started, there's nothing to save).
        if (stopped) current?.stream?.plex?.let { plex -> c.appScope.launch { plex.close() } }
        val row = snapshot() ?: return
        val stream = current?.stream ?: return
        val playing = engine?.player?.isPlaying == true
        c.appScope.launch { c.vod.saveProgress(row, stream, playing, stopped) }
    }

    /** At the end: a movie closes; an episode is saved as watched and the next one starts. */
    override fun onEnded() {
        save(stopped = true)
        if (nextEpisode == null) stop() else playNext()
    }

    fun playNext() {
        val p = playlist ?: return
        val next = nextEpisode ?: return
        val seriesId = screen.seriesId ?: return
        if (movingOn) return
        movingOn = true
        skip = null
        save(stopped = true)
        viewModelScope.launch {
            try {
                startEpisode(p, seriesId, next.id, fromStart = true)
            } catch (e: java.io.IOException) { // as at the start: the server didn't answer
                movingOn = false
                fail(e.message)
            }
        }
    }

    /**
     * The pill as playback stands now; a skip whose time has come happens, unless a panel is open or the user is
     * scrubbing (then the pill waits to be pressed).
     */
    private fun updateSkip() {
        val now = engine?.player?.currentPosition
        val pill = pillNow(fromMs = lastTickMs)
        lastTickMs = now
        when {
            pill?.due != true -> skip = pill
            sheet == null && scrubMs == null -> skip(pill.marker)
            else -> skip = pill.copy(due = false)
        }
    }

    private fun pillNow(fromMs: Long? = null): SkipPill? {
        val markers = current?.stream?.plex?.markers
        val player = engine?.player
        if (markers.isNullOrEmpty() || player == null || movingOn || error != null) return null
        return skipPill(markers, player.currentPosition, fromMs) { m ->
            val sec = if (m.type == PlexMarker.INTRO) settings.autoSkipIntroSec else settings.autoSkipCreditsSec
            sec.takeUnless { m in skipHandled || it == Settings.AUTO_SKIP_OFF }
        }
    }

    /** OK or a tap on the pill: past the intro or credits playing now, at once. */
    fun skipNow() {
        pillNow()?.let { skip(it.marker) }
    }

    /** Whether the pill counts down to skipping by itself (Back then cancels it). */
    val autoSkipping: Boolean get() = skip?.countdownSec != null

    /** Back while the pill counts down: no automatic skip of this one; a pill to press shows while it plays. */
    fun cancelAutoSkip() {
        skip?.let { skipHandled += it.marker }
        updateSkip()
    }

    /** Past [marker]; the last credits of an episode go on to the next one, as at its end (and saved as there). */
    private fun skip(marker: PlexMarker) {
        skipHandled += marker
        skip = null
        engine?.player?.seekTo(marker.endMs)
        if (marker.final && nextEpisode != null) playNext()
    }

    fun startOver() {
        cancelScrub()
        engine?.player?.seekTo(0)
        showControls()
    }

    override fun stop() {
        // Saved before the player stops, while its position is still known.
        save(stopped = true)
        tickJob?.cancel()
        super.stop()
    }

    private companion object {
        const val TICK_MS = 500L

        /** Plex asks for a report every 10 seconds while playing. */
        const val SAVE_EVERY_MS = 10_000L

        /** Less than this in, it just starts from the beginning. */
        const val RESUME_AFTER_MS = 30_000L
    }
}

/** The provider's title for [e], as it gave it; null when it gave none. */
fun providerTitle(e: EpisodeInfo): String? = e.title.ifBlank { null }

/**
 * "Series 1 · Episode 2 · Title": the provider's title only when it says more than "Episode 2" (matched in English, as
 * providers write it), so the number isn't there twice. Left out, never reworded.
 */
fun episodeLabel(e: EpisodeInfo): String = providerTitle(e)?.takeUnless { it.equals("Episode ${e.number}", ignoreCase = true) }
    ?.let { appString(R.string.player_episode_label_titled, e.season, e.number, it) }
    ?: appString(R.string.player_episode_label, e.season, e.number)

/** How [e] is shown: the provider's title, or the app's "Episode 2" (in its language) when it gave none. Never saved. */
fun episodeName(e: EpisodeInfo): String = providerTitle(e) ?: appString(R.string.vod_episode, e.number)
