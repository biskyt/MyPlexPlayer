package com.biskyt.mytvplayer.data.db

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface PlaylistDao {
    @Query("SELECT * FROM playlist ORDER BY id")
    fun observeAll(): Flow<List<PlaylistEntity>>

    @Query("SELECT * FROM playlist WHERE id = :id")
    suspend fun get(id: Long): PlaylistEntity?

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

    @Upsert
    suspend fun upsert(items: List<CategoryEntity>)

    @Query("UPDATE category SET included = :included WHERE playlistId = :pid AND categoryId IN (:ids)")
    suspend fun setIncluded(pid: Long, ids: List<String>, included: Boolean)

    @Query(
        """UPDATE category SET channelCount = CASE type
             WHEN 'live' THEN (SELECT COUNT(*) FROM channel c WHERE c.playlistId = :pid AND c.categoryId = category.categoryId AND c.hidden = 0)
             WHEN 'movie' THEN (SELECT COUNT(*) FROM movie m WHERE m.playlistId = :pid AND m.categoryId = category.categoryId)
             ELSE (SELECT COUNT(*) FROM series s WHERE s.playlistId = :pid AND s.categoryId = category.categoryId) END
           WHERE playlistId = :pid AND type = :type"""
    )
    suspend fun refreshCounts(pid: Long, type: String)

    @Query("DELETE FROM category WHERE playlistId = :pid AND type = :type AND categoryId NOT IN (:keep)")
    suspend fun deleteOthers(pid: Long, type: String, keep: List<String>)
}

/** Lightweight row used to restore user preferences across syncs. */
data class ChannelPrefs(val streamId: Long, val hidden: Boolean, val sortOrder: Int?)

@Dao
interface ChannelDao {
    // Ordering shared by every list so zapping follows what the user sees.
    // Users can override the provider order with sortOrder.

    @Query(
        """SELECT * FROM channel WHERE playlistId = :pid AND hidden = 0
           AND (:q = '' OR name LIKE '%' || :q || '%' OR CAST(number AS TEXT) LIKE :q || '%')
           ORDER BY position, name"""
    )
    fun pageAll(pid: Long, q: String): PagingSource<Int, ChannelEntity>

    @Query(
        """SELECT * FROM channel WHERE playlistId = :pid AND hidden = 0 AND categoryId = :cat
           AND (:q = '' OR name LIKE '%' || :q || '%' OR CAST(number AS TEXT) LIKE :q || '%')
           ORDER BY position, name"""
    )
    fun pageCategory(pid: Long, cat: String, q: String): PagingSource<Int, ChannelEntity>

    @Query(
        """SELECT c.* FROM channel c JOIN favourite f ON f.playlistId = c.playlistId AND f.streamId = c.streamId
           WHERE c.playlistId = :pid AND (:q = '' OR c.name LIKE '%' || :q || '%' OR CAST(c.number AS TEXT) LIKE :q || '%')
           ORDER BY c.position, c.name"""
    )
    fun pageFavourites(pid: Long, q: String): PagingSource<Int, ChannelEntity>

    @Query(
        """SELECT c.* FROM channel c JOIN recent r ON r.playlistId = c.playlistId AND r.streamId = c.streamId
           WHERE c.playlistId = :pid AND (:q = '' OR c.name LIKE '%' || :q || '%' OR CAST(c.number AS TEXT) LIKE :q || '%')
           ORDER BY r.watchedAt DESC"""
    )
    fun pageRecent(pid: Long, q: String): PagingSource<Int, ChannelEntity>

    @Query("SELECT streamId FROM channel WHERE playlistId = :pid AND hidden = 0 ORDER BY position, name")
    suspend fun idsAll(pid: Long): List<Long>

    @Query("SELECT streamId FROM channel WHERE playlistId = :pid AND hidden = 0 AND categoryId = :cat ORDER BY position, name")
    suspend fun idsCategory(pid: Long, cat: String): List<Long>

    @Query(
        """SELECT c.streamId FROM channel c JOIN favourite f ON f.playlistId = c.playlistId AND f.streamId = c.streamId
           WHERE c.playlistId = :pid ORDER BY c.position, c.name"""
    )
    suspend fun idsFavourites(pid: Long): List<Long>

    @Query(
        "SELECT c.streamId FROM channel c JOIN recent r ON r.playlistId = c.playlistId AND r.streamId = c.streamId WHERE c.playlistId = :pid ORDER BY r.watchedAt DESC"
    )
    suspend fun idsRecent(pid: Long): List<Long>

    @Query(
        """SELECT c.* FROM channel c JOIN favourite f ON f.playlistId = c.playlistId AND f.streamId = c.streamId
           WHERE c.playlistId = :pid ORDER BY c.position, c.name LIMIT :limit"""
    )
    fun observeFavourites(pid: Long, limit: Int): Flow<List<ChannelEntity>>

    @Query(
        "SELECT c.* FROM channel c JOIN recent r ON r.playlistId = c.playlistId AND r.streamId = c.streamId WHERE c.playlistId = :pid ORDER BY r.watchedAt DESC LIMIT :limit"
    )
    fun observeRecent(pid: Long, limit: Int): Flow<List<ChannelEntity>>

    @Query("SELECT * FROM channel WHERE playlistId = :pid AND streamId = :id")
    suspend fun get(pid: Long, id: Long): ChannelEntity?

    @Query("SELECT * FROM channel WHERE playlistId = :pid AND streamId IN (:ids)")
    suspend fun getMany(pid: Long, ids: List<Long>): List<ChannelEntity>

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

    @Query("UPDATE channel SET sortOrder = :order, position = COALESCE(:order, number) WHERE playlistId = :pid AND streamId = :id")
    suspend fun setSortOrder(pid: Long, id: Long, order: Int?)

    @Query("SELECT * FROM channel WHERE playlistId = :pid AND hidden = 1 ORDER BY name")
    fun observeHidden(pid: Long): Flow<List<ChannelEntity>>

    @Query("SELECT streamId, name, providerEpgId FROM channel WHERE playlistId = :pid")
    suspend fun names(pid: Long): List<ChannelName>
}

data class ChannelName(val streamId: Long, val name: String, val providerEpgId: String?)

@Dao
interface FavouriteDao {
    @Query("SELECT streamId FROM favourite WHERE playlistId = :pid")
    fun observeIds(pid: Long): Flow<List<Long>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun add(f: FavouriteEntity)

    @Query("DELETE FROM favourite WHERE playlistId = :pid AND streamId = :id")
    suspend fun remove(pid: Long, id: Long)

    @Upsert
    suspend fun touchRecent(r: RecentEntity)

    @Query(
        "DELETE FROM recent WHERE playlistId = :pid AND streamId NOT IN (SELECT streamId FROM recent WHERE playlistId = :pid ORDER BY watchedAt DESC LIMIT :keep)"
    )
    suspend fun trimRecent(pid: Long, keep: Int)
}

/** Now/next row: a programme for a playlist channel with the channel's time shift already applied. */
data class ChannelProgramme(
    val streamId: Long,
    val startMs: Long,
    val stopMs: Long,
    val title: String,
    val subtitle: String?,
    val description: String?
)

/** Mapping row joined with its guide channel name, for the matching screen. */
data class MappingInfo(
    val streamId: Long,
    val sourceId: Long?,
    val xmlId: String?,
    val manual: Boolean,
    val score: Double,
    val offsetMinutes: Int,
    val displayName: String?,
    val sourceName: String?
)

@Dao
interface EpgDao {
    @Query("SELECT * FROM epg_source ORDER BY id")
    fun observeSources(): Flow<List<EpgSourceEntity>>

    @Query("SELECT id FROM epg_source")
    suspend fun allSourceIds(): List<Long>

    @Query("SELECT * FROM epg_source WHERE enabled = 1 ORDER BY id")
    suspend fun enabledSources(): List<EpgSourceEntity>

    @Query("SELECT * FROM epg_source WHERE playlistId = :pid")
    suspend fun providerSource(pid: Long): EpgSourceEntity?

    @Insert
    suspend fun insertSource(s: EpgSourceEntity): Long

    @Upsert
    suspend fun upsertSource(s: EpgSourceEntity)

    @Query("DELETE FROM epg_source WHERE id = :id")
    suspend fun deleteSource(id: Long)

    @Query("DELETE FROM channel_epg_mapping WHERE sourceId = :sourceId")
    suspend fun deleteMappingsForSource(sourceId: Long)

    @Query("DELETE FROM epg_channel WHERE sourceId = :sourceId")
    suspend fun clearChannels(sourceId: Long)

    @Upsert
    suspend fun upsertChannels(items: List<EpgChannelEntity>)

    @Query("SELECT * FROM epg_channel")
    suspend fun allChannels(): List<EpgChannelEntity>

    @Query(
        """SELECT * FROM epg_channel WHERE (:q = '' OR displayName LIKE '%' || :q || '%' OR xmlId LIKE '%' || :q || '%')
           ORDER BY displayName LIMIT :limit"""
    )
    suspend fun searchChannels(q: String, limit: Int): List<EpgChannelEntity>

    @Query("DELETE FROM programme WHERE sourceId = :sourceId")
    suspend fun clearProgrammes(sourceId: Long)

    @Query("DELETE FROM programme WHERE sourceId = :sourceId AND stamp != :stamp")
    suspend fun deleteOlderProgrammes(sourceId: Long, stamp: Long)

    @Query("DELETE FROM programme WHERE sourceId = :sourceId AND xmlId IN (:xmlIds) AND stamp != :stamp")
    suspend fun deleteOlderProgrammes(sourceId: Long, xmlIds: List<String>, stamp: Long)

    @Query("DELETE FROM programme WHERE stopMs < :before")
    suspend fun pruneProgrammes(before: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertProgrammes(items: List<ProgrammeEntity>)

    @Query("SELECT DISTINCT xmlId FROM channel_epg_mapping WHERE sourceId = :sourceId")
    suspend fun mappedXmlIds(sourceId: Long): List<String>

    // Time bounds are applied to the bare startMs column (offset moved to the other side) so SQLite can range-scan the
    // (sourceId, xmlId, startMs) primary key; programmes are assumed to last at most MAX_PROGRAMME_MS.
    @Query(
        """SELECT m.streamId, p.startMs + m.offsetMinutes * 60000 AS startMs, p.stopMs + m.offsetMinutes * 60000 AS stopMs,
                  p.title, p.subtitle, CASE WHEN :details THEN p.description END AS description
           FROM channel_epg_mapping m JOIN programme p ON p.sourceId = m.sourceId AND p.xmlId = m.xmlId
           WHERE m.playlistId = :pid AND m.streamId IN (:ids)
             AND p.startMs > :from - m.offsetMinutes * 60000 - $MAX_PROGRAMME_MS
             AND p.startMs < :to - m.offsetMinutes * 60000
             AND p.stopMs > :from - m.offsetMinutes * 60000
           ORDER BY m.streamId, p.startMs"""
    )
    suspend fun programmes(pid: Long, ids: List<Long>, from: Long, to: Long, details: Boolean): List<ChannelProgramme>

    @Query("SELECT streamId FROM channel_epg_mapping WHERE playlistId = :pid AND manual = 1")
    suspend fun manualStreamIds(pid: Long): List<Long>

    @Query(
        """SELECT m.streamId, m.sourceId, m.xmlId, m.manual, m.score, m.offsetMinutes, e.displayName, s.name AS sourceName
           FROM channel_epg_mapping m LEFT JOIN epg_channel e ON e.sourceId = m.sourceId AND e.xmlId = m.xmlId
           LEFT JOIN epg_source s ON s.id = m.sourceId
           WHERE m.playlistId = :pid AND m.streamId IN (:ids)"""
    )
    suspend fun mappingInfo(pid: Long, ids: List<Long>): List<MappingInfo>

    @Query("SELECT * FROM channel_epg_mapping WHERE playlistId = :pid AND streamId = :id")
    suspend fun mapping(pid: Long, id: Long): ChannelEpgMappingEntity?

    @Upsert
    suspend fun upsertMappings(items: List<ChannelEpgMappingEntity>)

    @Query("DELETE FROM channel_epg_mapping WHERE playlistId = :pid AND streamId = :id")
    suspend fun deleteMapping(pid: Long, id: Long)

    @Query("DELETE FROM channel_epg_mapping WHERE manual = 0 AND playlistId = :pid")
    suspend fun clearAutoMappings(pid: Long)

    /** Channels with their current guide choice joined in, so rows update by themselves when a mapping changes. */
    @Query(
        """SELECT c.*, m.sourceId AS mapSourceId, m.manual AS mapManual, m.score AS mapScore, m.offsetMinutes AS mapOffset,
                  e.displayName AS guideName, s.name AS sourceName
           FROM channel c
           LEFT JOIN channel_epg_mapping m ON m.playlistId = c.playlistId AND m.streamId = c.streamId
           LEFT JOIN epg_channel e ON e.sourceId = m.sourceId AND e.xmlId = m.xmlId
           LEFT JOIN epg_source s ON s.id = m.sourceId
           WHERE c.playlistId = :pid AND c.hidden = 0 AND (:q = '' OR c.name LIKE '%' || :q || '%')
             AND CASE :status WHEN 1 THEN m.sourceId IS NULL WHEN 2 THEN m.manual = 0 WHEN 3 THEN m.manual = 1 ELSE 1 END
           ORDER BY c.position, c.name"""
    )
    fun pageForMatching(pid: Long, q: String, status: Int): PagingSource<Int, MatchRow>

    @Query(
        """SELECT
             (SELECT COUNT(*) FROM channel c WHERE c.playlistId = :pid AND c.hidden = 0) AS total,
             (SELECT COUNT(*) FROM channel c WHERE c.playlistId = :pid AND c.hidden = 0 AND NOT EXISTS
                (SELECT 1 FROM channel_epg_mapping m WHERE m.playlistId = c.playlistId AND m.streamId = c.streamId AND m.sourceId IS NOT NULL)) AS unmatched,
             (SELECT COUNT(*) FROM channel_epg_mapping m WHERE m.playlistId = :pid AND m.manual = 0) AS auto,
             (SELECT COUNT(*) FROM channel_epg_mapping m WHERE m.playlistId = :pid AND m.manual = 1) AS manual"""
    )
    fun observeMatchCounts(pid: Long): Flow<MatchCounts>
}

/** Longest programme the time-range queries allow for; IPTV guides often use 24-hour placeholder entries. */
const val MAX_PROGRAMME_MS = 26 * 3_600_000L

data class MatchRow(
    @androidx.room.Embedded val channel: ChannelEntity,
    val mapSourceId: Long?,
    val mapManual: Boolean?,
    val mapScore: Double?,
    val mapOffset: Int?,
    val guideName: String?,
    val sourceName: String?
)

data class MatchCounts(val total: Int, val unmatched: Int, val auto: Int, val manual: Int)

/** Movies, series, favourites among them, and how far the user has watched. */
@Dao
interface VodDao {
    // Lists filter by title as the user types; provider order unless sorted by date.

    @Query("SELECT * FROM movie WHERE playlistId = :pid AND (:q = '' OR title LIKE '%' || :q || '%') ORDER BY position, title")
    fun pageMovies(pid: Long, q: String): PagingSource<Int, MovieEntity>

    @Query(
        "SELECT * FROM movie WHERE playlistId = :pid AND categoryId = :cat AND (:q = '' OR title LIKE '%' || :q || '%') ORDER BY position, title"
    )
    fun pageMovieCategory(pid: Long, cat: String, q: String): PagingSource<Int, MovieEntity>

    @Query("SELECT * FROM movie WHERE playlistId = :pid AND (:q = '' OR title LIKE '%' || :q || '%') ORDER BY added DESC")
    fun pageMoviesByAdded(pid: Long, q: String): PagingSource<Int, MovieEntity>

    @Query(
        """SELECT m.* FROM movie m JOIN vod_favourite f ON f.playlistId = m.playlistId AND f.kind = 'movie' AND f.itemId = m.streamId
           WHERE m.playlistId = :pid AND (:q = '' OR m.title LIKE '%' || :q || '%') ORDER BY f.addedAt DESC"""
    )
    fun pageMovieFavourites(pid: Long, q: String): PagingSource<Int, MovieEntity>

    @Query("SELECT * FROM series WHERE playlistId = :pid AND (:q = '' OR title LIKE '%' || :q || '%') ORDER BY position, title")
    fun pageSeries(pid: Long, q: String): PagingSource<Int, SeriesEntity>

    @Query(
        "SELECT * FROM series WHERE playlistId = :pid AND categoryId = :cat AND (:q = '' OR title LIKE '%' || :q || '%') ORDER BY position, title"
    )
    fun pageSeriesCategory(pid: Long, cat: String, q: String): PagingSource<Int, SeriesEntity>

    @Query("SELECT * FROM series WHERE playlistId = :pid AND (:q = '' OR title LIKE '%' || :q || '%') ORDER BY lastModified DESC")
    fun pageSeriesByUpdated(pid: Long, q: String): PagingSource<Int, SeriesEntity>

    @Query(
        """SELECT s.* FROM series s JOIN vod_favourite f ON f.playlistId = s.playlistId AND f.kind = 'series' AND f.itemId = s.seriesId
           WHERE s.playlistId = :pid AND (:q = '' OR s.title LIKE '%' || :q || '%') ORDER BY f.addedAt DESC"""
    )
    fun pageSeriesFavourites(pid: Long, q: String): PagingSource<Int, SeriesEntity>

    @Query("SELECT COUNT(*) FROM movie WHERE playlistId = :pid")
    fun observeMovieCount(pid: Long): Flow<Int>

    @Query("SELECT COUNT(*) FROM series WHERE playlistId = :pid")
    fun observeSeriesCount(pid: Long): Flow<Int>

    // Whole lists, for tests and small checks only (screens page).
    @Query("SELECT * FROM movie WHERE playlistId = :pid ORDER BY position, title")
    suspend fun moviesSnapshot(pid: Long): List<MovieEntity>

    @Query("SELECT * FROM series WHERE playlistId = :pid ORDER BY position, title")
    suspend fun seriesSnapshot(pid: Long): List<SeriesEntity>

    @Query("SELECT * FROM movie WHERE playlistId = :pid AND streamId = :id")
    suspend fun movie(pid: Long, id: Long): MovieEntity?

    @Query("SELECT * FROM series WHERE playlistId = :pid AND seriesId = :id")
    suspend fun series(pid: Long, id: Long): SeriesEntity?

    @Upsert
    suspend fun upsertMovies(items: List<MovieEntity>)

    @Upsert
    suspend fun upsertSeries(items: List<SeriesEntity>)

    @Query("DELETE FROM movie WHERE playlistId = :pid AND syncStamp != :stamp")
    suspend fun deleteStaleMovies(pid: Long, stamp: Long)

    @Query("DELETE FROM series WHERE playlistId = :pid AND syncStamp != :stamp")
    suspend fun deleteStaleSeries(pid: Long, stamp: Long)

    @Query("SELECT itemId FROM vod_favourite WHERE playlistId = :pid AND kind = :kind")
    fun observeFavouriteIds(pid: Long, kind: String): Flow<List<Long>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun addFavourite(f: VodFavouriteEntity)

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
     * "Carry on watching": unfinished movies and, per series, only its most recent episode, newest first.
     * The finished share matches [WatchProgressEntity.FINISHED_FRACTION].
     */
    @Query(
        """SELECT * FROM watch_progress w WHERE playlistId = :pid
           AND NOT (durationMs > 0 AND positionMs >= durationMs * 0.92)
           AND (seriesId IS NULL OR updatedAt = (SELECT MAX(x.updatedAt) FROM watch_progress x WHERE x.playlistId = w.playlistId AND x.seriesId = w.seriesId))
           ORDER BY updatedAt DESC LIMIT :limit"""
    )
    fun observeCarryOn(pid: Long, limit: Int): Flow<List<WatchProgressEntity>>

    @Query(
        "DELETE FROM watch_progress WHERE playlistId = :pid AND updatedAt < (SELECT updatedAt FROM watch_progress WHERE playlistId = :pid ORDER BY updatedAt DESC LIMIT 1 OFFSET :keep)"
    )
    suspend fun trimProgress(pid: Long, keep: Int)
}
