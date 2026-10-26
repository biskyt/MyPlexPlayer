package com.biskyt.myplexplayer.data

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.room.withTransaction
import com.biskyt.myplexplayer.R
import com.biskyt.myplexplayer.appString
import com.biskyt.myplexplayer.data.db.AppDatabase
import com.biskyt.myplexplayer.data.db.CAT_LIVE
import com.biskyt.myplexplayer.data.db.ChannelEntity
import com.biskyt.myplexplayer.data.db.FavouriteEntity
import com.biskyt.myplexplayer.data.db.RecentEntity
import java.io.IOException
import kotlinx.coroutines.flow.Flow

/** Which channels a list shows. Zapping in the player follows the same list. */
sealed interface ChannelFilter {
    data object All : ChannelFilter
    data object Favourites : ChannelFilter
    data object Recent : ChannelFilter

    data class Category(val id: String) : ChannelFilter
}

class ChannelRepository(private val db: AppDatabase, private val plex: PlexSources, private val notices: Notices) {
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

    /**
     * Makes a channel a favourite, or not, in Plex (the account's favourites, which every Plex app shares), then here,
     * where a new one goes at the end of the order. Plex can't be reached, or refuses: nothing changes, a notice says
     * so, and this says false.
     */
    suspend fun setFavourite(playlistId: Long, streamId: Long, favourite: Boolean): Boolean {
        val p = db.playlists().get(playlistId) ?: return false
        val ch = channels.get(playlistId, streamId)?.takeIf { it.providerEpgId != null } ?: return false
        return plex.withFavourites {
            if (!savedInPlex { plex.setFavouriteChannel(p, ch, favourite) }) return@withFavourites false
            if (favourite) {
                db.withTransaction {
                    favourites.add(
                        FavouriteEntity(playlistId, streamId, System.currentTimeMillis(), favourites.lastPosition(playlistId) + 1)
                    )
                }
            } else {
                favourites.remove(playlistId, streamId)
            }
            true
        }
    }

    /** The favourite channels' ids, in their order. */
    suspend fun favouriteOrder(playlistId: Long): List<Long> = channels.idsFavourites(playlistId)

    /** Stores favourite channels' places here only ([positions]: stream id to place), as a move goes. */
    suspend fun setFavouritePositions(playlistId: Long, positions: Map<Long, Long>) = db.withTransaction {
        positions.forEach { (id, position) -> favourites.setPosition(playlistId, id, position) }
    }

    /**
     * Saves the favourite channels' order [ids] (all of them, as moved here) to Plex, where other servers' favourites
     * keep their places. As [setFavourite]: false, with a notice, when Plex doesn't take it.
     */
    suspend fun saveFavouriteOrder(playlistId: Long, ids: List<Long>): Boolean {
        val p = db.playlists().get(playlistId) ?: return false
        val keyOf = channels.getAll(playlistId, ids).associate { it.streamId to it.providerEpgId }
        return plex.withFavourites { savedInPlex { plex.orderFavouriteChannels(p, ids.mapNotNull { keyOf[it] }) } }
    }

    private suspend fun savedInPlex(save: suspend () -> Unit): Boolean = try {
        save()
        true
    } catch (e: IOException) {
        notices.post(Notice(appString(R.string.common_favourites_not_saved), userMessage(e)))
        false
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

    private companion object {
        const val RECENT_LIMIT = 30
    }
}
