package com.biskyt.myplexplayer.data

import android.util.LruCache
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.room.withTransaction
import androidx.sqlite.db.SimpleSQLiteQuery
import com.biskyt.myplexplayer.R
import com.biskyt.myplexplayer.core.plex.PlexAutoDelete
import com.biskyt.myplexplayer.core.plex.PlexClient
import com.biskyt.myplexplayer.core.plex.PlexException
import com.biskyt.myplexplayer.core.plex.PlexItem
import com.biskyt.myplexplayer.core.plex.PlexPlayState
import com.biskyt.myplexplayer.core.plex.PlexType
import com.biskyt.myplexplayer.core.vod.CastMember
import com.biskyt.myplexplayer.core.vod.EpisodeInfo
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
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/** What a grid of movies or series shows: one [category] (null: all), which of them ([show]), in what order ([sort]). */
data class VodView(val category: String? = null, val show: VodShow = VodShow.ALL, val sort: VodSort = VodSort.TITLE)

enum class VodShow { ALL, UNWATCHED, FAVOURITES }

/** Cards on Carry on watching: as many as the server's Continue Watching is asked for. */
const val CARRY_ON_LIMIT = 20

/** [NEWEST]: films by date added, series by their newest episode. Favourites keep the user's own order. */
enum class VodSort { NEWEST, TITLE, YEAR }

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
    private val notices: Notices,
) {
    private val vod = db.vod()
    private val categories = db.categories()

    // Not replayed: only grids open as a film is deleted need to hear of it (the buffer is so the emit never waits).
    private val deleted = MutableSharedFlow<Pair<Long, Long>>(extraBufferCapacity = 8)

    /** Films deleted from here (source, film), as they go: a grid moves the remote on once its list catches up. */
    val deletedMovies: SharedFlow<Pair<Long, Long>> = deleted

    // maxSize drops pages scrolled far away, so browsing tens of thousands of titles doesn't keep them all.
    private val paging = PagingConfig(pageSize = 48, prefetchDistance = 24, initialLoadSize = 72, enablePlaceholders = true, maxSize = 300)

    fun pageCards(type: String, playlistId: Long, view: VodView): Flow<PagingData<VodCard>> = Pager(paging) {
        gridQuery(type, playlistId, view).let { if (type == CAT_MOVIE) vod.pageMovieGrid(it) else vod.pageSeriesGrid(it) }
    }.flow

    /** Whether the playlist has any movies or series loaded. */
    suspend fun hasTitles(playlistId: Long) = vod.titleCount(playlistId) > 0

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

    fun observeCarryOn(playlistId: Long, limit: Int = CARRY_ON_LIMIT) =
        vod.observeCarryOn(playlistId, limit, WatchProgressEntity.FINISHED_FRACTION)

    /**
     * Where playback of [stream] has got to ([row]), as it plays ([playing]) or pauses, or when it [stopped]. From
     * Plex it's reported to the server too, the row marked pending until it gets there. Otherwise a stop also drops
     * the oldest rows beyond the cap (Plex's mirror the server's, so aren't capped).
     */
    suspend fun saveProgress(row: WatchProgressEntity, stream: VodStream, playing: Boolean, stopped: Boolean, next: EpisodeInfo? = null) {
        val plex = stream.plex ?: return saveProgress(row, trim = stopped, next = next)
        val state = when {
            stopped -> PlexPlayState.STOPPED
            playing -> PlexPlayState.PLAYING
            else -> PlexPlayState.PAUSED
        }
        // With the watched one, in its save (a save dropped as older writes neither): the next one's card is kept here
        // only, the server putting it on its Continue Watching by itself.
        val up = next?.takeIf { row.finished }
        val sent = plex.report(state, row.positionMs, row.updatedAt) {
            db.withTransaction {
                vod.saveProgress(row.copy(pending = true))
                up?.let { upNext(row, it) }
            }
        }
        if (sent != null && sent != Sent.NOT_YET) vod.reported(row.playlistId, row.kind, row.itemId, row.updatedAt)
    }

    /**
     * Saves progress; [trim] (when playback stops) also drops the oldest rows beyond the cap, in the same write. A
     * finished episode with [next] after it puts that one on "Carry on watching" in its place ([upNext]).
     */
    suspend fun saveProgress(p: WatchProgressEntity, trim: Boolean = false, next: EpisodeInfo? = null) {
        val up = next?.takeIf { p.finished }
        if (!trim && up == null) return vod.saveProgress(p)
        db.withTransaction {
            vod.saveProgress(p)
            if (up != null) upNext(p, up)
            if (trim) vod.trimProgress(p.playlistId, KEEP_PROGRESS)
        }
    }

    /**
     * After [watched], its series' card is [next]: placed just after it on "Carry on watching", not started (or, started
     * already, where it got). Each later save of [watched] (the credits play on) places it again, so it stays after it.
     * Only its place moves: its [WatchProgressEntity.updatedAt] stays when it was last watched.
     */
    private suspend fun upNext(watched: WatchProgressEntity, next: EpisodeInfo) {
        val saved = vod.progress(watched.playlistId, VOD_EPISODE, next.id)
        if (saved?.finished == true) return
        val row = saved ?: watched.copy(
            itemId = next.id,
            positionMs = 0,
            durationMs = next.runningMs ?: 0,
            season = next.season,
            episode = next.number,
            episodeTitle = next.title.ifBlank { null },
            hasNext = false,
            pending = false
        )
        vod.saveProgress(listOf(row.copy(carryOnAt = watched.updatedAt + 1)))
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

    // --- deleting ---

    /** Whether a film or episode of source [playlistId] can be deleted: the server allows it. */
    suspend fun canDelete(playlistId: Long): Boolean = playlists.get(playlistId)?.let { plex.allowsDeletion(it) } ?: false

    /**
     * Deletes film [id] on its server, its file included, then here: its row, progress and favourite. False, with a
     * notice, if the server couldn't be reached or refused (then nothing changed).
     */
    suspend fun deleteMovie(playlistId: Long, id: Long): Boolean {
        deleteOnServer(playlistId, id) ?: return false
        db.withTransaction {
            vod.deleteMovie(playlistId, id)
            vod.removeFavourite(playlistId, CAT_MOVIE, id)
            vod.deleteProgress(playlistId, VOD_MOVIE, listOf(id))
        }
        movieDetails.remove("$playlistId:$id")
        deleted.tryEmit(playlistId to id)
        return true
    }

    /** Deletes episode [id] of [seriesId] on its server, as [deleteMovie]; its page, fetched again, brings the badge. */
    suspend fun deleteEpisode(playlistId: Long, seriesId: Long, id: Long): Boolean {
        deleteOnServer(playlistId, id) ?: return false
        vod.deleteProgress(playlistId, VOD_EPISODE, listOf(id))
        seriesDetails.remove("$playlistId:$seriesId")
        return true
    }

    /** Deletes [id] on its source's server: the source, or null (with a notice) when it couldn't. */
    private suspend fun deleteOnServer(playlistId: Long, id: Long): PlaylistEntity? {
        val source = playlists.get(playlistId) ?: return null
        return source.takeIf { notices.inPlex(R.string.vod_not_deleted) { plex.delete(source, id) } }
    }

    /** Each series' "Delete episodes after playing" as last read or set, so a menu opened again asks for nothing. */
    private val autoDelete = LruCache<String, PlexAutoDelete>(DETAILS_CACHE)

    /** A series' "Delete episodes after playing" on its server; null when it can't be read. */
    suspend fun autoDeleteWatched(playlistId: Long, seriesId: Long): PlexAutoDelete? {
        autoDelete.get("$playlistId:$seriesId")?.let { return it }
        val read = playlists.get(playlistId)?.let { offlineOk { plex.autoDeleteWatched(it, seriesId) } } ?: return null
        return read.also { autoDelete.put("$playlistId:$seriesId", it) }
    }

    /** A title's cast, read from the server (a series' for an episode with none of its own); empty when unknown. */
    suspend fun cast(playlistId: Long, id: Long): List<CastMember> =
        playlists.get(playlistId)?.let { offlineOk { plex.cast(it, id) } }.orEmpty()

    /** Sets it; false, with a notice, if the server couldn't be reached or refused. */
    suspend fun setAutoDeleteWatched(playlistId: Long, seriesId: Long, policy: PlexAutoDelete): Boolean {
        val source = playlists.get(playlistId) ?: return false
        return notices.inPlex(R.string.vod_auto_delete_not_saved) { plex.setAutoDeleteWatched(source, seriesId, policy) }
            .also { if (it) autoDelete.put("$playlistId:$seriesId", policy) }
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
            plex.forgetDeletion(p.id)
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

/**
 * The query behind a grid of [type] (CAT_MOVIE or CAT_SERIES): every choice of [VodView] in one, its values bound as
 * arguments. A film carries how far it was played; a series, how many episodes are unwatched.
 */
internal fun gridQuery(type: String, playlistId: Long, view: VodView): SimpleSQLiteQuery {
    val movies = type == CAT_MOVIE
    val t = if (movies) "m" else "s"
    val args = mutableListOf<Any>()
    val sql = StringBuilder(
        if (movies) {
            "SELECT m.streamId AS id, m.title, m.year, m.posterUrl AS imageUrl, NULL AS unwatched, m.durationSec, NULL AS episodes, " +
                "p.positionMs, p.durationMs AS playedOfMs FROM movie m LEFT JOIN watch_progress p ON p.playlistId = m.playlistId " +
                "AND p.kind = '$VOD_MOVIE' AND p.itemId = m.streamId"
        } else {
            "SELECT s.seriesId AS id, s.title, s.year, s.coverUrl AS imageUrl, s.unwatched, NULL AS durationSec, s.episodes, " +
                "NULL AS positionMs, NULL AS playedOfMs FROM series s"
        }
    )
    if (view.show == VodShow.FAVOURITES) {
        sql.append(
            " JOIN vod_favourite f ON f.playlistId = $t.playlistId AND f.kind = ? AND f.itemId = $t.${if (movies) "streamId" else "seriesId"}"
        )
        args += type
    }
    sql.append(" WHERE $t.playlistId = ?")
    args += playlistId
    view.category?.let {
        sql.append(" AND $t.categoryId = ?")
        args += it
    }
    if (view.show == VodShow.UNWATCHED) {
        sql.append(
            if (movies) {
                " AND (p.durationMs IS NULL OR NOT (p.durationMs > 0 AND p.positionMs >= p.durationMs * ${WatchProgressEntity.FINISHED_FRACTION}))"
            } else {
                " AND (s.unwatched IS NULL OR s.unwatched > 0)"
            }
        )
    }
    sql.append(
        when {
            view.show == VodShow.FAVOURITES -> " ORDER BY f.position, f.addedAt DESC"
            view.sort == VodSort.TITLE -> " ORDER BY ${sortTitle("$t.title")}, $t.position"
            view.sort == VodSort.YEAR -> " ORDER BY $t.year IS NULL, $t.year DESC, $t.position"
            movies -> " ORDER BY m.added DESC"
            else -> " ORDER BY s.lastModified DESC"
        }
    )
    return SimpleSQLiteQuery(sql.toString(), args.toTypedArray())
}

/** An episode's running time, if known. */
private val EpisodeInfo.runningMs: Long? get() = durationSec?.takeIf { it > 0 }?.times(1_000L)

/**
 * SQL for a title as it sorts, in any case: without a leading "The", "A" or "An", as Plex sorts it ("The Mentalist" under
 * M). The title itself is shown as it is.
 */
private fun sortTitle(column: String): String {
    val title = "ltrim($column)"
    return "ltrim(CASE WHEN $title LIKE 'The %' THEN substr($title, 5) WHEN $title LIKE 'An %' THEN substr($title, 4) " +
        "WHEN $title LIKE 'A %' THEN substr($title, 3) ELSE $title END) COLLATE NOCASE"
}
