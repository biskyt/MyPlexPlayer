package com.biskyt.mytvplayer.data

import android.util.LruCache
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.room.withTransaction
import com.biskyt.mytvplayer.core.vod.VodTitles
import com.biskyt.mytvplayer.core.xtream.SeriesDetails
import com.biskyt.mytvplayer.core.xtream.VodDetails
import com.biskyt.mytvplayer.core.xtream.XtreamCategory
import com.biskyt.mytvplayer.core.xtream.XtreamMovie
import com.biskyt.mytvplayer.core.xtream.XtreamSeries
import com.biskyt.mytvplayer.data.db.AppDatabase
import com.biskyt.mytvplayer.data.db.CAT_MOVIE
import com.biskyt.mytvplayer.data.db.CAT_SERIES
import com.biskyt.mytvplayer.data.db.MovieEntity
import com.biskyt.mytvplayer.data.db.PlaylistEntity
import com.biskyt.mytvplayer.data.db.SeriesEntity
import com.biskyt.mytvplayer.data.db.VodCard
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
 * episodes are fetched when a title is opened and kept briefly in memory. [type] arguments are CAT_MOVIE or CAT_SERIES,
 * which double as the favourite kinds.
 */
class VodRepository(private val db: AppDatabase, private val playlists: PlaylistRepository, private val categoryStore: CategoryStore) {
    private val vod = db.vod()
    private val categories = db.categories()

    // maxSize drops pages scrolled far away, so browsing tens of thousands of titles doesn't keep them all.
    private val paging = PagingConfig(pageSize = 48, prefetchDistance = 24, initialLoadSize = 72, enablePlaceholders = true, maxSize = 300)

    fun pageCards(type: String, playlistId: Long, filter: VodFilter, query: String): Flow<PagingData<VodCard>> {
        val q = query.trim()
        val movies = type == CAT_MOVIE
        return Pager(paging) {
            when (filter) {
                VodFilter.All -> if (movies) vod.pageMovies(playlistId, q) else vod.pageSeries(playlistId, q)
                VodFilter.Favourites -> if (movies) vod.pageMovieFavourites(playlistId, q) else vod.pageSeriesFavourites(playlistId, q)
                VodFilter.Newest -> if (movies) vod.pageMoviesByAdded(playlistId, q) else vod.pageSeriesByUpdated(playlistId, q)
                is VodFilter.Category ->
                    if (movies) vod.pageMovieCategory(playlistId, filter.id, q) else vod.pageSeriesCategory(playlistId, filter.id, q)
            }
        }.flow
    }

    fun observeCount(type: String, playlistId: Long) =
        if (type == CAT_MOVIE) vod.observeMovieCount(playlistId) else vod.observeSeriesCount(playlistId)

    fun observeCategories(playlistId: Long, type: String) = categories.observeIncluded(playlistId, type)

    suspend fun movie(playlistId: Long, id: Long) = vod.movie(playlistId, id)

    suspend fun series(playlistId: Long, id: Long) = vod.series(playlistId, id)

    // --- favourites and progress ---

    fun observeFavourite(playlistId: Long, type: String, id: Long): Flow<Boolean> = vod.observeFavourite(playlistId, type, id)

    suspend fun setFavourite(playlistId: Long, type: String, id: Long, favourite: Boolean) {
        if (favourite) {
            vod.addFavourite(VodFavouriteEntity(playlistId, type, id, System.currentTimeMillis()))
        } else {
            vod.removeFavourite(playlistId, type, id)
        }
    }

    suspend fun progress(playlistId: Long, kind: String, id: Long) = vod.progress(playlistId, kind, id)

    fun observeMovieProgress(playlistId: Long, id: Long) = vod.observeMovieProgress(playlistId, id)

    fun observeSeriesProgress(playlistId: Long, seriesId: Long) = vod.observeSeriesProgress(playlistId, seriesId)

    fun observeCarryOn(playlistId: Long, limit: Int = 12) = vod.observeCarryOn(playlistId, limit, WatchProgressEntity.FINISHED_FRACTION)

    /** Saves progress; [trim] (when playback stops) also drops the oldest rows beyond the cap, in the same write. */
    suspend fun saveProgress(p: WatchProgressEntity, trim: Boolean = false) {
        if (!trim) return vod.saveProgress(p)
        db.withTransaction {
            vod.saveProgress(p)
            vod.trimProgress(p.playlistId, KEEP_PROGRESS)
        }
    }

    // --- details (fetched when opened) ---

    private val movieDetails = LruCache<String, VodDetails>(DETAILS_CACHE)
    private val seriesDetails = LruCache<String, SeriesDetails>(DETAILS_CACHE)

    suspend fun movieDetails(p: PlaylistEntity, streamId: Long): VodDetails =
        movieDetails["${p.id}:$streamId"] ?: playlists.client(p).movieDetails(streamId).also { movieDetails.put("${p.id}:$streamId", it) }

    suspend fun seriesDetails(p: PlaylistEntity, seriesId: Long): SeriesDetails = seriesDetails["${p.id}:$seriesId"]
        ?: playlists.client(p).seriesDetails(seriesId).also { seriesDetails.put("${p.id}:$seriesId", it) }

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
        val movies = syncKind(
            p.id,
            CAT_MOVIE,
            client::movieCategories,
            { cat, emit -> client.movies(cat, emit) },
            XtreamMovie::categoryId,
            vod::insertMovies,
            vod::deleteStaleMovies,
            onProgress
        ) { m, stamp ->
            val clean = VodTitles.clean(m.name)
            MovieEntity(
                p.id, m.streamId, clean.title, clean.year, m.categoryId, m.posterUrl, m.rating, m.added, m.extension, m.number, stamp
            )
        }
        syncKind(
            p.id,
            CAT_SERIES,
            client::seriesCategories,
            { cat, emit -> client.series(cat, emit) },
            XtreamSeries::categoryId,
            vod::insertSeries,
            vod::deleteStaleSeries,
            { onProgress(movies + it) }
        ) { s, stamp ->
            val clean = VodTitles.clean(s.name)
            SeriesEntity(
                p.id, s.seriesId, clean.title, s.year ?: clean.year, s.categoryId, s.coverUrl, s.rating, s.lastModified, s.number, stamp
            )
        }
    }

    /** One list (movies or series): categories, then the included items in batches, then what the provider dropped. */
    @Suppress("LongParameterList") // the two kinds differ only in these
    private suspend fun <T, E> syncKind(
        playlistId: Long,
        type: String,
        fetchCategories: suspend () -> List<XtreamCategory>,
        fetch: suspend (categoryId: String?, emit: suspend (T) -> Unit) -> Unit,
        categoryOf: (T) -> String,
        insert: suspend (List<E>) -> Unit,
        deleteStale: suspend (playlistId: Long, stamp: Long) -> Unit,
        onProgress: (Int) -> Unit,
        toEntity: (T, stamp: Long) -> E
    ): Int {
        categoryStore.store(playlistId, type, fetchCategories())
        val stamp = System.currentTimeMillis()
        // Large batches: an open grid reloads after each one.
        val batch = Batch(BATCH, insert, onProgress)
        categoryStore.forIncluded(playlistId, type, categoryOf, fetch) { batch.add(toEntity(it, stamp)) }
        batch.flush()
        db.withTransaction {
            deleteStale(playlistId, stamp)
            categories.refreshCounts(playlistId, type)
        }
        return batch.count
    }

    private companion object {
        const val BATCH = 2_000
        const val DETAILS_CACHE = 20
        const val KEEP_PROGRESS = 300
    }
}
