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

    fun refreshGuide(): Job = scope.launch { refreshGuideNow() }

    fun refreshAll(): Job = scope.launch { refreshAllNow() }

    /** Every playlist's channels, movies and series, then the guide once for all of them. */
    suspend fun refreshAllNow() {
        val ids = playlists.allIds()
        ids.forEach { refreshNow(it, includeGuide = false) }
        if (ids.isNotEmpty()) refreshGuideNow()
    }

    /** Looks for guides for channels without one; null if that failed (the failure shows in [state]). */
    suspend fun matchMissingGuides(): MatchResult? {
        var result: MatchResult? = null
        guarded("Couldn't match channels") { result = epg.matchMissing(playlists.allIds()) { _state.value = SyncState.Guide(it) } }
        return result
    }

    /** Runs [block] while no refresh is running, e.g. replacing everything from a backup. */
    suspend fun <T> whileIdle(block: suspend () -> T): T = mutex.withLock { block() }

    suspend fun refreshNow(playlistId: Long, includeGuide: Boolean = true) = guarded("Couldn't refresh") {
        _state.value = SyncState.Channels(0)
        playlists.syncChannels(playlistId) { _state.value = SyncState.Channels(it) }
        if (includeGuide) importGuide()
        syncVod(playlistId)
    }

    suspend fun refreshGuideNow() = guarded("Couldn't update the TV guide") { importGuide() }

    /** Only movies and series (after their categories change). */
    fun refreshVod(playlistId: Long): Job = scope.launch { guarded("Couldn't load movies and series") { syncVod(playlistId) } }

    private suspend fun syncVod(playlistId: Long) {
        val p = playlists.get(playlistId)?.takeUnless { it.isM3u } ?: return
        _state.value = SyncState.Vod(0)
        vod.sync(p) { _state.value = SyncState.Vod(it) }
    }

    /** Runs one refresh at a time; any failure (network, bad data, full storage) is shown, never allowed to crash the app. */
    @Suppress("TooGenericExceptionCaught")
    private suspend fun guarded(failure: String, block: suspend () -> Unit) {
        mutex.withLock {
            try {
                block()
                _state.value = SyncState.Idle
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = SyncState.Failed(e.message ?: failure)
            }
        }
    }

    private suspend fun importGuide() {
        val ids = playlists.allIds()
        epg.importAll(ids) { _state.value = SyncState.Guide(it) }
    }
}
