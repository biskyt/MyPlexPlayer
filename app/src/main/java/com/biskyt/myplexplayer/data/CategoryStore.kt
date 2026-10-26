package com.biskyt.myplexplayer.data

import androidx.room.withTransaction
import com.biskyt.myplexplayer.core.epg.CategoryLanguageDetector
import com.biskyt.myplexplayer.data.db.AppDatabase
import com.biskyt.myplexplayer.data.db.CategoryEntity
import com.biskyt.myplexplayer.data.db.MAX_SQL_ARGS

/** A category as a server lists it: a Plex library, or a server's channels. */
data class RemoteCategory(val id: String, val name: String)

/**
 * A source's categories of every type (live, movie, series): which are included, and which items to download because
 * of that. Each is grouped by the language or country its name says, if any.
 */
class CategoryStore(private val db: AppDatabase) {
    private val categories = db.categories()

    /**
     * Saves the server's categories of one [type], keeping the user's choices for ones already known. Each new one is
     * included whatever its name (a server is one person's own choice of films), and the list is complete: an empty
     * one means they have all gone.
     */
    suspend fun store(playlistId: Long, type: String, remote: List<RemoteCategory>) {
        val existing = categories.all(playlistId, type).associateBy { it.categoryId }
        val remoteIds = remote.mapTo(HashSet()) { it.id }
        val items = remote.mapIndexed { i, c ->
            val old = existing[c.id]
            CategoryEntity(
                playlistId = playlistId,
                categoryId = c.id,
                name = c.name,
                lang = CategoryLanguageDetector.detect(c.name),
                included = old?.included ?: true,
                sortOrder = old?.sortOrder ?: (existing.size + i),
                channelCount = old?.channelCount ?: 0,
                type = type
            )
        }
        val gone = existing.keys.filter { it !in remoteIds }
        db.withTransaction {
            categories.upsert(items)
            gone.chunked(MAX_SQL_ARGS).forEach { categories.delete(playlistId, type, it) }
        }
    }

    /**
     * Reads the items of included categories of [type]: with just a few included, one request per category;
     * otherwise the whole list once, dropping the rest as it streams in.
     */
    suspend fun <T> forIncluded(
        playlistId: Long,
        type: String,
        categoryOf: (T) -> String,
        fetch: suspend (categoryId: String?, emit: suspend (T) -> Unit) -> Unit,
        onItem: suspend (T) -> Unit
    ) {
        val all = categories.all(playlistId, type)
        val included = all.filter { it.included }.mapTo(HashSet()) { it.categoryId }
        if (included.isEmpty()) return
        // Items are checked in both cases: some panels ignore the category asked for and send everything.
        val keep: suspend (T) -> Unit = { item -> if (categoryOf(item) in included) onItem(item) }
        if (included.size <= PER_CATEGORY_LIMIT && included.size * 2 < all.size) {
            for (cat in included) fetch(cat, keep)
        } else {
            fetch(null, keep)
        }
    }

    private companion object {
        const val PER_CATEGORY_LIMIT = 20
    }
}

/** Writes a long list in batches as it streams in, so it never sits in memory whole. [count] is the total added. */
class Batch<E>(private val size: Int, private val write: suspend (List<E>) -> Unit, private val onFlush: (Int) -> Unit = {}) {
    private val items = ArrayList<E>(size)
    var count = 0
        private set

    suspend fun add(item: E) {
        items += item
        count++
        if (items.size >= size) flush()
    }

    suspend fun flush() {
        if (items.isEmpty()) return
        write(items.toList())
        items.clear()
        onFlush(count)
    }
}
