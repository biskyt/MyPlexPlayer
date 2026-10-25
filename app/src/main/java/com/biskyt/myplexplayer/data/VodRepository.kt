package com.biskyt.myplexplayer.data

import android.util.LruCache
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.room.withTransaction
import com.biskyt.myplexplayer.core.plex.PlexException
import com.biskyt.myplexplayer.core.plex.PlexItem
import com.biskyt.myplexplayer.core.plex.PlexType
import com.biskyt.myplexplayer.core.vod.VodTitles
import com.biskyt.myplexplayer.core.xtream.SeriesDetails
import com.biskyt.myplexplayer.core.xtream.VodDetails
import com.biskyt.myplexplayer.core.xtream.XtreamCategory
import com.biskyt.myplexplayer.core.xtream.XtreamMovie
import com.biskyt.myplexplayer.core.xtream.XtreamSeries
import com.biskyt.myplexplayer.data.db.AppDatabase
import com.biskyt.myplexplayer.data.db.CAT_MOVIE
import com.biskyt.myplexplayer.data.db.CAT_SERIES
import com.biskyt.myplexplayer.data.db.MovieEntity
import com.biskyt.myplexplayer.data.db.PlaylistEntity
import com.biskyt.myplexplayer.data.db.SeriesEntity
import com.biskyt.myplexplayer.data.db.VodCard
import com.biskyt.myplexplayer.data.db.VodFavouriteEntity
import com.biskyt.myplexplayer.data.db.WatchProgressEntity
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
 * Movies and series, from a Plex server or an Xtream account. The light lists are stored for fast paging and
 * filtering; details and episodes are fetched when a title is opened and kept briefly in memory. [type] arguments are
 * CAT_MOVIE or CAT_SERIES, which double as the favourite kinds.
 */
class VodRepository(
    private val db: AppDatabase,
    private val playlists: PlaylistRepository,
    private val categoryStore: CategoryStore,
    private val plex: PlexSources,
) {
    private val vod = db.vod()
    private val categories = db.categories()

    // maxSize drops pages scrolled far away, so browsing tens of thousands of titles doesn't keep them all.
    private val paging = PagingConfig(pageSize = 48, prefetchDistance = 24, initialLoadSize = 72, enablePlaceholders = true, maxSize = 300)

    fun pageCards(type: String, playlistId: Long, filter: VodFilter, query: String): Flow<PagingData<VodCard>> {
        val q = likeArg(query)
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

    /** Whether the playlist has any movies or series loaded. */
    suspend fun hasTitles(playlistId: Long) = vod.titleCount(playlistId) > 0

    fun observeCount(type: String, playlistId: Long) =
        if (type == CAT_MOVIE) vod.observeMovieCount(playlistId) else vod.observeSeriesCount(playlistId)

    fun observeCategories(playlistId: Long, type: String) = categories.observeIncluded(playlistId, type)

    suspend fun movie(playlistId: Long, id: Long) = vod.movie(playlistId, id)

    /**
     * The movie or series ([type]) with the same title as a TV programme, if the playlist has one. Looked up by the
     * title's longest word, so punctuation differences don't hide it, then compared in full. An all-ASCII word is
     * preferred, as SQLite ignores case only for ASCII letters ("Élite" stored, "élite" searched).
     */
    suspend fun findTitle(playlistId: Long, title: String, type: String): Long? {
        val key = VodTitles.key(title)
        val words = key.split(' ').filter { it.isNotEmpty() }
        val word = (words.filter { w -> w.all { it.code < 128 } }.ifEmpty { words }).maxByOrNull { it.length } ?: return null
        val q = likeArg(word)
        val found = if (type == CAT_MOVIE) vod.searchMovies(playlistId, q, FIND_LIMIT) else vod.searchSeries(playlistId, q, FIND_LIMIT)
        return found.firstOrNull { VodTitles.key(it.title) == key }?.id
    }

    suspend fun series(playlistId: Long, id: Long) = vod.series(playlistId, id)

    // --- favourites and progress ---

    fun observeFavourite(playlistId: Long, type: String, id: Long): Flow<Boolean> = vod.observeFavourite(playlistId, type, id)

    /** The favourite movies' or series' ids ([type] is CAT_MOVIE or CAT_SERIES). */
    fun observeFavouriteIds(playlistId: Long, type: String): Flow<List<Long>> = vod.observeFavouriteIds(playlistId, type)

    /** The first [limit] favourite movies or series ([type]), as listed. */
    fun observeFavouriteCards(playlistId: Long, type: String, limit: Int): Flow<List<VodCard>> =
        if (type == CAT_MOVIE) vod.observeMovieFavourites(playlistId, limit) else vod.observeSeriesFavourites(playlistId, limit)

    /** A new favourite goes at the end of the user's order. */
    suspend fun setFavourite(playlistId: Long, type: String, id: Long, favourite: Boolean) {
        if (favourite) {
            db.withTransaction {
                vod.addFavourite(
                    VodFavouriteEntity(
                        playlistId,
                        type,
                        id,
                        System.currentTimeMillis(),
                        vod.lastFavouritePosition(playlistId, type) + 1
                    )
                )
            }
        } else {
            vod.removeFavourite(playlistId, type, id)
        }
    }

    /** The favourite movies' or series' ([type]) ids, in their order. */
    suspend fun favouriteOrder(playlistId: Long, type: String): List<Long> =
        if (type == CAT_MOVIE) vod.idsMovieFavourites(playlistId) else vod.idsSeriesFavourites(playlistId)

    /** Stores favourite movies' or series' ([type]) places in the order ([positions]: id to place). */
    suspend fun setFavouritePositions(playlistId: Long, type: String, positions: Map<Long, Long>) = db.withTransaction {
        positions.forEach { (id, position) -> vod.setFavouritePosition(playlistId, type, id, position) }
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

    /** Takes a movie, or a whole series, off "Carry on watching": forgets how far it got (for a series, every episode). */
    suspend fun forgetProgress(p: WatchProgressEntity) {
        val series = p.seriesId
        if (series == null) vod.deleteMovieProgress(p.playlistId, p.itemId) else vod.deleteSeriesProgress(p.playlistId, series)
    }

    // --- details (fetched when opened) ---

    private val movieDetails = LruCache<String, VodDetails>(DETAILS_CACHE)
    private val seriesDetails = LruCache<String, SeriesDetails>(DETAILS_CACHE)

    suspend fun movieDetails(p: PlaylistEntity, streamId: Long): VodDetails = movieDetails.getOrPut("${p.id}:$streamId") {
        if (p.isPlex) {
            withUserPlexErrors { plex.movieDetails(p, streamId) }
        } else {
            withUserXtreamErrors { playlists.client(p).movieDetails(streamId) }
        }
    }

    suspend fun seriesDetails(p: PlaylistEntity, seriesId: Long): SeriesDetails = seriesDetails.getOrPut("${p.id}:$seriesId") {
        if (p.isPlex) {
            withUserPlexErrors { plex.seriesDetails(p, seriesId) }
        } else {
            withUserXtreamErrors { playlists.client(p).seriesDetails(seriesId) }
        }
    }

    suspend fun movieUrl(p: PlaylistEntity, m: MovieEntity): String = if (p.isPlex) {
        withUserPlexErrors { plex.playUrl(p, m.streamId) }
    } else {
        playlists.client(p).movieUrl(m.streamId, m.extension)
    }

    suspend fun episodeUrl(p: PlaylistEntity, episodeId: Long, extension: String): String = if (p.isPlex) {
        withUserPlexErrors { plex.playUrl(p, episodeId) }
    } else {
        playlists.client(p).episodeUrl(episodeId, extension)
    }

    // --- sync ---

    /**
     * Downloads the movie and series lists, only for included categories (by default those of the TV's
     * language/country, like live TV; every library of a Plex server).
     */
    suspend fun sync(p: PlaylistEntity, onProgress: (Int) -> Unit = {}) {
        if (p.isPlex) return syncPlex(p, onProgress)
        val client = playlists.client(p)
        var failure: java.io.IOException? = null

        // Each list on its own: a broken movie list shouldn't stop series loading (the failure is reported after).
        suspend fun guarded(block: suspend () -> Int): Int = try {
            block()
        } catch (e: java.io.IOException) {
            failure = failure ?: e
            0
        }
        val movies = guarded {
            syncKind(
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
        }
        guarded {
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
        failure?.let { throw it }
    }

    /**
     * A Plex server's films and series: each library is a category (all of them included to start with). A server
     * that can't be reached at its address is looked for again (its address may have changed), once.
     */
    private suspend fun syncPlex(p: PlaylistEntity, onProgress: (Int) -> Unit) {
        try {
            syncPlexAt(p, onProgress)
        } catch (e: PlexException) {
            val moved = e.reason == PlexException.Reason.UNREACHABLE
            syncPlexAt(plex.reconnect(p).takeIf { moved } ?: throw e, onProgress)
        }
        // Films and series are all a Plex source has for now: loaded, it's up to date.
        db.playlists().setLastSync(p.id, System.currentTimeMillis())
    }

    private suspend fun syncPlexAt(p: PlaylistEntity, onProgress: (Int) -> Unit) {
        val client = plex.client(p)
        // Fetched here, not by syncKind, which reads a failure as "no films": a server that can't be reached says so.
        val libraries = plex.libraries(client)
        var position = 0 // the server's order (by title), across libraries

        // Asked for all at once, the included libraries: Plex lists one library at a time anyway.
        fun titles(type: PlexType, category: String): suspend (String?, suspend (Pair<String, PlexItem>) -> Unit) -> Unit =
            { library, emit ->
                val ids = library?.let(::listOf) ?: categories.all(p.id, category).filter { it.included }.map { it.categoryId }
                ids.forEach { plex.titles(client, type, it, emit) }
            }
        val movies = syncKind(
            p.id,
            CAT_MOVIE,
            { libraries.getValue(PlexType.MOVIE) },
            titles(PlexType.MOVIE, CAT_MOVIE),
            { it.first },
            vod::insertMovies,
            vod::deleteStaleMovies,
            onProgress,
            libraries = true
        ) { title, stamp -> plex.movie(client, p.id, title, position++, stamp) }
        syncKind(
            p.id,
            CAT_SERIES,
            { libraries.getValue(PlexType.SHOW) },
            titles(PlexType.SHOW, CAT_SERIES),
            { it.first },
            vod::insertSeries,
            vod::deleteStaleSeries,
            { onProgress(movies + it) },
            libraries = true
        ) { title, stamp -> plex.series(client, p.id, title, position++, stamp) }
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
        /** A Plex server's libraries (see CategoryStore.store): every one included, and none means none. */
        libraries: Boolean = false,
        /** Null for an item to leave out. */
        toEntity: (T, stamp: Long) -> E?
    ): Int {
        // Live-only accounts have no movie or series section (the request fails or returns nothing): nothing to do.
        val remote = runCatching { fetchCategories() }.getOrDefault(emptyList())
        if (remote.isEmpty() && !libraries) return 0
        categoryStore.store(playlistId, type, remote, libraries = libraries)
        val stamp = System.currentTimeMillis()
        // Large batches: an open grid reloads after each one.
        val batch = Batch(BATCH, insert, onProgress)
        categoryStore.forIncluded(playlistId, type, categoryOf, fetch) { item -> toEntity(item, stamp)?.let { batch.add(it) } }
        batch.flush()
        db.withTransaction {
            deleteStale(playlistId, stamp)
            categories.refreshCounts(playlistId, type)
        }
        return batch.count
    }

    /** The cached value for [key], or [load]'s, cached. */
    private inline fun <V : Any> LruCache<String, V>.getOrPut(key: String, load: () -> V): V = get(key) ?: load().also { put(key, it) }

    private companion object {
        const val BATCH = 2_000
        const val DETAILS_CACHE = 20
        const val KEEP_PROGRESS = 300
        const val FIND_LIMIT = 500
    }
}
