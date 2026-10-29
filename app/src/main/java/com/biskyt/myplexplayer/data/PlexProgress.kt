package com.biskyt.myplexplayer.data

import androidx.room.withTransaction
import com.biskyt.myplexplayer.core.plex.PlexItem
import com.biskyt.myplexplayer.data.db.AppDatabase
import com.biskyt.myplexplayer.data.db.VOD_EPISODE
import com.biskyt.myplexplayer.data.db.VOD_MOVIE
import com.biskyt.myplexplayer.data.db.WatchProgressEntity

/**
 * The server's play state in the app's progress rows, so resume points, watched marks and "Carry on watching" match
 * the owner's other Plex apps, with every screen reading the rows as before. The server wins, except over a row
 * still [WatchProgressEntity.pending]: played here, and not yet reported to it (that is sent again first).
 */
class PlexProgress(private val db: AppDatabase) {
    private val vod = db.vod()

    /**
     * The server's state of [items] (films or episodes: [kind]), each made a row by [row] (null: unplayed). A row
     * keeps its place in "Carry on watching": that's [carryOn]'s to set.
     */
    suspend fun positions(playlistId: Long, kind: String, items: List<PlexItem>, row: (PlexItem) -> WatchProgressEntity?) {
        val served = items.mapNotNull { item -> item.ratingKey.toLongOrNull()?.let { it to item } }
        if (served.isEmpty()) return
        db.withTransaction {
            val ids = served.map { it.first }
            // A few (a series' episodes) by id; a whole library at once.
            val existing = (if (ids.size <= BY_ID) vod.progressOf(playlistId, kind, ids) else vod.progressOf(playlistId, kind))
                .associateBy { it.itemId }
            val changed = mutableListOf<WatchProgressEntity>()
            val unplayed = mutableListOf<Long>()
            served.forEach { (id, item) ->
                val local = existing[id]
                if (local?.pending == true) return@forEach
                // Its place in Carry on watching stays, and that another episode follows (known from its series' page).
                val server = row(item)?.let { it.copy(carryOnAt = local?.carryOnAt, hasNext = it.hasNext || local?.hasNext == true) }
                if (server == null) {
                    // Unplayed: its point goes, unless it's an episode Carry on watching suggests next (at its start).
                    if (local != null && (local.carryOnAt == null || local.started)) unplayed += id
                } else if (server != local) {
                    changed += server
                }
            }
            if (changed.isNotEmpty()) vod.saveProgress(changed)
            if (unplayed.isNotEmpty()) vod.deleteProgress(playlistId, kind, unplayed)
        }
    }

    /**
     * The server's Continue Watching ([hub], newest first) as "Carry on watching": its films and episodes (one it
     * suggests next at its start), in its order; everything else leaves the row.
     */
    suspend fun carryOn(playlistId: Long, hub: List<WatchProgressEntity>) = db.withTransaction {
        val order = hubOrder(hub.map { it.updatedAt.takeIf { t -> t > 0 } }, System.currentTimeMillis())
        val listed = hub.zip(order) { row, at -> row.copy(carryOnAt = at) }.associateBy { it.kind to it.itemId }
        listOf(VOD_MOVIE, VOD_EPISODE).forEach { kind ->
            val existing = vod.progressOf(playlistId, kind).associateBy { it.itemId }
            val left = existing.values.filter { it.carryOnAt != null && !it.pending && (kind to it.itemId) !in listed }
                .map { it.copy(carryOnAt = null) }
            val added = listed.values.filter { it.kind == kind }
                .filter { existing[it.itemId]?.pending != true }
                .map { it.copy(hasNext = existing[it.itemId]?.hasNext ?: false) } // known from its series' page
                .filter { it != existing[it.itemId] }
            if (left.isNotEmpty() || added.isNotEmpty()) vod.saveProgress(left + added)
        }
    }

    /** Reports again what didn't reach the server: kept for next time while it can't, let go once it answers. */
    suspend fun sendPending(playlistId: Long, report: suspend (WatchProgressEntity) -> Unit) {
        vod.pendingProgress(playlistId).forEach { row ->
            if (send { report(row) } != Sent.NOT_YET) vod.reported(row.playlistId, row.kind, row.itemId, row.updatedAt)
        }
    }

    companion object {
        /** Up to this many ids are looked up by id (well within SQLite's 999 variables). */
        private const val BY_ID = 500

        /**
         * The order of the server's Continue Watching (newest first) as times to sort "Carry on watching" by: when
         * each was last watched, or just before the one ahead of it for one never played (an episode it suggests
         * next), and never after [now].
         */
        fun hubOrder(lastViewedMs: List<Long?>, now: Long): List<Long> {
            var previous = now
            return lastViewedMs.map { t -> (t?.coerceAtMost(previous) ?: (previous - 1)).also { previous = it } }
        }
    }
}
