package com.biskyt.myplexplayer.data.db

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RawQuery
import androidx.room.Upsert
import androidx.sqlite.db.SupportSQLiteQuery
import kotlinx.coroutines.flow.Flow

/** The most `IN (…)` arguments passed in one query (Android before 12 allows 999): longer lists go in chunks. */
const val MAX_SQL_ARGS = 900

@Dao
interface PlaylistDao {
    @Query("SELECT * FROM playlist ORDER BY id")
    fun observeAll(): Flow<List<PlaylistEntity>>

    @Query("SELECT * FROM playlist WHERE id = :id")
    suspend fun get(id: Long): PlaylistEntity?

    @Query("UPDATE playlist SET lastSyncAt = :at WHERE id = :id")
    suspend fun setLastSync(id: Long, at: Long)

    @Query("SELECT * FROM playlist ORDER BY id")
    suspend fun all(): List<PlaylistEntity>

    @Insert
    suspend fun insert(p: PlaylistEntity): Long

    @Upsert
    suspend fun upsert(p: PlaylistEntity)

    @Query("DELETE FROM playlist WHERE id = :id")
    suspend fun delete(id: Long)

    // Rows belonging to a playlist, removed together when it is deleted.
    @Query("DELETE FROM category WHERE playlistId = :pid")
    suspend fun deleteCategories(pid: Long)

    @Query("DELETE FROM channel WHERE playlistId = :pid")
    suspend fun deleteChannels(pid: Long)

    @Query("DELETE FROM favourite WHERE playlistId = :pid")
    suspend fun deleteFavourites(pid: Long)

    @Query("DELETE FROM recent WHERE playlistId = :pid")
    suspend fun deleteRecents(pid: Long)

    @Query("DELETE FROM channel_epg_mapping WHERE playlistId = :pid")
    suspend fun deleteMappings(pid: Long)

    @Query("DELETE FROM movie WHERE playlistId = :pid")
    suspend fun deleteMovies(pid: Long)

    @Query("DELETE FROM series WHERE playlistId = :pid")
    suspend fun deleteSeries(pid: Long)

    @Query("DELETE FROM watch_progress WHERE playlistId = :pid")
    suspend fun deleteProgress(pid: Long)

    @Query("DELETE FROM vod_favourite WHERE playlistId = :pid")
    suspend fun deleteVodFavourites(pid: Long)
}

@Dao
interface CategoryDao {
    // Every query is for one [CategoryEntity.type]: live, movie or series.

    @Query("SELECT * FROM category WHERE playlistId = :pid AND type = :type ORDER BY sortOrder")
    fun observe(pid: Long, type: String): Flow<List<CategoryEntity>>

    @Query("SELECT * FROM category WHERE playlistId = :pid AND type = :type ORDER BY sortOrder")
    suspend fun all(pid: Long, type: String): List<CategoryEntity>

    @Query("SELECT * FROM category WHERE playlistId = :pid AND type = :type AND included = 1 ORDER BY sortOrder")
    fun observeIncluded(pid: Long, type: String): Flow<List<CategoryEntity>>

    @Query("SELECT * FROM category WHERE playlistId = :pid AND type = :type AND included = 1")
    suspend fun included(pid: Long, type: String): List<CategoryEntity>

    @Upsert
    suspend fun upsert(items: List<CategoryEntity>)

    @Query("UPDATE category SET included = :included WHERE playlistId = :pid AND type = :type AND categoryId IN (:ids)")
    suspend fun setIncluded(pid: Long, type: String, ids: List<String>, included: Boolean)

    @Query(
        """UPDATE category SET channelCount = CASE type
             WHEN 'live' THEN (SELECT COUNT(*) FROM channel c WHERE c.playlistId = :pid AND c.categoryId = category.categoryId AND c.hidden = 0)
             WHEN 'movie' THEN (SELECT COUNT(*) FROM movie m WHERE m.playlistId = :pid AND m.categoryId = category.categoryId)
             ELSE (SELECT COUNT(*) FROM series s WHERE s.playlistId = :pid AND s.categoryId = category.categoryId) END
           WHERE playlistId = :pid AND type = :type"""
    )
    suspend fun refreshCounts(pid: Long, type: String)

    /** At most [MAX_SQL_ARGS] [ids] at a time. */
    @Query("DELETE FROM category WHERE playlistId = :pid AND type = :type AND categoryId IN (:ids)")
    suspend fun delete(pid: Long, type: String, ids: List<String>)
}

// The favourite channels of :pid (c the channel, f the favourite), and their order: the user's, else channel numbers.
private const val CHANNEL_FAVOURITES =
    "FROM channel c JOIN favourite f ON f.playlistId = c.playlistId AND f.streamId = c.streamId WHERE c.playlistId = :pid"
private const val CHANNEL_FAVOURITE_ORDER = "ORDER BY f.position, c.position, c.name"

/** A channel number starting with the typed digits (channels without a number are stored as 0 and never match). */
private const val NUMBER_MATCH = "(number > 0 AND CAST(number AS TEXT) LIKE :q || '%')"

/** Contains the typed text, taken literally: callers escape it with likeArg (so "%" and "_" aren't wildcards). */
private const val LIKE_Q = "LIKE '%' || :q || '%' ESCAPE '\\'"

/** A channel matches a typed filter by name, or by the start of its number. Shared by the lists and search. */
private const val CHANNEL_MATCH = "(name $LIKE_Q OR $NUMBER_MATCH)"

/** Lightweight row used to restore user preferences across syncs. */
data class ChannelPrefs(val streamId: Long, val hidden: Boolean, val sortOrder: Int?)

@Dao
interface ChannelDao {
    // Ordering shared by every list so zapping follows what the user sees.
    // Users can override the provider order with sortOrder.

    @Query(
        """SELECT * FROM channel WHERE playlistId = :pid AND hidden = 0
           AND (:q = '' OR $CHANNEL_MATCH)
           ORDER BY position, name"""
    )
    fun pageAll(pid: Long, q: String): PagingSource<Int, ChannelEntity>

    @Query(
        """SELECT * FROM channel WHERE playlistId = :pid AND hidden = 0 AND categoryId = :cat
           AND (:q = '' OR $CHANNEL_MATCH)
           ORDER BY position, name"""
    )
    fun pageCategory(pid: Long, cat: String, q: String): PagingSource<Int, ChannelEntity>

    @Query("SELECT c.* $CHANNEL_FAVOURITES AND (:q = '' OR $CHANNEL_MATCH) $CHANNEL_FAVOURITE_ORDER")
    fun pageFavourites(pid: Long, q: String): PagingSource<Int, ChannelEntity>

    @Query(
        """SELECT c.* FROM channel c JOIN recent r ON r.playlistId = c.playlistId AND r.streamId = c.streamId
           WHERE c.playlistId = :pid AND (:q = '' OR $CHANNEL_MATCH)
           ORDER BY r.watchedAt DESC"""
    )
    fun pageRecent(pid: Long, q: String): PagingSource<Int, ChannelEntity>

    @Query("SELECT streamId FROM channel WHERE playlistId = :pid AND hidden = 0 ORDER BY position, name")
    suspend fun idsAll(pid: Long): List<Long>

    /** Search: the same match as the list filter (name, or the number's start), first [limit] in list order. */
    @Query(
        """SELECT * FROM channel WHERE playlistId = :pid AND hidden = 0
           AND $CHANNEL_MATCH ORDER BY position, name LIMIT :limit"""
    )
    suspend fun search(pid: Long, q: String, limit: Int): List<ChannelEntity>

    @Query("SELECT * FROM channel WHERE playlistId = :pid AND hidden = 0 AND $NUMBER_MATCH ORDER BY number LIMIT :limit")
    suspend fun searchNumber(pid: Long, q: String, limit: Int): List<ChannelEntity>

    @Query("SELECT streamId FROM channel WHERE playlistId = :pid AND hidden = 0 AND categoryId = :cat ORDER BY position, name")
    suspend fun idsCategory(pid: Long, cat: String): List<Long>

    @Query("DELETE FROM channel WHERE playlistId = :pid AND categoryId = :cat")
    suspend fun deleteCategory(pid: Long, cat: String)

    @Query("SELECT c.streamId $CHANNEL_FAVOURITES $CHANNEL_FAVOURITE_ORDER")
    suspend fun idsFavourites(pid: Long): List<Long>

    @Query(
        "SELECT c.streamId FROM channel c JOIN recent r ON r.playlistId = c.playlistId AND r.streamId = c.streamId WHERE c.playlistId = :pid ORDER BY r.watchedAt DESC"
    )
    suspend fun idsRecent(pid: Long): List<Long>

    @Query("SELECT c.* $CHANNEL_FAVOURITES $CHANNEL_FAVOURITE_ORDER LIMIT :limit")
    fun observeFavourites(pid: Long, limit: Int): Flow<List<ChannelEntity>>

    @Query(
        "SELECT c.* FROM channel c JOIN recent r ON r.playlistId = c.playlistId AND r.streamId = c.streamId WHERE c.playlistId = :pid ORDER BY r.watchedAt DESC LIMIT :limit"
    )
    fun observeRecent(pid: Long, limit: Int): Flow<List<ChannelEntity>>

    @Query("SELECT * FROM channel WHERE playlistId = :pid AND streamId = :id")
    suspend fun get(pid: Long, id: Long): ChannelEntity?

    @Query("SELECT * FROM channel WHERE playlistId = :pid AND streamId IN (:ids)")
    suspend fun getAll(pid: Long, ids: List<Long>): List<ChannelEntity>

    @Query("SELECT * FROM channel WHERE playlistId = :pid AND number = :number AND hidden = 0 LIMIT 1")
    suspend fun byNumber(pid: Long, number: Int): ChannelEntity?

    @Query("SELECT COUNT(*) FROM channel WHERE playlistId = :pid AND hidden = 0")
    fun observeCount(pid: Long): Flow<Int>

    @Query("SELECT streamId, hidden, sortOrder FROM channel WHERE playlistId = :pid AND (hidden = 1 OR sortOrder IS NOT NULL)")
    suspend fun prefs(pid: Long): List<ChannelPrefs>

    @Upsert
    suspend fun upsert(items: List<ChannelEntity>)

    @Query("DELETE FROM channel WHERE playlistId = :pid AND syncStamp != :stamp")
    suspend fun deleteStale(pid: Long, stamp: Long)

    @Query("UPDATE channel SET hidden = :hidden WHERE playlistId = :pid AND streamId = :id")
    suspend fun setHidden(pid: Long, id: Long, hidden: Boolean)

    // The user's own order, as Move up and down (until 1.2.1) set it; kept, and restored from backups.
    @Query("UPDATE channel SET sortOrder = :order, position = COALESCE(:order, number) WHERE playlistId = :pid AND streamId = :id")
    suspend fun setSortOrder(pid: Long, id: Long, order: Int?)

    @Query("SELECT * FROM channel WHERE playlistId = :pid AND hidden = 1 ORDER BY name")
    fun observeHidden(pid: Long): Flow<List<ChannelEntity>>
}

@Dao
interface FavouriteDao {
    @Query("SELECT streamId FROM favourite WHERE playlistId = :pid")
    fun observeIds(pid: Long): Flow<List<Long>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun add(f: FavouriteEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun addAll(f: List<FavouriteEntity>)

    @Query("DELETE FROM favourite WHERE playlistId = :pid AND streamId = :id")
    suspend fun remove(pid: Long, id: Long)

    @Query("SELECT COALESCE(MAX(position), 0) FROM favourite WHERE playlistId = :pid")
    suspend fun lastPosition(pid: Long): Long

    @Query("UPDATE favourite SET position = :position WHERE playlistId = :pid AND streamId = :id")
    suspend fun setPosition(pid: Long, id: Long, position: Long)

    @Query("SELECT * FROM favourite WHERE playlistId = :pid")
    suspend fun all(pid: Long): List<FavouriteEntity>

    @Query("DELETE FROM favourite WHERE playlistId = :pid")
    suspend fun clear(pid: Long)

    @Upsert
    suspend fun touchRecent(r: RecentEntity)

    @Query(
        "DELETE FROM recent WHERE playlistId = :pid AND streamId NOT IN (SELECT streamId FROM recent WHERE playlistId = :pid ORDER BY watchedAt DESC LIMIT :keep)"
    )
    suspend fun trimRecent(pid: Long, keep: Int)

    @Query("DELETE FROM recent WHERE playlistId = :pid AND streamId = :id")
    suspend fun removeRecent(pid: Long, id: Long)
}

/** A programme found by search, with its channel. */
data class ProgrammeHit(
    val streamId: Long,
    val channelName: String,
    val logoUrl: String?,
    val startMs: Long,
    val stopMs: Long,
    val title: String,
    val subtitle: String?
)

/** Now/next row: a programme for a playlist channel with the channel's time shift already applied. */
data class ChannelProgramme(
    val streamId: Long,
    val startMs: Long,
    val stopMs: Long,
    val title: String,
    val subtitle: String?,
    val description: String?,
    /** Series, film or neither (a ProgrammeKind constant), and an episode's place in its series. */
    val kind: Int = 0,
    val season: Int? = null,
    val episode: Int? = null
)

@Dao
interface EpgDao {
    @Query("SELECT * FROM epg_source ORDER BY id")
    fun observeSources(): Flow<List<EpgSourceEntity>>

    @Query("UPDATE epg_source SET lastError = :error WHERE id = :id")
    suspend fun setSourceError(id: Long, error: String?)

    // Only these columns: a long import mustn't write back an older copy of the rest (a new address, say).
    @Query("UPDATE epg_source SET lastImportAt = :at, channelCount = :channelCount, lastError = :error WHERE id = :id")
    suspend fun setImportResult(id: Long, at: Long, channelCount: Int, error: String?)

    @Query("SELECT * FROM epg_source WHERE playlistId = :pid")
    suspend fun providerSource(pid: Long): EpgSourceEntity?

    @Insert
    suspend fun insertSource(s: EpgSourceEntity): Long

    @Query("DELETE FROM epg_source WHERE id = :id")
    suspend fun deleteSource(id: Long)

    @Query("DELETE FROM channel_epg_mapping WHERE sourceId = :sourceId")
    suspend fun deleteMappingsForSource(sourceId: Long)

    @Query("DELETE FROM programme WHERE sourceId = :sourceId")
    suspend fun clearProgrammes(sourceId: Long)

    @Query("DELETE FROM programme WHERE sourceId = :sourceId AND stamp != :stamp")
    suspend fun deleteOlderProgrammes(sourceId: Long, stamp: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertProgrammes(items: List<ProgrammeEntity>)

    @Query("DELETE FROM programme WHERE stopMs < :before")
    suspend fun pruneProgrammes(before: Long)

    /**
     * Search: programmes on now or later whose title matches, on visible channels that use the guide, soonest first.
     * Scans every stored programme (only guide channels in use, for a few days), so search asks for 3+ letters.
     */
    @Query(
        """SELECT m.streamId, c.name AS channelName, c.logoUrl, p.startMs + m.offsetMinutes * 60000 AS startMs,
                  p.stopMs + m.offsetMinutes * 60000 AS stopMs, p.title, p.subtitle
           FROM programme p JOIN channel_epg_mapping m ON m.sourceId = p.sourceId AND m.xmlId = p.xmlId
           JOIN channel c ON c.playlistId = m.playlistId AND c.streamId = m.streamId
           WHERE m.playlistId = :pid AND c.hidden = 0
             AND p.startMs > :from - m.offsetMinutes * 60000 - $MAX_PROGRAMME_MS
             AND p.stopMs > :from - m.offsetMinutes * 60000
             AND p.title $LIKE_Q
           ORDER BY startMs, c.position LIMIT :limit"""
    )
    suspend fun searchProgrammes(pid: Long, q: String, from: Long, limit: Int): List<ProgrammeHit>

    // Time bounds are applied to the bare startMs column (offset moved to the other side) so SQLite can range-scan the
    // (sourceId, xmlId, startMs) primary key; programmes are assumed to last at most MAX_PROGRAMME_MS.
    @Query(
        """SELECT m.streamId, p.startMs + m.offsetMinutes * 60000 AS startMs, p.stopMs + m.offsetMinutes * 60000 AS stopMs,
                  p.title, p.subtitle, CASE WHEN :details THEN p.description END AS description, p.kind, p.season, p.episode
           FROM channel_epg_mapping m JOIN programme p ON p.sourceId = m.sourceId AND p.xmlId = m.xmlId
           WHERE m.playlistId = :pid AND m.streamId IN (:ids)
             AND p.startMs > :from - m.offsetMinutes * 60000 - $MAX_PROGRAMME_MS
             AND p.startMs < :to - m.offsetMinutes * 60000
             AND p.stopMs > :from - m.offsetMinutes * 60000
           ORDER BY m.streamId, p.startMs"""
    )
    suspend fun programmes(pid: Long, ids: List<Long>, from: Long, to: Long, details: Boolean): List<ChannelProgramme>

    @Upsert
    suspend fun upsertMappings(items: List<ChannelEpgMappingEntity>)
}

/** Longest programme the time-range queries allow for (a whole-day placeholder, and then some). */
const val MAX_PROGRAMME_MS = 26 * 3_600_000L

/**
 * A poster in a movie or series grid: only what the grid shows, so paging holds as little as possible. [unwatched]:
 * a series' episodes not yet watched, for its badge.
 */
data class VodCard(
    val id: Long,
    val title: String,
    val year: Int?,
    val imageUrl: String?,
    val unwatched: Int? = null,
    /** A film's running time; a series' episodes. */
    val durationSec: Int? = null,
    val episodes: Int? = null,
    /** How far into a film, of how long, when it has been played. */
    val positionMs: Long? = null,
    val playedOfMs: Long? = null,
) {
    /** A film watched to the end, or a series with every episode watched. */
    val watched: Boolean
        get() = unwatched == 0 || playedOfMs != null && WatchProgressEntity.isFinished(positionMs ?: 0, playedOfMs)

    /** How far into a film part-watched (0 to 1), else null. */
    val partWatched: Float?
        get() {
            val played = positionMs ?: 0
            return if (watched || playedOfMs == null || playedOfMs <= 0 || played <= 0) null else played.toFloat() / playedOfMs
        }
}

private const val MOVIE_CARD = "SELECT streamId AS id, title, year, posterUrl AS imageUrl, NULL AS unwatched"
private const val SERIES_CARD = "SELECT seriesId AS id, title, year, coverUrl AS imageUrl, unwatched"

// Favourite movies and series of :pid (f is the favourite), and their order: the user's, else newest first.
private const val MOVIE_FAVOURITES =
    "FROM movie m JOIN vod_favourite f ON f.playlistId = m.playlistId AND f.kind = 'movie' AND f.itemId = m.streamId WHERE m.playlistId = :pid"
private const val SERIES_FAVOURITES =
    "FROM series s JOIN vod_favourite f ON f.playlistId = s.playlistId AND f.kind = 'series' AND f.itemId = s.seriesId WHERE s.playlistId = :pid"
private const val FAVOURITE_ORDER = "ORDER BY f.position, f.addedAt DESC"

/** Movies, series, favourites among them, and how far the user has watched. */
@Dao
interface VodDao {
    // The grids' own pages come from gridQuery (VodRepository).

    /** A grid's page, built by [gridQuery]: each watches only the tables it reads. */
    @RawQuery(observedEntities = [MovieEntity::class, VodFavouriteEntity::class, WatchProgressEntity::class])
    fun pageMovieGrid(query: SupportSQLiteQuery): PagingSource<Int, VodCard>

    @RawQuery(observedEntities = [SeriesEntity::class, VodFavouriteEntity::class])
    fun pageSeriesGrid(query: SupportSQLiteQuery): PagingSource<Int, VodCard>

    @Query("$MOVIE_CARD $MOVIE_FAVOURITES $FAVOURITE_ORDER LIMIT :limit")
    fun observeMovieFavourites(pid: Long, limit: Int): Flow<List<VodCard>>

    @Query("$MOVIE_CARD FROM movie WHERE playlistId = :pid ORDER BY added DESC LIMIT :limit")
    fun observeNewestMovies(pid: Long, limit: Int): Flow<List<VodCard>>

    @Query("$SERIES_CARD $SERIES_FAVOURITES $FAVOURITE_ORDER LIMIT :limit")
    fun observeSeriesFavourites(pid: Long, limit: Int): Flow<List<VodCard>>

    @Query("$SERIES_CARD FROM series WHERE playlistId = :pid AND newest IS NOT NULL ORDER BY newest LIMIT :limit")
    fun observeNewestSeries(pid: Long, limit: Int): Flow<List<VodCard>>

    /** The favourite movies' ids, in order. */
    @Query("SELECT m.streamId $MOVIE_FAVOURITES $FAVOURITE_ORDER")
    suspend fun idsMovieFavourites(pid: Long): List<Long>

    /** The favourite series' ids, in order. */
    @Query("SELECT s.seriesId $SERIES_FAVOURITES $FAVOURITE_ORDER")
    suspend fun idsSeriesFavourites(pid: Long): List<Long>

    @Query("SELECT COALESCE(MAX(position), 0) FROM vod_favourite WHERE playlistId = :pid AND kind = :kind")
    suspend fun lastFavouritePosition(pid: Long, kind: String): Long

    @Query("UPDATE vod_favourite SET position = :position WHERE playlistId = :pid AND kind = :kind AND itemId = :id")
    suspend fun setFavouritePosition(pid: Long, kind: String, id: Long, position: Long)

    @Query("SELECT COUNT(*) FROM movie WHERE playlistId = :pid")
    fun observeMovieCount(pid: Long): Flow<Int>

    @Query("SELECT (SELECT COUNT(*) FROM movie WHERE playlistId = :pid) + (SELECT COUNT(*) FROM series WHERE playlistId = :pid)")
    suspend fun titleCount(pid: Long): Int

    @Query("$MOVIE_CARD FROM movie WHERE playlistId = :pid AND title $LIKE_Q ORDER BY position, title LIMIT :limit")
    suspend fun searchMovies(pid: Long, q: String, limit: Int): List<VodCard>

    @Query("$SERIES_CARD FROM series WHERE playlistId = :pid AND title $LIKE_Q ORDER BY position, title LIMIT :limit")
    suspend fun searchSeries(pid: Long, q: String, limit: Int): List<VodCard>

    // Whole lists, used by tests (screens page).
    @Query("SELECT * FROM movie WHERE playlistId = :pid ORDER BY position, title")
    suspend fun moviesSnapshot(pid: Long): List<MovieEntity>

    @Query("SELECT * FROM series WHERE playlistId = :pid ORDER BY position, title")
    suspend fun seriesSnapshot(pid: Long): List<SeriesEntity>

    @Query("SELECT * FROM movie WHERE playlistId = :pid AND streamId = :id")
    suspend fun movie(pid: Long, id: Long): MovieEntity?

    @Query("SELECT * FROM series WHERE playlistId = :pid AND seriesId = :id")
    suspend fun series(pid: Long, id: Long): SeriesEntity?

    // Replace, not upsert: these rows hold no user state, and upserting tens of thousands of existing rows costs a
    // failed insert each.
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMovies(items: List<MovieEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSeries(items: List<SeriesEntity>)

    @Query("DELETE FROM movie WHERE playlistId = :pid AND categoryId = :cat")
    suspend fun deleteMovieCategory(pid: Long, cat: String)

    @Query("DELETE FROM series WHERE playlistId = :pid AND categoryId = :cat")
    suspend fun deleteSeriesCategory(pid: Long, cat: String)

    @Query("DELETE FROM movie WHERE playlistId = :pid AND streamId = :id")
    suspend fun deleteMovie(pid: Long, id: Long)

    @Query("DELETE FROM movie WHERE playlistId = :pid AND syncStamp != :stamp")
    suspend fun deleteStaleMovies(pid: Long, stamp: Long)

    @Query("DELETE FROM series WHERE playlistId = :pid AND syncStamp != :stamp")
    suspend fun deleteStaleSeries(pid: Long, stamp: Long)

    @Query("SELECT EXISTS(SELECT 1 FROM vod_favourite WHERE playlistId = :pid AND kind = :kind AND itemId = :id)")
    fun observeFavourite(pid: Long, kind: String, id: Long): Flow<Boolean>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun addFavourite(f: VodFavouriteEntity)

    @Query("SELECT itemId FROM vod_favourite WHERE playlistId = :pid AND kind = :kind")
    fun observeFavouriteIds(pid: Long, kind: String): Flow<List<Long>>

    @Query("DELETE FROM vod_favourite WHERE playlistId = :pid AND kind = :kind AND itemId = :id")
    suspend fun removeFavourite(pid: Long, kind: String, id: Long)

    @Upsert
    suspend fun saveProgress(p: WatchProgressEntity)

    @Query("SELECT * FROM watch_progress WHERE playlistId = :pid AND kind = :kind AND itemId = :id")
    suspend fun progress(pid: Long, kind: String, id: Long): WatchProgressEntity?

    @Query("SELECT * FROM watch_progress WHERE playlistId = :pid AND kind = 'episode' AND seriesId = :seriesId")
    fun observeSeriesProgress(pid: Long, seriesId: Long): Flow<List<WatchProgressEntity>>

    @Query("SELECT * FROM watch_progress WHERE playlistId = :pid AND kind = 'movie' AND itemId = :id")
    fun observeMovieProgress(pid: Long, id: Long): Flow<WatchProgressEntity?>

    /**
     * "Carry on watching", newest first: unfinished movies, and per series its most recent episode, unless that was
     * finished and the last one; only rows on it ([WatchProgressEntity.carryOnAt]). [finished] is [WatchProgressEntity.FINISHED_FRACTION].
     */
    @Query(
        """SELECT * FROM watch_progress w WHERE playlistId = :pid AND carryOnAt IS NOT NULL
           AND (NOT (durationMs > 0 AND positionMs >= durationMs * :finished) OR hasNext)
           AND (seriesId IS NULL OR carryOnAt = (SELECT MAX(x.carryOnAt) FROM watch_progress x WHERE x.playlistId = w.playlistId AND x.seriesId = w.seriesId))
           ORDER BY carryOnAt DESC LIMIT :limit"""
    )
    fun observeCarryOn(pid: Long, limit: Int, finished: Double): Flow<List<WatchProgressEntity>>

    @Query(
        "DELETE FROM watch_progress WHERE playlistId = :pid AND rowid NOT IN (SELECT rowid FROM watch_progress WHERE playlistId = :pid ORDER BY updatedAt DESC LIMIT :keep)"
    )
    suspend fun trimProgress(pid: Long, keep: Int)

    @Query("SELECT * FROM watch_progress WHERE playlistId = :pid AND kind = :kind")
    suspend fun progressOf(pid: Long, kind: String): List<WatchProgressEntity>

    @Query("SELECT * FROM watch_progress WHERE playlistId = :pid AND kind = :kind AND itemId IN (:ids)")
    suspend fun progressOf(pid: Long, kind: String, ids: List<Long>): List<WatchProgressEntity>

    @Upsert
    suspend fun saveProgress(rows: List<WatchProgressEntity>)

    @Query("DELETE FROM watch_progress WHERE playlistId = :pid AND kind = :kind AND itemId IN (:ids)")
    suspend fun deleteProgress(pid: Long, kind: String, ids: List<Long>)

    @Query("SELECT * FROM watch_progress WHERE playlistId = :pid AND pending")
    suspend fun pendingProgress(pid: Long): List<WatchProgressEntity>

    /** The server has it: unless the row has moved on since ([updatedAt] is the one reported). */
    @Query("UPDATE watch_progress SET pending = 0 WHERE playlistId = :pid AND kind = :kind AND itemId = :id AND updatedAt = :updatedAt")
    suspend fun reported(pid: Long, kind: String, id: Long, updatedAt: Long)

    /** Takes a movie off "Carry on watching", keeping how far it got. */
    @Query("UPDATE watch_progress SET carryOnAt = NULL WHERE playlistId = :pid AND kind = 'movie' AND itemId = :id")
    suspend fun leaveMovieCarryOn(pid: Long, id: Long)

    /** Takes a series (every episode) off "Carry on watching", keeping how far each got. */
    @Query("UPDATE watch_progress SET carryOnAt = NULL WHERE playlistId = :pid AND kind = 'episode' AND seriesId = :seriesId")
    suspend fun leaveSeriesCarryOn(pid: Long, seriesId: Long)

    /** A series' unwatched and all episodes, only when they changed: an update wakes every list of series, even to the same values. */
    @Query(
        """UPDATE series SET unwatched = :unwatched, episodes = :episodes WHERE playlistId = :pid AND seriesId = :seriesId
           AND (unwatched IS NOT :unwatched OR episodes IS NOT :episodes)"""
    )
    suspend fun setEpisodeCounts(pid: Long, seriesId: Long, unwatched: Int?, episodes: Int?)
}

/** Everything a backup holds (see BackupRepository), read and replaced as a whole. */
@Dao
interface BackupDao {
    @Query("SELECT * FROM category")
    suspend fun categories(): List<CategoryEntity>

    /** Only channels the user hid or moved: the next sync keeps these choices and fills in the rest. */
    @Query("SELECT * FROM channel WHERE hidden = 1 OR sortOrder IS NOT NULL")
    suspend fun channelChoices(): List<ChannelEntity>

    @Query("SELECT * FROM favourite")
    suspend fun favourites(): List<FavouriteEntity>

    @Query("SELECT * FROM vod_favourite")
    suspend fun vodFavourites(): List<VodFavouriteEntity>

    @Query("SELECT * FROM watch_progress")
    suspend fun progress(): List<WatchProgressEntity>

    @Insert
    @Suppress("LongParameterList") // one per table
    suspend fun insert(
        playlists: List<PlaylistEntity>,
        categories: List<CategoryEntity>,
        channels: List<ChannelEntity>,
        favourites: List<FavouriteEntity>,
        vodFavourites: List<VodFavouriteEntity>,
        progress: List<WatchProgressEntity>
    )
}
