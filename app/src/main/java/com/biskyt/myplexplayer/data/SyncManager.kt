package com.biskyt.myplexplayer.data

import android.util.Log
import androidx.annotation.StringRes
import com.biskyt.myplexplayer.R
import com.biskyt.myplexplayer.appString
import java.util.concurrent.ConcurrentHashMap
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

    /** [message] is worked out when shown, so it's in the language shown then. */
    data class Failed(val error: Throwable, @StringRes val fallback: Int) : SyncState {
        val message: String get() = userMessage(error) ?: appString(fallback)
    }
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

    /** Refreshes a source's channels (and its guide), then movies and series (the slowest, so last). */
    fun refresh(playlistId: Long): Job = scope.launch { refreshNow(playlistId) }

    fun refreshAll(): Job = scope.launch { refreshAllNow() }

    /** Every source's channels and guide, movies and series. */
    suspend fun refreshAllNow() {
        playlists.allIds().forEach { refreshNow(it) }
    }

    /** Runs [block] while no refresh is running, e.g. replacing everything from a backup. */
    suspend fun <T> whileIdle(block: suspend () -> T): T = mutex.withLock { block() }

    /**
     * [refresh], waiting for it: the outcome is returned (with why its guide couldn't be loaded, if it couldn't), and
     * also shows in [state].
     */
    suspend fun refreshNow(playlistId: Long): Result<String?> = attempt(R.string.data_refresh_failed) {
        val name = playlists.get(playlistId)?.name.orEmpty()
        _state.value = SyncState.Channels(name, 0)
        playlists.syncChannels(playlistId) { _state.value = SyncState.Channels(name, it) }
        epg.pruneProgrammes()
        // The guide came with the channels: whether it loaded is kept with it.
        val guideError = epg.plexGuideError(playlistId)
        // Read again (by syncVod): updating the channels stores to the playlist (its account).
        syncVod(playlistId)
        guideError
    }

    /**
     * When each Plex source's play state was last brought up to date: by [refreshPlayState], or by a sync (when its
     * listing began: what plays elsewhere while it runs is after that).
     */
    private val playStateAt = ConcurrentHashMap<Long, Long>()

    /**
     * Back in the app (from another app, or opened again): play state changed meanwhile on a Plex source (in the
     * owner's other Plex apps) is brought here, quickly, without a sync. Not while a sync runs (it brings the same),
     * nor within seconds of the last; out of reach, it waits for next time. Due a sync, [syncIfDue] starts one (the
     * app opening starts its own).
     */
    @Suppress("TooGenericExceptionCaught") // as a sync: whatever goes wrong (a full disk, say) mustn't end the app
    suspend fun refreshPlayState(playlistId: Long, syncIfDue: Boolean) {
        if (!mutex.tryLock()) return
        try {
            val p = playlists.get(playlistId)?.takeIf { it.lastSyncAt > 0 } ?: return
            if (p.isStale()) {
                if (syncIfDue) refresh(p.id) // once this lets go
                return
            }
            val now = System.currentTimeMillis()
            val since = playStateAt[p.id] ?: p.lastSyncAt
            if (now - since < PLAY_STATE_GAP_MS) return
            vod.refreshPlayState(p, since)
            playStateAt[p.id] = now
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Play state not brought in; next time", e)
        } finally {
            mutex.unlock()
        }
    }

    /** Only movies and series (after their categories change). */
    fun refreshVod(playlistId: Long): Job = scope.launch { guarded(R.string.data_vod_failed) { syncVod(playlistId) } }

    private suspend fun syncVod(playlistId: Long) {
        val p = playlists.get(playlistId) ?: return
        _state.value = SyncState.Vod(p.name, 0)
        val started = System.currentTimeMillis()
        vod.sync(p) { _state.value = SyncState.Vod(p.name, it) }
        playStateAt[p.id] = started
    }

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

    private companion object {
        const val TAG = "SyncManager"

        /** Back in the app sooner than this after the last look (or a sync), play state isn't asked for again. */
        const val PLAY_STATE_GAP_MS = 5_000L
    }
}
