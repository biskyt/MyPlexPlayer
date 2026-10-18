package com.biskyt.mytvplayer.ui.player

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewModelScope
import com.biskyt.mytvplayer.AppContainer
import com.biskyt.mytvplayer.core.xtream.EpisodeInfo
import com.biskyt.mytvplayer.data.db.PlaylistEntity
import com.biskyt.mytvplayer.data.db.VOD_EPISODE
import com.biskyt.mytvplayer.data.db.VOD_MOVIE
import com.biskyt.mytvplayer.data.db.WatchProgressEntity
import com.biskyt.mytvplayer.ui.Screen
import com.biskyt.mytvplayer.ui.common.formatDuration
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

    var positionMs by mutableLongStateOf(0)
        private set
    var durationMs by mutableLongStateOf(0)
        private set

    /** The episode after this one, if any. */
    var nextEpisode by mutableStateOf<EpisodeInfo?>(null)
        private set

    override val isLive = false
    override val failureMessage = "This can't be played right now. Try again later."

    private var playlist: PlaylistEntity? = null
    private var current: Playing? = null
    private var episodes: List<EpisodeInfo> = emptyList()
    private var tickJob: Job? = null

    /** What is playing, with what progress rows need. */
    private data class Playing(
        val kind: String,
        val itemId: Long,
        val title: String,
        val imageUrl: String?,
        val extension: String,
        val episode: EpisodeInfo? = null
    )

    init {
        viewModelScope.launch {
            startEngine()
            val p = c.playlists.get(screen.playlistId) ?: return@launch stop()
            playlist = p
            try {
                if (screen.seriesId == null) startMovie(p) else startEpisode(p, screen.seriesId, screen.itemId, screen.fromStart)
            } catch (e: java.io.IOException) {
                error = e.message ?: failureMessage
            }
        }
    }

    private suspend fun startMovie(p: PlaylistEntity) {
        val m = c.vod.movie(p.id, screen.itemId) ?: return stop()
        title = m.title
        subtitle = m.year?.toString().orEmpty()
        play(
            p,
            Playing(VOD_MOVIE, m.streamId, m.title, m.posterUrl, m.extension),
            c.vod.movieUrl(p, m.streamId, m.extension),
            screen.fromStart
        )
    }

    private suspend fun startEpisode(p: PlaylistEntity, seriesId: Long, episodeId: Long, fromStart: Boolean) {
        val show = c.vod.series(p.id, seriesId)
        val details = c.vod.seriesDetails(p, seriesId)
        episodes = details.episodes
        val e = episodes.firstOrNull { it.id == episodeId } ?: return stop()
        val showTitle = show?.title ?: e.title
        title = showTitle
        subtitle = episodeLabel(e)
        nextEpisode = episodes.getOrNull(episodes.indexOf(e) + 1)
        val playing = Playing(VOD_EPISODE, e.id, showTitle, show?.coverUrl ?: e.imageUrl, e.extension, e)
        play(p, playing, c.vod.episodeUrl(p, e.id, e.extension), fromStart)
    }

    private suspend fun play(p: PlaylistEntity, playing: Playing, url: String, fromStart: Boolean) {
        current = playing
        resetForNewStream()
        val engine = engine ?: return
        engine.play(url, hls = null)
        // Resume unless it was nearly finished (or the user asked to start over).
        val saved = if (fromStart) null else c.vod.progress(p.id, playing.kind, playing.itemId)
        if (saved != null && !saved.finished && saved.positionMs > RESUME_AFTER_MS) {
            engine.player.seekTo(saved.positionMs)
            flash("Resuming from ${formatDuration(saved.positionMs)}")
        }
        showControls()
        startTicking()
    }

    /** Updates the progress bar while visible and saves progress every so often while playing. */
    private fun startTicking() {
        tickJob?.cancel()
        tickJob = viewModelScope.launch {
            var sinceSave = 0L
            while (isActive) {
                val player = engine?.player ?: break
                positionMs = player.currentPosition
                durationMs = player.duration.coerceAtLeast(0)
                if (player.isPlaying) {
                    sinceSave += TICK_MS
                    if (sinceSave >= SAVE_EVERY_MS) {
                        sinceSave = 0
                        saveProgress()
                    }
                }
                delay(TICK_MS)
            }
        }
    }

    override fun onPlayingChanged(playing: Boolean) {
        if (!playing) viewModelScope.launch { saveProgress() }
    }

    private suspend fun saveProgress() {
        val p = playlist ?: return
        val now = current ?: return
        val player = engine?.player ?: return
        val duration = player.duration.takeIf { it > 0 } ?: return
        c.vod.saveProgress(progressRow(p, now, player.currentPosition, duration, System.currentTimeMillis()))
    }

    private fun progressRow(p: PlaylistEntity, item: Playing, position: Long, duration: Long, at: Long) = WatchProgressEntity(
        playlistId = p.id,
        kind = item.kind,
        itemId = item.itemId,
        positionMs = position,
        durationMs = duration,
        updatedAt = at,
        title = item.title,
        imageUrl = item.imageUrl,
        extension = item.extension,
        seriesId = screen.seriesId,
        season = item.episode?.season,
        episode = item.episode?.number,
        episodeTitle = item.episode?.title
    )

    /** At the end: a movie closes; an episode is marked watched and the next one starts. */
    override fun onEnded() {
        val p = playlist ?: return
        val finished = current ?: return
        viewModelScope.launch {
            val duration = durationMs.coerceAtLeast(1)
            val now = System.currentTimeMillis()
            c.vod.saveProgress(progressRow(p, finished, duration, duration, now))
            val next = nextEpisode
            if (next == null || screen.seriesId == null) {
                stop()
            } else {
                // Saved as the series' latest, so "Carry on watching" shows the next episode even if the user stops now.
                c.vod.saveProgress(
                    progressRow(p, finished.copy(itemId = next.id, extension = next.extension, episode = next), 0, 0, now + 1)
                )
                playNext()
            }
        }
    }

    fun playNext() {
        val p = playlist ?: return
        val next = nextEpisode ?: return
        val seriesId = screen.seriesId ?: return
        viewModelScope.launch {
            saveProgress()
            startEpisode(p, seriesId, next.id, fromStart = true)
        }
    }

    fun startOver() {
        engine?.player?.seekTo(0)
        showControls()
    }

    fun seekTo(fraction: Float) {
        val player = engine?.player ?: return
        if (durationMs > 0) player.seekTo((durationMs * fraction).toLong())
    }

    override fun onAppHidden() {
        viewModelScope.launch { saveProgress() }
        super.onAppHidden()
    }

    override fun stop() {
        // Save before the player stops, while its position is still known.
        val p = playlist
        val item = current
        val player = engine?.player
        if (p != null && item != null && player != null && player.duration > 0) {
            val row = progressRow(p, item, player.currentPosition, player.duration, System.currentTimeMillis())
            c.appScope.launch { c.vod.saveProgress(row) }
        }
        tickJob?.cancel()
        super.stop()
    }

    private companion object {
        const val TICK_MS = 500L
        const val SAVE_EVERY_MS = 15_000L

        /** Less than this in, it just starts from the beginning. */
        const val RESUME_AFTER_MS = 30_000L
    }
}

/** "Series 1 · Episode 2 · Title" (the title only when it says more than "Episode 2"). */
fun episodeLabel(e: EpisodeInfo): String =
    listOfNotNull("Series ${e.season}", "Episode ${e.number}", e.title.takeUnless { it.equals("Episode ${e.number}", ignoreCase = true) })
        .joinToString(" · ")
