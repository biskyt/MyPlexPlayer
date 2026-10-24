package com.biskyt.mytvplayer.data

import com.biskyt.mytvplayer.data.db.CAT_MOVIE
import com.biskyt.mytvplayer.data.db.CAT_SERIES
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * The active playlist's favourites, for marking them in lists and offering "Add" or "Remove": one source for every
 * screen, each read once while any screen shows them. Null until read (a label saying "Add" would then undo one).
 */
class Favourites(scope: CoroutineScope, settings: SettingsRepository, channelRepo: ChannelRepository, vod: VodRepository) {
    val channels: StateFlow<Set<Long>?> = ids(scope, settings) { channelRepo.observeFavouriteIds(it) }
    val series: StateFlow<Set<Long>?> = ids(scope, settings) { vod.observeFavouriteIds(it, CAT_SERIES) }
    val movies: StateFlow<Set<Long>?> = ids(scope, settings) { vod.observeFavouriteIds(it, CAT_MOVIE) }

    /** [series] or [movies], by CAT_SERIES or CAT_MOVIE. */
    fun of(type: String): StateFlow<Set<Long>?> = if (type == CAT_MOVIE) movies else series

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun ids(scope: CoroutineScope, settings: SettingsRepository, read: (Long) -> Flow<List<Long>>) =
        settings.activePlaylistId.flatMapLatest(read).map { it.toSet() }
            // Back to null once no screen shows them: a screen coming back mustn't act on a set read long ago.
            .stateIn(scope, SharingStarted.WhileSubscribed(5_000, replayExpirationMillis = 0), null)
}
