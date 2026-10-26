package com.biskyt.myplexplayer.data

import androidx.room.withTransaction
import com.biskyt.myplexplayer.core.plex.PlexException
import com.biskyt.myplexplayer.data.db.AppDatabase
import com.biskyt.myplexplayer.data.db.CAT_LIVE
import com.biskyt.myplexplayer.data.db.CAT_MOVIE
import com.biskyt.myplexplayer.data.db.CategoryEntity
import com.biskyt.myplexplayer.data.db.ChannelEntity
import com.biskyt.myplexplayer.data.db.ChannelPrefs
import com.biskyt.myplexplayer.data.db.FavouriteEntity
import com.biskyt.myplexplayer.data.db.PlaylistEntity
import com.biskyt.myplexplayer.data.db.PlexFavouriteSeenEntity
import kotlinx.coroutines.flow.Flow

/** Sources (Plex servers) and their channels. */
class PlaylistRepository(
    private val db: AppDatabase,
    private val settings: SettingsRepository,
    private val epg: EpgRepository,
    private val categoryStore: CategoryStore,
    private val plex: PlexSources
) {
    private val playlists = db.playlists()
    private val categories = db.categories()
    private val channels = db.channels()

    fun observePlaylists(): Flow<List<PlaylistEntity>> = playlists.observeAll()

    suspend fun get(id: Long): PlaylistEntity? = playlists.get(id)

    suspend fun allIds(): List<Long> = playlists.all().map { it.id }

    /**
     * Hides a category, as unticking it in Settings › Languages & categories does: its channels (or titles) go now,
     * without downloading anything, and updates leave it out.
     */
    suspend fun hideCategory(cat: CategoryEntity) {
        val categories = db.categories()
        db.withTransaction {
            categories.setIncluded(cat.playlistId, cat.type, listOf(cat.categoryId), false)
            when (cat.type) {
                CAT_LIVE -> db.channels().deleteCategory(cat.playlistId, cat.categoryId)
                CAT_MOVIE -> db.vod().deleteMovieCategory(cat.playlistId, cat.categoryId)
                else -> db.vod().deleteSeriesCategory(cat.playlistId, cat.categoryId)
            }
            categories.refreshCounts(cat.playlistId, cat.type)
        }
    }

    suspend fun delete(id: Long) {
        db.withTransaction {
            playlists.deleteChannels(id)
            playlists.deleteCategories(id)
            playlists.deleteFavourites(id)
            playlists.deletePlexSeen(id)
            playlists.deleteRecents(id)
            playlists.deleteMappings(id)
            playlists.deleteMovies(id)
            playlists.deleteSeries(id)
            playlists.deleteProgress(id)
            playlists.deleteVodFavourites(id)
            playlists.delete(id)
        }
        epg.removeGuide(id)
        val next = playlists.all().firstOrNull()?.id ?: 0
        if (settings.current().activePlaylistId == id) settings.update { it.copy(activePlaylistId = next) }
    }

    /**
     * Downloads a Plex server's Live TV channels, in one list (Plex has no channel groups), then its guide for the next
     * two days: the channel count. A guide that can't be fetched leaves the last one (and says why with the server); a
     * server without Live TV (or that won't share it: a friend's, refusing), no channels and no guide. A
     * server failing for now (starting up, say) leaves the channels as they were; out of reach, the sync fails, as it
     * would for the films.
     */
    suspend fun syncChannels(playlistId: Long, onProgress: (Int) -> Unit = {}): Int {
        val p = playlists.get(playlistId) ?: return 0
        val lineup = try {
            plex.lineup(p)
        } catch (e: PlexException) {
            val refused = e.reason == PlexException.Reason.UNAUTHORIZED || e.code in HTTP_REFUSED
            if (e.reason == PlexException.Reason.UNREACHABLE) throw e
            if (!refused) return 0
            null
        }
        categoryStore.store(p.id, CAT_LIVE, listOfNotNull(lineup?.let { RemoteCategory(PLEX_CHANNELS, "") }))
        val out = ChannelWriter(p.id, onProgress)
        val keys = HashMap<Long, String>() // by stream id
        lineup?.channels.orEmpty().forEachIndexed { i, ch ->
            val key = ch.tuneKey ?: return@forEachIndexed
            val id = plexChannelId(key)
            keys[id] = key
            out.add(
                ChannelEntity(
                    playlistId = p.id, streamId = id, number = ch.number?.substringBefore('.')?.toIntOrNull() ?: (i + 1),
                    name = ch.name, categoryId = PLEX_CHANNELS, logoUrl = ch.thumb?.takeIf { it.startsWith("http") },
                    providerEpgId = key, hidden = out.hidden(id), sortOrder = out.sortOrder(id), syncStamp = out.stamp
                )
            )
        }
        val count = out.finish()
        if (lineup == null) {
            epg.removeGuide(p.id)
        } else {
            val now = System.currentTimeMillis()
            try {
                val guide = plex.guide(p, lineup.live, now - GUIDE_BEFORE_MS, now + GUIDE_AHEAD_MS)
                epg.savePlexGuide(p, keys, plexGuideRows(guide, lineup.channels))
            } catch (e: PlexException) {
                epg.plexGuideFailed(p, e)
            }
            syncFavourites(p, keys.values.toSet())
        }
        return count
    }

    /**
     * The account's favourite channels in Plex: one made a favourite in Plex since the last sync is one here too (at
     * the end of the user's order; only once it's in [lineup], this sync's channel keys), and one taken off there is
     * taken off here. A choice made here stays until that channel changes in Plex (saving back to Plex isn't proven
     * yet). A channel missing from one sync's lineup changes nothing. Unread, nothing changes.
     */
    private suspend fun syncFavourites(p: PlaylistEntity, lineup: Set<String>) {
        val inPlex = plex.favouriteChannelKeys(p) ?: return
        val favourites = db.favourites()
        db.withTransaction {
            val seen = favourites.plexSeen(p.id).toSet()
            var last = favourites.lastPosition(p.id)
            val now = System.currentTimeMillis()
            (inPlex.intersect(lineup) - seen).forEach { favourites.add(FavouriteEntity(p.id, plexChannelId(it), now, ++last)) }
            (seen - inPlex).forEach { favourites.remove(p.id, plexChannelId(it)) }
            // Seen: what was applied, so one not yet in the lineup is added once it is.
            val nowSeen = seen.intersect(inPlex) + inPlex.intersect(lineup)
            if (nowSeen != seen) {
                favourites.clearPlexSeen(p.id)
                favourites.addPlexSeen(nowSeen.map { PlexFavouriteSeenEntity(p.id, it) })
            }
        }
    }

    /**
     * Writes one sync's channels in batches (keeping the user's hidden/order choices), then removes channels the
     * provider no longer lists and updates category counts.
     */
    private inner class ChannelWriter(private val playlistId: Long, onProgress: (Int) -> Unit) {
        val stamp = System.currentTimeMillis()
        private var prefs: Map<Long, ChannelPrefs>? = null
        private val batch = Batch(BATCH, channels::upsert, onProgress)

        private suspend fun prefs() = prefs ?: channels.prefs(playlistId).associateBy { it.streamId }.also { prefs = it }

        suspend fun hidden(id: Long) = prefs()[id]?.hidden ?: false

        suspend fun sortOrder(id: Long) = prefs()[id]?.sortOrder

        suspend fun add(ch: ChannelEntity) = batch.add(ch)

        suspend fun finish(): Int {
            batch.flush()
            db.withTransaction {
                channels.deleteStale(playlistId, stamp)
                categories.refreshCounts(playlistId, CAT_LIVE)
            }
            playlists.setLastSync(playlistId, stamp)
            return batch.count
        }
    }

    companion object {
        private const val BATCH = 500

        /** A server's answers that mean it won't give this user Live TV (as a friend's shared server does). */
        private val HTTP_REFUSED = 400..499

        /** A Plex server's one channel list (its category id). */
        const val PLEX_CHANNELS = "plex-live"

        /** The Plex guide is kept from an hour back (what's on now) for two days (four of PlexSources' requests). */
        private const val GUIDE_BEFORE_MS = 3_600_000L
        private const val GUIDE_AHEAD_MS = 47 * 3_600_000L
    }
}
