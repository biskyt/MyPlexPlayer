package com.biskyt.mytvplayer.data

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.room.withTransaction
import com.biskyt.mytvplayer.core.vod.VodTitles
import com.biskyt.mytvplayer.core.xtream.SeriesDetails
import com.biskyt.mytvplayer.core.xtream.VodDetails
import com.biskyt.mytvplayer.core.xtream.XtreamCategory
import com.biskyt.mytvplayer.core.xtream.XtreamClient
import com.biskyt.mytvplayer.data.db.AppDatabase
import com.biskyt.mytvplayer.data.db.CAT_MOVIE
import com.biskyt.mytvplayer.data.db.CAT_SERIES
import com.biskyt.mytvplayer.data.db.MovieEntity
import com.biskyt.mytvplayer.data.db.PlaylistEntity
import com.biskyt.mytvplayer.data.db.SeriesEntity
import com.biskyt.mytvplayer.data.db.VodFavouriteEntity
import com.biskyt.mytvplayer.data.db.WatchProgressEntity
import kotlinx.coroutines.flow.Flow

/** Which movies or series a grid shows. */
sealed interface VodFilter {
    data object All : VodFilter
    data object Favourites : VodFilter

    /** Movies by date added, series by date last updated. */
    data object Newest : VodFilter
    data class Category(val id: String) : VodFilter
}

/**
 * Movies and series (Xtream playlists only): the light lists are stored for fast paging and filtering; details and
 * episodes are fetched when a title is opened and kept briefly in memory.
 */
class VodRepository(private val db: AppDatabase, private val playlists: PlaylistRepository) {
    private val vod = db.vod()
    private val categories = db.categories()

    private val paging = PagingConfig(pageSize = 48, prefetchDistance = 24, initialLoadSize = 72, enablePlaceholders = true)

    fun pageMovies(playlistId: Long, filter: VodFilter, query: String): Flow<PagingData<MovieEntity>> {
        val q = query.trim()
        return Pager(paging) {
            when (filter) {
                VodFilter.All -> vod.pageMovies(playlistId, q)
                VodFilter.Favourites -> vod.pageMovieFavourites(playlistId, q)
                VodFilter.Newest -> vod.pageMoviesByAdded(playlistId, q)
                is VodFilter.Category -> vod.pageMovieCategory(playlistId, filter.id, q)
            }
        }.flow
    }

    fun pageSeries(playlistId: Long, filter: VodFilter, query: String): Flow<PagingData<SeriesEntity>> {
        val q = query.trim()
        return Pager(paging) {
            when (filter) {
                VodFilter.All -> vod.pageSeries(playlistId, q)
                VodFilter.Favourites -> vod.pageSeriesFavourites(playlistId, q)
                VodFilter.Newest -> vod.pageSeriesByUpdated(playlistId, q)
                is VodFilter.Category -> vod.pageSeriesCategory(playlistId, filter.id, q)
            }
        }.flow
    }

    fun observeCategories(playlistId: Long, type: String) = categories.observeIncluded(playlistId, type)

    fun observeMovieCount(playlistId: Long) = vod.observeMovieCount(playlistId)

    fun observeSeriesCount(playlistId: Long) = vod.observeSeriesCount(playlistId)

    suspend fun movie(playlistId: Long, id: Long) = vod.movie(playlistId, id)

    suspend fun series(playlistId: Long, id: Long) = vod.series(playlistId, id)

    // --- favourites and progress ---

    fun observeFavouriteIds(playlistId: Long, kind: String): Flow<List<Long>> = vod.observeFavouriteIds(playlistId, kind)

    suspend fun setFavourite(playlistId: Long, kind: String, id: Long, favourite: Boolean) {
        if (favourite) {
            vod.addFavourite(VodFavouriteEntity(playlistId, kind, id, System.currentTimeMillis()))
        } else {
            vod.removeFavourite(playlistId, kind, id)
        }
    }

    suspend fun progress(playlistId: Long, kind: String, id: Long) = vod.progress(playlistId, kind, id)

    fun observeMovieProgress(playlistId: Long, id: Long) = vod.observeMovieProgress(playlistId, id)

    fun observeSeriesProgress(playlistId: Long, seriesId: Long) = vod.observeSeriesProgress(playlistId, seriesId)

    fun observeCarryOn(playlistId: Long, limit: Int = 12) = vod.observeCarryOn(playlistId, limit)

    suspend fun saveProgress(p: WatchProgressEntity) {
        vod.saveProgress(p)
        vod.trimProgress(p.playlistId, KEEP_PROGRESS)
    }

    // --- details (fetched when opened) ---

    private val details = object : LinkedHashMap<String, Any>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Any>?) = size > DETAILS_CACHE
    }

    suspend fun movieDetails(p: PlaylistEntity, streamId: Long): VodDetails =
        cached("m${p.id}:$streamId") { playlists.client(p).movieDetails(streamId) }

    suspend fun seriesDetails(p: PlaylistEntity, seriesId: Long): SeriesDetails =
        cached("s${p.id}:$seriesId") { playlists.client(p).seriesDetails(seriesId) }

    @Suppress("UNCHECKED_CAST")
    private suspend fun <T : Any> cached(key: String, load: suspend () -> T): T =
        synchronized(details) { details[key] as T? } ?: load().also { synchronized(details) { details[key] = it } }

    fun movieUrl(p: PlaylistEntity, streamId: Long, extension: String) = playlists.client(p).movieUrl(streamId, extension)

    fun episodeUrl(p: PlaylistEntity, episodeId: Long, extension: String) = playlists.client(p).episodeUrl(episodeId, extension)

    // --- sync ---

    /**
     * Downloads the movie and series lists of an Xtream playlist, only for included categories (by default those of
     * the TV's language/country, like live TV). M3U playlists have no separate movie or series lists.
     */
    suspend fun sync(p: PlaylistEntity, onProgress: (Int) -> Unit = {}) {
        if (p.isM3u) return
        val client = playlists.client(p)
        var loaded = 0
        syncMovies(p.id, client) { onProgress(++loaded) }
        syncSeries(p.id, client) { onProgress(++loaded) }
    }

    /** Fetches and stores the movie and series categories, so the category picker can show them before any sync. */
    suspend fun refreshCategories(p: PlaylistEntity) {
        if (p.isM3u) return
        val client = playlists.client(p)
        storeCategories(p.id, CAT_MOVIE, client.movieCategories())
        storeCategories(p.id, CAT_SERIES, client.seriesCategories())
    }

    private suspend fun storeCategories(playlistId: Long, type: String, remote: List<XtreamCategory>) =
        playlists.storeCategories(playlistId, remote.map { XtreamCategory("$type:${it.id}", it.name) }, groupByName = false, type)

    private suspend fun syncMovies(playlistId: Long, client: XtreamClient, onItem: () -> Unit) {
        storeCategories(playlistId, CAT_MOVIE, client.movieCategories())
        val stamp = System.currentTimeMillis()
        val out = Batch(vod::upsertMovies)
        forIncluded(playlistId, CAT_MOVIE, { it.categoryId }, { cat, emit -> client.movies(cat, emit) }) { m ->
            val clean = VodTitles.clean(m.name)
            out.add(
                MovieEntity(
                    playlistId, m.streamId, clean.title, clean.year, "$CAT_MOVIE:${m.categoryId}", m.posterUrl, m.rating, m.added,
                    m.extension, m.number, stamp
                )
            )
            onItem()
        }
        out.flush()
        db.withTransaction {
            vod.deleteStaleMovies(playlistId, stamp)
            categories.refreshCounts(playlistId, CAT_MOVIE)
        }
    }

    private suspend fun syncSeries(playlistId: Long, client: XtreamClient, onItem: () -> Unit) {
        storeCategories(playlistId, CAT_SERIES, client.seriesCategories())
        val stamp = System.currentTimeMillis()
        val out = Batch(vod::upsertSeries)
        forIncluded(playlistId, CAT_SERIES, { it.categoryId }, { cat, emit -> client.series(cat, emit) }) { s ->
            val clean = VodTitles.clean(s.name)
            out.add(
                SeriesEntity(
                    playlistId, s.seriesId, clean.title, s.year ?: clean.year, "$CAT_SERIES:${s.categoryId}", s.coverUrl, s.rating,
                    s.lastModified, s.number, stamp
                )
            )
            onItem()
        }
        out.flush()
        db.withTransaction {
            vod.deleteStaleSeries(playlistId, stamp)
            categories.refreshCounts(playlistId, CAT_SERIES)
        }
    }

    /**
     * Reads items of included categories only: with just a few included, one request per category; otherwise the
     * whole list once, dropping the rest as it streams in.
     */
    private suspend fun <T : Any> forIncluded(
        playlistId: Long,
        type: String,
        categoryOf: (T) -> String,
        fetch: suspend (categoryId: String?, emit: suspend (T) -> Unit) -> Unit,
        onItem: suspend (T) -> Unit
    ) {
        val all = categories.all(playlistId, type)
        val included = all.filter { it.included }.mapTo(HashSet()) { it.categoryId.removePrefix("$type:") }
        if (included.isEmpty()) return
        if (included.size <= PER_CATEGORY_LIMIT && included.size * 2 < all.size) {
            for (cat in included) fetch(cat, onItem)
        } else {
            fetch(null) { item -> if (categoryOf(item) in included) onItem(item) }
        }
    }

    /** Writes rows in batches so a long list never sits in memory. */
    private class Batch<E>(private val write: suspend (List<E>) -> Unit) {
        private val items = ArrayList<E>(BATCH)

        suspend fun add(item: E) {
            items += item
            if (items.size >= BATCH) flush()
        }

        suspend fun flush() {
            if (items.isEmpty()) return
            write(items.toList())
            items.clear()
        }
    }

    companion object {
        private const val BATCH = 500
        private const val PER_CATEGORY_LIMIT = 20
        private const val DETAILS_CACHE = 20
        private const val KEEP_PROGRESS = 300
    }
}
