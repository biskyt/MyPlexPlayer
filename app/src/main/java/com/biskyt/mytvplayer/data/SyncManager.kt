package com.biskyt.mytvplayer.data

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
    data class Channels(val loaded: Int) : SyncState
    data class Vod(val loaded: Int) : SyncState
    data class Guide(val message: String) : SyncState
    data class Failed(val message: String) : SyncState
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
        attempt("Couldn't match channels") { epg.matchMissing(playlists.allIds()) { _state.value = SyncState.Guide(it) } }

    /** Runs [block] while no refresh is running, e.g. replacing everything from a backup. */
    suspend fun <T> whileIdle(block: suspend () -> T): T = mutex.withLock { block() }

    suspend fun refreshNow(playlistId: Long, includeGuide: Boolean = true) = guarded("Couldn't refresh") {
        _state.value = SyncState.Channels(0)
        playlists.syncChannels(playlistId) { _state.value = SyncState.Channels(it) }
        if (includeGuide) importGuide()
        syncVod(playlistId)
    }

    /**
     * Updates the guide now, with this update's own outcome (another refresh may change [state] straight after):
     * the error of each guide that couldn't be loaded, by source id. With [only], just that guide is downloaded (e.g.
     * one just added); the others are used as last loaded.
     */
    suspend fun updateGuide(only: Long? = null): Result<Map<Long, String>> = attempt("Couldn't update the TV guide") { importGuide(only) }

    /** Only movies and series (after their categories change). */
    fun refreshVod(playlistId: Long): Job = scope.launch { guarded("Couldn't load movies and series") { syncVod(playlistId) } }

    private suspend fun syncVod(playlistId: Long) {
        val p = playlists.get(playlistId) ?: return
        // An M3U playlist without movie or series playlists still syncs: to clear any it had before.
        if (p.hasVod) _state.value = SyncState.Vod(0)
        vod.sync(p) { _state.value = SyncState.Vod(it) }
    }

    private suspend fun importGuide(only: Long? = null) = epg.importAll(playlists.allIds(), only) { _state.value = SyncState.Guide(it) }

    /** [attempt] when only the state needs to show the outcome. */
    private suspend fun <T> guarded(failure: String, block: suspend () -> T): T? = attempt(failure, block).getOrNull()

    /**
     * Runs one refresh at a time; any failure (network, bad data, full storage) is shown in [state] and returned
     * (with its message for the user), never allowed to crash the app.
     */
    @Suppress("TooGenericExceptionCaught")
    private suspend fun <T> attempt(failure: String, block: suspend () -> T): Result<T> = mutex.withLock {
        try {
            Result.success(block().also { _state.value = SyncState.Idle })
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val message = e.message ?: failure
            _state.value = SyncState.Failed(message)
            Result.failure(Exception(message, e))
        }
    }
}
