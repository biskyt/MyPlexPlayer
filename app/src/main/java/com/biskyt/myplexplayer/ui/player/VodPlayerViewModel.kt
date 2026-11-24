package com.biskyt.myplexplayer.ui.player

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewModelScope
import androidx.media3.common.PlaybackException
import com.biskyt.myplexplayer.AppContainer
import com.biskyt.myplexplayer.HTTP_READ_TIMEOUT_MS
import com.biskyt.myplexplayer.R
import com.biskyt.myplexplayer.appString
import com.biskyt.myplexplayer.core.player.OverlayState
import com.biskyt.myplexplayer.core.plex.PlexException
import com.biskyt.myplexplayer.core.plex.PlexMarker
import com.biskyt.myplexplayer.core.plex.SkipPill
import com.biskyt.myplexplayer.core.plex.skipPill
import com.biskyt.myplexplayer.core.tmdb.TmdbCredits
import com.biskyt.myplexplayer.core.vod.CastMember
import com.biskyt.myplexplayer.core.vod.EpisodeInfo
import com.biskyt.myplexplayer.core.vod.VodDetails
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
import com.biskyt.myplexplayer.ui.common.favouriteAction
import com.biskyt.myplexplayer.ui.common.favouriteFlash
import com.biskyt.myplexplayer.ui.common.formatDuration
import com.biskyt.myplexplayer.ui.vod.TitlePanel
import com.biskyt.myplexplayer.ui.vod.acting
import com.biskyt.myplexplayer.ui.vod.castAndCrew
import com.biskyt.myplexplayer.ui.vod.episodeAndShowActions
import com.biskyt.myplexplayer.ui.vod.episodeCastAction
import com.biskyt.myplexplayer.ui.vod.personScreen
import com.biskyt.myplexplayer.ui.vod.tabOf
import com.biskyt.myplexplayer.ui.vod.titleActions
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * A movie or an episode: resumes where the user left off, saves progress as it plays, and closes at the end.
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

    /** Where playback was at the last tick (null: just started), to tell playing on from seeking. */
    private var lastTickMs: Long? = null

    /** The episode after this one, if any. */
    private var nextEpisode: EpisodeInfo? = null

    override val isLive = false
    override val failureMessage = R.string.player_failure_vod

    private var playlist: PlaylistEntity? = null
    private var current by mutableStateOf<Playing?>(null)
    private var tickJob: Job? = null

    /** What is playing, with what its progress row needs, and where it plays from. */
    private data class Playing(
        val kind: String,
        val itemId: Long,
        val title: String,
        val imageUrl: String?,
        val stream: VodStream,
        val episode: EpisodeInfo? = null,
        /** The show of [episode] (null when no longer listed). */
        val show: String? = null
    )

    /** The movie, or the series of the episode: what "Add to favourites" applies to. */
    private val series = screen.seriesId
    private val favouriteType = if (series == null) CAT_MOVIE else CAT_SERIES
    private val favouriteId = series ?: screen.itemId
    private var favourite by mutableStateOf(false)

    /** The episode playing as its description tells of it, once read as the series page reads it (see [describe]). */
    private var describedRead by mutableStateOf<DescribedEpisode?>(null)

    /** The episode playing, as the series page describes it: its own words and date until the rest is read. */
    val described: DescribedEpisode?
        get() = current?.let { p -> p.episode?.let { describedRead ?: DescribedEpisode(p.show, it) } }

    // A trailer has none: its title's options are on the title's page.
    override fun menuTitle(): String? = current?.takeIf { screen.trailer == null }?.let { p -> p.episode?.let(::episodeName) ?: p.title }

    /**
     * The same options as the series page's for an episode (MyTVPlayer 1.35.0, the owner, 2026-11-24): this episode's
     * (its description, its own cast and crew), then the show's, but no trailers. A film's are the show's alone.
     */
    override fun menuActions(): List<ItemAction> {
        // A trailer has none: its title's options are on the title's page.
        if (screen.trailer != null) return emptyList()
        readDescribedOnce()
        val d = described ?: return showActions()
        val episode = listOfNotNull(
            episodeDescriptionAction(d) { openPanel(TitlePanel.Describe(d)) },
            episodeCastAction(d.extras?.credits) {
                d.extras?.credits?.let { showCast(CastList(episodeName(d.episode), castAndCrew(it), it.acting())) }
            },
        )
        return episodeAndShowActions(episode, showActions())
    }

    /** The film's, or the episode's show's: as its page offers them, with no trailers. */
    private fun showActions(): List<ItemAction> = listOfNotNull(
        favouriteAction(favourite) { add ->
            viewModelScope.launch {
                c.vod.setFavourite(screen.playlistId, favouriteType, favouriteId, add)
                flash(favouriteFlash(add))
            }
        },
        // In place of the player: playing from there starts a new player, not a second one over this.
        if (series == null) {
            ItemAction.Open(
                appString(R.string.common_about_movie),
                Screen.MovieDetails(screen.playlistId, screen.itemId),
                replace = true
            )
        } else {
            val episode = current?.episode
            ItemAction.Open(
                appString(R.string.common_all_episodes),
                Screen.SeriesDetails(screen.playlistId, series, episode?.season, episode?.number),
                replace = true
            )
        }
    ) + titleActions(
        details,
        trailer = null,
        otherTrailers = null,
        // More like this asks TMDB only when it opens.
        similar = if (tmdbOn) {
            { id -> Screen.MoreLike(screen.playlistId, id, series = series != null, showTitle, tab) }
        } else {
            null
        },
        showCast = { details?.let { d -> showCast(CastList(showTitle, castAndCrew(d), d.cast.size)) } },
        // Only once the details are read: the About panel shows what they hold.
        showAbout = { openPanel(TitlePanel.About) }.takeIf { details != null },
        film = series == null,
    ) + listOfNotNull(playedCastAction())

    /**
     * A film's Cast and crew from the item that plays, while its details are not read (or could not be): the same
     * people, as the server lists them for the item.
     */
    private fun playedCastAction(): ItemAction? {
        val playing = current?.takeIf { details == null && series == null } ?: return null
        val facts = playing.stream.plex?.facts ?: return null
        val credits = TmdbCredits(facts.cast, emptyList(), facts.directors, facts.writers).takeUnless { it.isEmpty } ?: return null
        return ItemAction.Run(appString(R.string.vod_cast_crew)) {
            showCast(CastList(playing.title, castAndCrew(credits), credits.acting()))
        }
    }

    /**
     * The film's or series' details, for About and More like this in the menu; null until read. A film's are read once
     * playback has started (kept, once the film's page has read them); a series' are those its episode list came with.
     * No TMDB request: More like this asks TMDB only when it opens.
     */
    var details by mutableStateOf<VodDetails?>(null)
        private set
    private var tmdbOn by mutableStateOf(false)

    /** A panel open over the player from the menu (About, the episode's description), as the title's page opens it. */
    var panel by mutableStateOf<TitlePanel?>(null)

    private val tab = tabOf(film = series == null)

    private fun openPanel(p: TitlePanel) {
        sheet = null
        panel = p
    }

    /** Cast and crew over the player: [title]'s [people], of whom the first [acting] are the cast (then its crew). */
    data class CastList(val title: String, val people: List<CastMember>, val acting: Int)

    /** Cast and crew shown over the player (opened from More), once read; null while it's closed. */
    var cast by mutableStateOf<CastList?>(null)

    /** Who in [cast] has the remote as it opens: back from a person's page, that person. */
    var castFocus by mutableIntStateOf(0)
        private set

    /**
     * Cast and crew over the player ([shown]): the film's or the show's, as its page lists them, or the episode's own (the
     * server's people, then TMDB's where the server has none).
     */
    private fun showCast(shown: CastList) {
        sheet = null
        castFocus = 0
        cast = shown
    }

    /**
     * The episode's description and its own Cast and crew, read as the series page reads them ([describe]): the
     * server's (the episode played is its full episode), then TMDB's (with a key) where the server has none, once the
     * show's details give its TMDB id.
     */
    private fun readDescribedOnce() {
        // After the details' read (they give the show's TMDB id): More is drawn again when it ends.
        if (describing || !detailsRead) return
        val playing = current ?: return
        val e = playing.episode ?: return
        describing = true
        viewModelScope.launch { describedRead = c.tmdb.describe(playing.show, e, playing.stream.plex?.facts, details?.tmdbId) }
    }

    /**
     * Set once the description's read has started. It starts when More first opens, not with the video: the player asks
     * TMDB nothing until then.
     */
    private var describing = false

    /** Set once the read of the details has ended, whether or not it gave them. */
    private var detailsRead by mutableStateOf(false)

    /** The film's, or the episode's show's, title. */
    private val showTitle: String get() = current?.show ?: current?.title.orEmpty()

    /** The film's or series' details for the menu (see [details]): from what is kept, else one read of a film's. */
    private suspend fun readDetails(p: PlaylistEntity) {
        details = runCatching {
            if (series == null) {
                c.vod.movieDetails(p, screen.itemId, withState = false)
            } else {
                c.vod.seriesDetails(p, series, withState = false).info
            }
        }.getOrNull()
    }

    override val playsHow: String?
        get() = current?.stream?.plex?.let {
            // Streamed only when the player couldn't read the file's container (the server's own reason is just that
            // the app asked it to).
            val container = it.container
            when {
                !it.streaming -> appString(R.string.player_info_direct_play)
                container != null -> appString(R.string.player_info_direct_stream_container, container)
                else -> appString(R.string.player_info_direct_stream)
            }
        }

    override fun beforeLeaving() = save(stopped = true)

    /**
     * The page of person [i] of [cast], or null when the server gave them no id. Before it opens, playback pauses here,
     * which reports the place to the server as paused (or, not playing, it is saved as it is). The player stays under
     * the page: Back plays on from the same place, with the cast open on the same person.
     */
    fun personPage(i: Int): Screen.Person? {
        val shown = cast ?: return null
        val page = shown.people.getOrNull(i)?.let { personScreen(screen.playlistId, it, shown.title, tab, acting = i < shown.acting) }
            ?: return null
        castFocus = i
        val player = engine?.player
        // Pausing saves through onPlayingChanged.
        if (player?.isPlaying == true) player.pause() else save()
        return page
    }

    // Counted here and in PlayerViewModel, not in PlaybackViewModel: that class is shared with MyTVPlayer.
    override fun onCleared() {
        c.sync.playerClosed()
        super.onCleared()
    }

    init {
        c.sync.playerOpened()
        viewModelScope.launch { c.vod.observeFavourite(screen.playlistId, favouriteType, favouriteId).collect { favourite = it } }
        if (screen.trailer == null) viewModelScope.launch { c.tmdb.on.collect { tmdbOn = it } }
        viewModelScope.launch {
            startEngine()
            val p = c.playlists.get(screen.playlistId) ?: return@launch stop()
            playlist = p
            try {
                val seriesId = screen.seriesId
                when {
                    screen.trailer != null -> startTrailer(p, screen.trailer)
                    seriesId == null -> startMovie(p)
                    else -> startEpisode(p, seriesId, screen.itemId, screen.fromStart, screen.continueSeries)
                }
                if (screen.trailer == null) {
                    readDetails(p)
                    detailsRead = true
                }
            } catch (e: java.io.IOException) {
                fail(e.message)
            }
        }
    }

    /** Trailer [id] of the film (or of the series): its title, with "Trailer" over it. */
    private suspend fun startTrailer(p: PlaylistEntity, id: Long) {
        // Gone from the server meanwhile: closed, as a film or episode is.
        title = screen.seriesId?.let { c.vod.series(p.id, it)?.title } ?: c.vod.movie(p.id, screen.itemId)?.title ?: return stop()
        subtitle = appString(R.string.vod_trailer)
        play(p, Playing(VOD_MOVIE, id, title, null, c.vod.trailerStream(p, id)), fromStart = true)
    }

    private suspend fun startMovie(p: PlaylistEntity) {
        val m = c.vod.movie(p.id, screen.itemId) ?: return stop()
        title = m.title
        subtitle = m.year?.toString().orEmpty()
        play(p, Playing(VOD_MOVIE, m.streamId, m.title, m.posterUrl, c.vod.movieStream(p, m)), screen.fromStart)
    }

    /** [continueSeries]: coming from "Continue watching", where [episodeId] may be one already finished: play the next. */
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
        // Saved for Continue watching: the provider's words only, never the app's (they'd stay in today's language).
        val stream = c.vod.episodeStream(p, e.id)
        play(p, Playing(VOD_EPISODE, e.id, show?.title ?: e.title, show?.coverUrl ?: e.imageUrl, stream, e, show?.title), fromStart)
    }

    /**
     * A file from Plex in a container the player can't read is streamed by the server instead, repackaged with its
     * picture and sound as they are (once), from where it got to. One the server could only convert stops with why.
     */
    override fun playAnotherWay(e: PlaybackException): Boolean {
        val plex = current?.stream?.plex
        val engine = engine
        // Once only: not when the server's stream is the one that failed. Nor for an empty file: the server has nothing
        // to stream, and the error says so.
        val fileUnreadable = e.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED && plex?.streaming == false
        if (plex == null || engine == null || !fileUnreadable || engine.sentNothing(engine.currentUrl)) return false
        val position = engine.player.currentPosition
        viewModelScope.launch {
            val stream = try {
                plex.directStream()
            } catch (x: PlexException) {
                return@launch fail(x.userMessage())
            }
            // Moved on to another title meanwhile: this one's stream isn't wanted.
            if (current?.stream?.plex !== plex) return@launch plex.close()
            engine.play(
                stream.url,
                hls = true,
                headers = stream.headers,
                firstPictureMs = DIRECT_STREAM_WAIT_MS,
                rebufferMs = DIRECT_STREAM_WAIT_MS
            )
            if (position > 0) engine.player.seekTo(position)
        }
        return true
    }

    private suspend fun play(p: PlaylistEntity, playing: Playing, fromStart: Boolean) {
        // Where to resume (none to start over), read first: nothing waits between this title becoming current and its
        // playback starting (a save meanwhile would write it with the last one's place), nor between that and the controls.
        val saved = if (fromStart) null else c.vod.progress(p.id, playing.kind, playing.itemId)
        current = playing
        // A resumed title waits too: stopped at once, it keeps its place on Continue watching (the owner, 2026-11-16).
        saving = false
        lastTickMs = null
        skipHandled.clear()
        skip = null
        resetForNewStream()
        val engine = engine ?: return
        // The engine gives a film or an episode its own waits (see PlaybackEngine.play).
        engine.play(playing.stream.url, hls = null, headers = playing.stream.headers)
        // Resume unless it was nearly finished (or the user asked to start over). From Plex, the row has the server's point.
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
                if (overlay == OverlayState.CONTROLS) showTime()
                if (player.isPlaying) {
                    playedMs += TICK_MS
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

    override fun onControlsShown() = showTime()

    /** Where playback is, for the play bar. */
    private fun showTime() {
        val player = engine?.player ?: return
        positionSec = player.currentPosition / 1000
        durationSec = player.duration.coerceAtLeast(0) / 1000
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

    /** How long this title has played since it started (pauses not counted): see [countsAsWatching]. */
    private var playedMs = 0L

    /** Whether progress was saved since this title started playing: later saves then follow at once. */
    private var saving = false

    /** Taken now and written in the app's scope, so it completes even as the screen closes ([stopped]: playback ends). */
    private fun save(stopped: Boolean = false) {
        // A trailer is never saved or reported: it isn't the title itself.
        if (screen.trailer != null) return
        // The server's stream ends with playback, saved or not (before it has started, there's nothing to save).
        if (stopped) current?.stream?.plex?.let { plex -> c.appScope.launch { plex.close() } }
        val row = snapshot() ?: return
        if (!countsAsWatching(playedMs, saving, row.finished)) return
        saving = true
        val stream = current?.stream ?: return
        val playing = engine?.player?.isPlaying == true
        c.appScope.launch { c.vod.saveProgress(row, stream, playing, stopped, nextEpisode) }
    }

    /**
     * At the end, the player closes, as Stop does (the owner, 2026-11-06): the next episode never starts by itself. An
     * episode is saved as watched, with the next one on Continue watching (see [save]).
     */
    override fun onEnded() = stop()

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
        if (markers.isNullOrEmpty() || player == null || error != null) return null
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

    /**
     * Whether Back (the remote's or a touch screen's) now cancels the automatic skip: it counts down with nothing over
     * it (on a dimmed paused screen, Back first wakes it).
     */
    val backCancelsSkip: Boolean get() = autoSkipping && overlay == OverlayState.NONE && !dimmed

    /** Back while the pill counts down: no automatic skip of this one; a pill to press shows while it plays. */
    fun cancelAutoSkip() {
        skip?.let { skipHandled += it.marker }
        updateSkip()
    }

    /** Past [marker]; past the last credits is the end, so the player closes, as at the end. */
    private fun skip(marker: PlexMarker) {
        skipHandled += marker
        skip = null
        engine?.player?.seekTo(marker.endMs)
        if (marker.final) stop()
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

        /**
         * How long the server's direct stream may send nothing. The server repackages the file as it goes (no
         * re-encode), which can be slow to start: longer than a file played directly (ON_DEMAND_REBUFFER_MS).
         */
        const val DIRECT_STREAM_WAIT_MS = HTTP_READ_TIMEOUT_MS
    }
}

/**
 * Whether playback is saved (and so goes to the front of Continue watching): once it has played [COUNTS_AFTER_MS] since
 * it started or resumed (a short look isn't watching it), once it was saved already ([saving]), or at the end.
 */
internal fun countsAsWatching(playedMs: Long, saving: Boolean, finished: Boolean) = saving || finished || playedMs >= COUNTS_AFTER_MS

internal const val COUNTS_AFTER_MS = 30_000L

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
