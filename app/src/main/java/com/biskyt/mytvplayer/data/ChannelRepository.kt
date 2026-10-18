package com.biskyt.mytvplayer.data

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.room.withTransaction
import com.biskyt.mytvplayer.data.db.AppDatabase
import com.biskyt.mytvplayer.data.db.CAT_LIVE
import com.biskyt.mytvplayer.data.db.ChannelEntity
import com.biskyt.mytvplayer.data.db.FavouriteEntity
import com.biskyt.mytvplayer.data.db.RecentEntity
import kotlinx.coroutines.flow.Flow

/** Which channels a list shows. Zapping in the player follows the same list. */
sealed interface ChannelFilter {
    data object All : ChannelFilter
    data object Favourites : ChannelFilter
    data object Recent : ChannelFilter
    data class Category(val id: String) : ChannelFilter
}

class ChannelRepository(private val db: AppDatabase) {
    private val channels = db.channels()
    private val favourites = db.favourites()

    /** Paged so thousands of channels cost only the rows on screen. */
    fun page(playlistId: Long, filter: ChannelFilter, query: String): Flow<PagingData<ChannelEntity>> {
        val q = likeArg(query)
        return Pager(PagingConfig(pageSize = 40, prefetchDistance = 20, initialLoadSize = 60, enablePlaceholders = true)) {
            when (filter) {
                ChannelFilter.All -> channels.pageAll(playlistId, q)
                ChannelFilter.Favourites -> channels.pageFavourites(playlistId, q)
                ChannelFilter.Recent -> channels.pageRecent(playlistId, q)
                is ChannelFilter.Category -> channels.pageCategory(playlistId, filter.id, q)
            }
        }.flow
    }

    /** Ordered stream ids of a list, used for up/down zapping in the player. */
    suspend fun ids(playlistId: Long, filter: ChannelFilter): List<Long> = when (filter) {
        ChannelFilter.All -> channels.idsAll(playlistId)
        ChannelFilter.Favourites -> channels.idsFavourites(playlistId)
        ChannelFilter.Recent -> channels.idsRecent(playlistId)
        is ChannelFilter.Category -> channels.idsCategory(playlistId, filter.id)
    }

    suspend fun get(playlistId: Long, streamId: Long) = channels.get(playlistId, streamId)

    suspend fun byNumber(playlistId: Long, number: Int) = channels.byNumber(playlistId, number)

    fun observeCount(playlistId: Long) = channels.observeCount(playlistId)

    fun observeFavouriteIds(playlistId: Long): Flow<List<Long>> = favourites.observeIds(playlistId)

    fun observeFavourites(playlistId: Long, limit: Int = 12) = channels.observeFavourites(playlistId, limit)

    fun observeRecent(playlistId: Long, limit: Int = 12) = channels.observeRecent(playlistId, limit)

    fun observeHidden(playlistId: Long) = channels.observeHidden(playlistId)

    suspend fun setFavourite(playlistId: Long, streamId: Long, favourite: Boolean) {
        if (favourite) {
            favourites.add(
                FavouriteEntity(playlistId, streamId, System.currentTimeMillis())
            )
        } else {
            favourites.remove(playlistId, streamId)
        }
    }

    suspend fun markWatched(playlistId: Long, streamId: Long) {
        favourites.touchRecent(RecentEntity(playlistId, streamId, System.currentTimeMillis()))
        favourites.trimRecent(playlistId, RECENT_LIMIT)
    }

    suspend fun setHidden(playlistId: Long, streamId: Long, hidden: Boolean) {
        channels.setHidden(playlistId, streamId, hidden)
        db.categories().refreshCounts(playlistId, CAT_LIVE)
    }

    /** Moves a channel one place up/down within [filter] by giving it a sort position between its neighbours. */
    suspend fun move(playlistId: Long, filter: ChannelFilter, streamId: Long, delta: Int) {
        val ids = ids(playlistId, filter)
        val index = ids.indexOf(streamId)
        val target = index + delta
        if (index < 0 || target !in ids.indices) return
        // Only the two rows being swapped are read (SQLite limits how many ids an IN clause can take).
        val rows = channels.getMany(playlistId, listOf(streamId, ids[target])).associateBy { it.streamId }
        fun position(id: Long) = rows[id]?.let { it.sortOrder ?: it.number } ?: 0
        val mine = position(streamId)
        val theirs = position(ids[target])
        db.withTransaction {
            if (mine == theirs) {
                // Ties (e.g. a provider that numbers everything 0): give this list distinct positions first.
                ids.forEachIndexed { i, id -> channels.setSortOrder(playlistId, id, (i + 1) * 10) }
                channels.setSortOrder(playlistId, streamId, (target + 1) * 10)
                channels.setSortOrder(playlistId, ids[target], (index + 1) * 10)
            } else {
                channels.setSortOrder(playlistId, streamId, theirs)
                channels.setSortOrder(playlistId, ids[target], mine)
            }
        }
    }

    private companion object {
        const val RECENT_LIMIT = 30
    }
}
