package com.biskyt.myplexplayer.data

import androidx.annotation.StringRes
import com.biskyt.myplexplayer.R
import com.biskyt.myplexplayer.appString
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** What a background refresh is doing, for the small status line in the UI. */
sealed interface SyncState {
    data object Idle : SyncState

    /** [playlist]'s channels, [loaded] so far. */
    data class Channels(val playlist: String, val loaded: Int) : SyncState

    /** [playlist]'s movies and series, [loaded] so far. */
    data class Vod(val playlist: String, val loaded: Int) : SyncState
    data class Guide(val step: GuideStep) : SyncState

    /** [message] is worked out when shown, so it's in the language shown then. */
    data class Failed(val error: Throwable, @StringRes val fallback: Int) : SyncState {
        val message: String get() = userMessage(error) ?: appString(fallback)
    }
}

/** A step of a guide update, put in words when shown (so in the language shown then); [fraction]: how far along, if known. */
sealed interface GuideStep {
    val fraction: Float?

    data class Downloading(val guide: String, override val fraction: Float? = null) : GuideStep

    data class Reading(val guide: String) : GuideStep {
        override val fraction: Float? get() = null
    }

    /** Matching channels to the guide: [done] of [total] (0 when not counted yet). */
    data class Matching(val done: Int = 0, val total: Int = 0) : GuideStep {
        override val fraction get() = if (total > 0) done.toFloat() / total else null
    }

    /** Loading programmes from [guide] (null: from the guide, unnamed). */
    data class Loading(val guide: String?, override val fraction: Float? = null) : GuideStep
}

/**
 * Runs channel and guide refreshes one at a time in the app's scope and reports progress.
 * Used both from the UI (e.g. after adding a playlist) and the daily background worker.
 */
class SyncManager(
    private val scope: CoroutineScope,
    private val playlists: PlaylistRepository,
    private val vod: VodRepository,
    private val epg: EpgRepository
) {
    private val mutex = Mutex()
    private val _state = MutableStateFlow<SyncState>(SyncState.Idle)
    val state: StateFlow<SyncState> = _state.asStateFlow()

    /** Refreshes a playlist's channels, then the guide, then movies and series (the slowest, so last). */
    fun refresh(playlistId: Long): Job = scope.launch { refreshNow(playlistId) }

    fun refreshAll(): Job = scope.launch { refreshAllNow() }

    /** Every playlist's channels, movies and series, then the guide once for all of them. */
    suspend fun refreshAllNow() {
        val ids = playlists.allIds()
        ids.forEach { refreshNow(it, includeGuide = false) }
        if (ids.isNotEmpty()) updateGuide()
    }

    /** Looks for guides for channels without one. A failure also shows in [state]. */
    suspend fun matchMissingGuides(): Result<MatchResult> =
        attempt(R.string.common_match_failed) { epg.matchMissing(playlists.allIds()) { _state.value = SyncState.Guide(it) } }

    /** Runs [block] while no refresh is running, e.g. replacing everything from a backup. */
    suspend fun <T> whileIdle(block: suspend () -> T): T = mutex.withLock { block() }

    /**
     * [refresh], waiting for it: the outcome is returned (with the error of each guide that couldn't be loaded, by source
     * id, as [updateGuide]), and also shows in [state].
     */
    suspend fun refreshNow(playlistId: Long, includeGuide: Boolean = true): Result<Map<Long, String>> =
        attempt(R.string.data_refresh_failed) {
            val name = playlists.get(playlistId)?.name.orEmpty()
            _state.value = SyncState.Channels(name, 0)
            playlists.syncChannels(playlistId) { _state.value = SyncState.Channels(name, it) }
            val guideErrors = if (includeGuide) importGuide() else emptyMap()
            // Read again (by syncVod): updating the channels stores to the playlist (its account, M3U state).
            syncVod(playlistId)
            guideErrors
        }

    /**
     * Updates the guide now, with this update's own outcome (another refresh may change [state] straight after):
     * the error of each guide that couldn't be loaded, by source id. With [only], just that guide is downloaded (e.g.
     * one just added); the others are used as last loaded.
     */
    suspend fun updateGuide(only: Long? = null): Result<Map<Long, String>> =
        attempt(R.string.common_guide_update_failed) { importGuide(only) }

    /** Only movies and series (after their categories change). */
    fun refreshVod(playlistId: Long): Job = scope.launch { guarded(R.string.data_vod_failed) { syncVod(playlistId) } }

    private suspend fun syncVod(playlistId: Long) {
        val p = playlists.get(playlistId) ?: return
        // An M3U playlist without movie or series playlists still syncs: to clear any it had before.
        if (p.hasVod) _state.value = SyncState.Vod(p.name, 0)
        vod.sync(p) { _state.value = SyncState.Vod(p.name, it) }
    }

    private suspend fun importGuide(only: Long? = null) = epg.importAll(playlists.allIds(), only) { _state.value = SyncState.Guide(it) }

    /** [attempt] when only the state needs to show the outcome. */
    private suspend fun <T> guarded(@StringRes failure: Int, block: suspend () -> T): T? = attempt(failure, block).getOrNull()

    /**
     * Runs one refresh at a time; any failure (network, bad data, full storage) is shown in [state] and returned
     * (with its message for the user), never allowed to crash the app.
     */
    @Suppress("TooGenericExceptionCaught")
    private suspend fun <T> attempt(@StringRes failure: Int, block: suspend () -> T): Result<T> = mutex.withLock {
        try {
            Result.success(block().also { _state.value = SyncState.Idle })
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val failed = SyncState.Failed(e, failure)
            _state.value = failed
            Result.failure(Exception(failed.message, e))
        }
    }
}
