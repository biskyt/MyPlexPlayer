package com.biskyt.myplexplayer.data

import com.biskyt.myplexplayer.data.db.CAT_LIVE
import com.biskyt.myplexplayer.data.db.CAT_MOVIE
import com.biskyt.myplexplayer.data.db.CAT_SERIES
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * The active playlist's favourites, for marking them in lists and offering "Add" or "Remove": one source for every
 * screen, each read once while any screen shows them. Null until read (a label saying "Add" would then undo one).
 */
class Favourites(
    private val scope: CoroutineScope,
    settings: SettingsRepository,
    private val channelRepo: ChannelRepository,
    private val vod: VodRepository
) {
    val channels: StateFlow<Set<Long>?> = ids(scope, settings) { channelRepo.observeFavouriteIds(it) }
    val series: StateFlow<Set<Long>?> = ids(scope, settings) { vod.observeFavouriteIds(it, CAT_SERIES) }
    val movies: StateFlow<Set<Long>?> = ids(scope, settings) { vod.observeFavouriteIds(it, CAT_MOVIE) }

    /** [series] or [movies], by CAT_SERIES or CAT_MOVIE. */
    fun of(type: String): StateFlow<Set<Long>?> = if (type == CAT_MOVIE) movies else series

    /** The favourites of [type] (CAT_LIVE for channels, CAT_SERIES or CAT_MOVIE) in the user's order. */
    suspend fun order(playlistId: Long, type: String): List<Long> =
        if (type == CAT_LIVE) channelRepo.favouriteOrder(playlistId) else vod.favouriteOrder(playlistId, type)

    /**
     * Stores [ids] as the order of [type]'s favourites: one moved, or an order put back. Given the order stored [before],
     * only the favourites whose place changed are written (two for a step along a row).
     */
    suspend fun setOrder(playlistId: Long, type: String, ids: List<Long>, before: List<Long> = emptyList()) {
        val positions = ids.withIndex().filter { before.getOrNull(it.index) != it.value }.associate { it.value to it.index + 1L }
        when (type) {
            CAT_LIVE -> channelRepo.setFavouritePositions(playlistId, positions)
            else -> vod.setFavouritePositions(playlistId, type, positions)
        }
    }

    /**
     * A move of [type]'s favourites from [original] ended at [ids]. Channels' order is Plex's: it's saved there now,
     * once, and put back here if Plex doesn't take it: the save, if one is made.
     */
    fun moveEnded(playlistId: Long, type: String, original: List<Long>, ids: List<Long>): Job? {
        if (type != CAT_LIVE || ids == original) return null
        // In the app's scope: leaving the screen as the move ends doesn't cut the save short.
        return scope.launch { if (!channelRepo.saveFavouriteOrder(playlistId, ids)) setOrder(playlistId, type, original, before = ids) }
    }

    companion object {
        /**
         * [ids] with [id] moved [step] places (back if negative), as far as the ends, or as far as place [shown] (from 0)
         * of a list showing only the first ones.
         */
        fun moved(ids: List<Long>, id: Long, step: Int, shown: Int = Int.MAX_VALUE): List<Long> {
            val from = ids.indexOf(id)
            if (from < 0) return ids
            val to = (from + step).coerceIn(0, minOf(ids.lastIndex, maxOf(shown - 1, from)))
            return ids.toMutableList().apply { add(to, removeAt(from)) }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun ids(scope: CoroutineScope, settings: SettingsRepository, read: (Long) -> Flow<List<Long>>) =
        settings.activePlaylistId.flatMapLatest(read).map { it.toSet() }
            // Back to null once no screen shows them: a screen coming back mustn't act on a set read long ago.
            .stateIn(scope, SharingStarted.WhileSubscribed(5_000, replayExpirationMillis = 0), null)
}
