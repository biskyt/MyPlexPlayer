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
    private val epg: EpgRepository,
) {
    private val mutex = Mutex()
    private val _state = MutableStateFlow<SyncState>(SyncState.Idle)
    val state: StateFlow<SyncState> = _state.asStateFlow()

    /** Refreshes a playlist's channels, then the guide. */
    fun refresh(playlistId: Long, includeGuide: Boolean = true): Job = scope.launch { refreshNow(playlistId, includeGuide) }

    fun refreshGuide(): Job = scope.launch { refreshGuideNow() }

    suspend fun refreshNow(playlistId: Long, includeGuide: Boolean = true) {
        mutex.withLock {
            try {
                _state.value = SyncState.Channels(0)
                playlists.syncChannels(playlistId) { _state.value = SyncState.Channels(it) }
                playlists.get(playlistId)?.let { epg.ensureProviderSource(it, playlists.client(it).xmltvUrl()) }
                if (includeGuide) importGuide()
                _state.value = SyncState.Idle
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Any failure (network, bad data, full storage) is shown, never allowed to crash the app.
                _state.value = SyncState.Failed(e.message ?: "Couldn't refresh")
            }
        }
    }

    suspend fun refreshGuideNow() {
        mutex.withLock {
            try {
                importGuide()
                _state.value = SyncState.Idle
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = SyncState.Failed(e.message ?: "Couldn't update the TV guide")
            }
        }
    }

    private suspend fun importGuide() {
        val ids = playlists.allIds()
        epg.importAll(ids) { _state.value = SyncState.Guide(it) }
    }
}
