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
import kotlinx.coroutines.flow.distinctUntilChanged

/** Which channels a list shows. Zapping in the player follows the same list. */
sealed interface ChannelFilter {
    data object All : ChannelFilter
    data object Favourites : ChannelFilter
    data object Recent : ChannelFilter

    /** Channels whose provider keeps past programmes to watch again. */
    data object Catchup : ChannelFilter

    /** The catch-up channels of one category. */
    data class CatchupCategory(val id: String) : ChannelFilter

    /** A catch-up list (shown on the Catch-up tab, and never remembered for Live TV). */
    val isCatchup: Boolean get() = this == Catchup || this is CatchupCategory
    data class Category(val id: String) : ChannelFilter

    /** For remembering a list in settings: "all", "favourites", "recent", "catchup[:<id>]" or "category:<id>". */
    val key: String
        get() = when (this) {
            All -> "all"
            Favourites -> "favourites"
            Recent -> "recent"
            Catchup -> "catchup"
            is CatchupCategory -> "catchup:$id"
            is Category -> "category:$id"
        }

    companion object {
        fun of(key: String): ChannelFilter = when {
            key == "favourites" -> Favourites
            key == "recent" -> Recent
            key == "catchup" -> Catchup
            key.startsWith("catchup:") -> CatchupCategory(key.removePrefix("catchup:"))
            key.startsWith("category:") -> Category(key.removePrefix("category:"))
            else -> All
        }
    }
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
                ChannelFilter.Catchup -> channels.pageCatchup(playlistId, null, q)
                is ChannelFilter.CatchupCategory -> channels.pageCatchup(playlistId, filter.id, q)
                is ChannelFilter.Category -> channels.pageCategory(playlistId, filter.id, q)
            }
        }.flow
    }

    /** Ordered stream ids of a list, used for up/down zapping in the player. */
    suspend fun ids(playlistId: Long, filter: ChannelFilter): List<Long> = when (filter) {
        ChannelFilter.All -> channels.idsAll(playlistId)
        ChannelFilter.Favourites -> channels.idsFavourites(playlistId)
        ChannelFilter.Recent -> channels.idsRecent(playlistId)
        ChannelFilter.Catchup -> channels.idsCatchup(playlistId, null)
        is ChannelFilter.CatchupCategory -> channels.idsCatchup(playlistId, filter.id)
        is ChannelFilter.Category -> channels.idsCategory(playlistId, filter.id)
    }

    suspend fun get(playlistId: Long, streamId: Long) = channels.get(playlistId, streamId)

    suspend fun byNumber(playlistId: Long, number: Int) = channels.byNumber(playlistId, number)

    fun observeCount(playlistId: Long) = channels.observeCount(playlistId)

    /** The categories with catch-up channels, with how many each has. */
    fun observeCatchupCategories(playlistId: Long) = channels.observeCatchupCategories(playlistId)

    fun observeHasCatchup(playlistId: Long) = channels.observeHasCatchup(playlistId).distinctUntilChanged()

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

    /** Takes a channel off "Recently watched". */
    suspend fun removeRecent(playlistId: Long, streamId: Long) = favourites.removeRecent(playlistId, streamId)

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
