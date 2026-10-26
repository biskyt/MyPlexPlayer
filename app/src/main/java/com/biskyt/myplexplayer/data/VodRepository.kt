package com.biskyt.myplexplayer.data

import android.util.LruCache
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.room.withTransaction
import com.biskyt.myplexplayer.core.plex.PlexClient
import com.biskyt.myplexplayer.core.plex.PlexException
import com.biskyt.myplexplayer.core.plex.PlexItem
import com.biskyt.myplexplayer.core.plex.PlexPlayState
import com.biskyt.myplexplayer.core.plex.PlexType
import com.biskyt.myplexplayer.core.vod.SeriesDetails
import com.biskyt.myplexplayer.core.vod.VodDetails
import com.biskyt.myplexplayer.core.vod.VodTitles
import com.biskyt.myplexplayer.data.db.AppDatabase
import com.biskyt.myplexplayer.data.db.CAT_MOVIE
import com.biskyt.myplexplayer.data.db.CAT_SERIES
import com.biskyt.myplexplayer.data.db.MovieEntity
import com.biskyt.myplexplayer.data.db.PlaylistEntity
import com.biskyt.myplexplayer.data.db.VOD_EPISODE
import com.biskyt.myplexplayer.data.db.VOD_MOVIE
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
 * Movies and series, from a Plex server. The light lists are stored for fast paging and
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

    /** The newest movies, or the series with the newest episodes (Plex), for Home. */
    fun observeNewestCards(playlistId: Long, type: String, limit: Int): Flow<List<VodCard>> =
        if (type == CAT_MOVIE) vod.observeNewestMovies(playlistId, limit) else vod.observeNewestSeries(playlistId, limit)

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

    /**
     * Where playback of [stream] has got to ([row]), as it plays ([playing]) or pauses, or when it [stopped]. From
     * Plex it's reported to the server too, the row marked pending until it gets there. Otherwise a stop also drops
     * the oldest rows beyond the cap (Plex's mirror the server's, so aren't capped).
     */
    suspend fun saveProgress(row: WatchProgressEntity, stream: VodStream, playing: Boolean, stopped: Boolean) {
        val plex = stream.plex ?: return saveProgress(row, trim = stopped)
        val state = when {
            stopped -> PlexPlayState.STOPPED
            playing -> PlexPlayState.PLAYING
            else -> PlexPlayState.PAUSED
        }
        val sent = plex.report(state, row.positionMs, row.updatedAt) { vod.saveProgress(row.copy(pending = true)) }
        if (sent != null && sent != Sent.NOT_YET) vod.reported(row.playlistId, row.kind, row.itemId, row.updatedAt)
    }

    /** Saves progress; [trim] (when playback stops) also drops the oldest rows beyond the cap, in the same write. */
    suspend fun saveProgress(p: WatchProgressEntity, trim: Boolean = false) {
        if (!trim) return vod.saveProgress(p)
        db.withTransaction {
            vod.saveProgress(p)
            vod.trimProgress(p.playlistId, KEEP_PROGRESS)
        }
    }

    /**
     * Whether [card] (a film, or a series: [type]) has been watched, for "Mark as watched / unwatched"; null where
     * that isn't offered (a series whose count isn't known, or a source gone).
     */
    suspend fun watched(playlistId: Long, type: String, card: VodCard): Boolean? {
        if (playlists.get(playlistId) == null) return null
        return if (type == CAT_MOVIE) vod.progress(playlistId, VOD_MOVIE, card.id)?.finished == true else card.unwatched?.let { it == 0 }
    }

    /**
     * Marks a film, episode or whole series ([id]; [series]: the series it is or is in, null for a film) watched or
     * unwatched on its Plex server; false if it couldn't be told.
     */
    suspend fun setWatched(playlistId: Long, id: Long, series: Long?, watched: Boolean): Boolean {
        val source = playlists.get(playlistId) ?: return false
        return send { plex.setWatched(source, id, series, watched) } == Sent.SENT
    }

    /**
     * Takes a movie, or a whole series, off "Carry on watching", keeping how far it got (the server does). It comes off
     * the server's Continue Watching first: out of reach, it stays on (false), as it would come back with the next sync.
     */
    suspend fun forgetProgress(p: WatchProgressEntity): Boolean {
        val series = p.seriesId
        val source = playlists.get(p.playlistId) ?: return false
        if (send { plex.removeFromContinueWatching(source, p.itemId) } == Sent.NOT_YET) return false
        if (series == null) vod.leaveMovieCarryOn(p.playlistId, p.itemId) else vod.leaveSeriesCarryOn(p.playlistId, series)
        return true
    }

    // --- details (fetched when opened) ---

    private val movieDetails = LruCache<String, VodDetails>(DETAILS_CACHE)
    private val seriesDetails = LruCache<String, SeriesDetails>(DETAILS_CACHE)

    /**
     * A movie's page (kept, once fetched), with the film's play state: brought again each time when [withState] (a
     * page opening), even when the page is kept.
     */
    suspend fun movieDetails(p: PlaylistEntity, streamId: Long, withState: Boolean = true): VodDetails {
        val key = "${p.id}:$streamId"
        movieDetails.get(key)?.let { kept ->
            if (withState) offlineOk { plex.movieState(p, streamId) }
            return kept
        }
        return withUserPlexErrors { plex.movieDetails(p, streamId) }.also { movieDetails.put(key, it) }
    }

    /** A series' page and episodes (kept, once fetched), with their play state, as [movieDetails]. */
    suspend fun seriesDetails(p: PlaylistEntity, seriesId: Long, withState: Boolean = true): SeriesDetails {
        val key = "${p.id}:$seriesId"
        seriesDetails.get(key)?.let { kept ->
            if (withState) offlineOk { plex.seriesState(p, seriesId) }
            return kept
        }
        return withUserPlexErrors { plex.seriesDetails(p, seriesId) }.also { seriesDetails.put(key, it) }
    }

    suspend fun movieStream(p: PlaylistEntity, m: MovieEntity): VodStream = plexStream(p, m.streamId, VOD_MOVIE)

    suspend fun episodeStream(p: PlaylistEntity, episodeId: Long): VodStream = plexStream(p, episodeId, VOD_EPISODE)

    private suspend fun plexStream(p: PlaylistEntity, id: Long, kind: String) =
        withUserPlexErrors { plex.playback(p, id, kind).let { VodStream(it.stream.url, it) } }

    // --- sync ---

    /** A Plex source's play state, brought up to date since [sinceMs] (see [PlexSources.refreshPlayState]). */
    suspend fun refreshPlayState(p: PlaylistEntity, sinceMs: Long) =
        plex.refreshPlayState(p, includedLibraries(p.id, CAT_MOVIE), includedLibraries(p.id, CAT_SERIES), sinceMs)

    /** A Plex source's libraries of films or series ([category]) included here. */
    private suspend fun includedLibraries(playlistId: Long, category: String) =
        categories.included(playlistId, category).map { it.categoryId }

    /**
     * Downloads a Plex server's films and series, only for included libraries: each library is a category (all of them
     * included to start with). A server that can't be reached at its address is looked for again (its address may
     * have changed), once.
     */
    suspend fun sync(p: PlaylistEntity, onProgress: (Int) -> Unit = {}) {
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
        plex.sendPending(p, client)
        // A server that can't be reached says so here (nothing is written).
        val libraries = plex.libraries(client)
        var position = 0 // the server's order (by title), across libraries
        val films = mutableListOf<PlexItem>() // for their play state, after
        val newest = HashMap<Long, Int>() // series' places on Home's recently added row, as each TV library is listed
        var tvLibraries = 0

        // Asked for all at once, the included libraries: Plex lists one library at a time anyway.
        fun titles(type: PlexType, category: String): suspend (String?, suspend (Pair<String, PlexItem>) -> Unit) -> Unit =
            { library, emit ->
                val ids = library?.let(::listOf) ?: includedLibraries(p.id, category)
                ids.forEach {
                    if (type == PlexType.SHOW) rankNewest(client, it, tvLibraries++, newest)
                    plex.titles(client, type, it, emit)
                }
            }
        val movies = syncKind(
            p.id,
            CAT_MOVIE,
            libraries.getValue(PlexType.MOVIE),
            titles(PlexType.MOVIE, CAT_MOVIE),
            { it.first },
            vod::insertMovies,
            vod::deleteStaleMovies,
            onProgress
        ) { title, stamp -> plex.movie(client, p.id, title, position++, stamp)?.also { films += title.second.playState() } }
        syncKind(
            p.id,
            CAT_SERIES,
            libraries.getValue(PlexType.SHOW),
            titles(PlexType.SHOW, CAT_SERIES),
            { it.first },
            vod::insertSeries,
            vod::deleteStaleSeries,
            { onProgress(movies + it) }
        ) { title, stamp -> plex.series(client, p.id, title, position++, stamp, newest) }
        plex.syncPlayState(p, client, films)
    }

    /**
     * Home's recently added series: [library]'s shows with the newest episodes first, into [ranks] by series id. Shows
     * carry no date of their newest episode, only the server's order, so TV libraries take places in turn ([index]).
     * It's an extra: a library that can't say has none on the row, rather than failing the sync.
     */
    private suspend fun rankNewest(client: PlexClient, library: String, index: Int, ranks: MutableMap<Long, Int>) {
        val shows = offlineOk { client.recentlyAdded(library, PlexType.SHOW, NEWEST_SERIES) } ?: return
        shows.forEachIndexed { i, show -> show.ratingKey.toLongOrNull()?.let { ranks[it] = i * MAX_TV_LIBRARIES + index } }
    }

    /** One list (movies or series): libraries, then the included items in batches, then what the server dropped. */
    @Suppress("LongParameterList") // the two kinds differ only in these
    private suspend fun <T, E> syncKind(
        playlistId: Long,
        type: String,
        remote: List<RemoteCategory>,
        fetch: suspend (categoryId: String?, emit: suspend (T) -> Unit) -> Unit,
        categoryOf: (T) -> String,
        insert: suspend (List<E>) -> Unit,
        deleteStale: suspend (playlistId: Long, stamp: Long) -> Unit,
        onProgress: (Int) -> Unit,
        /** Null for an item to leave out. */
        toEntity: (T, stamp: Long) -> E?
    ): Int {
        categoryStore.store(playlistId, type, remote)
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

    private companion object {
        const val BATCH = 2_000
        const val DETAILS_CACHE = 20
        const val KEEP_PROGRESS = 300
        const val FIND_LIMIT = 500
    }
}

/** Where a film or episode plays from, and for one from Plex, its [plex] playback (to report to). */
data class VodStream(val url: String, val plex: PlexPlayback? = null)

/** How many series with new episodes Home's row is given. */
private const val NEWEST_SERIES = 20

/** TV libraries a server has, at most, for their places on Home's recently added row to take turns. */
private const val MAX_TV_LIBRARIES = 1_000
