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
}

@Dao
interface CategoryDao {
    @Query("SELECT * FROM category WHERE playlistId = :pid ORDER BY sortOrder")
    fun observe(pid: Long): Flow<List<CategoryEntity>>

    @Query("SELECT * FROM category WHERE playlistId = :pid ORDER BY sortOrder")
    suspend fun all(pid: Long): List<CategoryEntity>

    @Query("SELECT * FROM category WHERE playlistId = :pid AND included = 1 ORDER BY sortOrder")
    fun observeIncluded(pid: Long): Flow<List<CategoryEntity>>

    @Upsert
    suspend fun upsert(items: List<CategoryEntity>)

    @Query("UPDATE category SET included = :included WHERE playlistId = :pid AND categoryId IN (:ids)")
    suspend fun setIncluded(pid: Long, ids: List<String>, included: Boolean)

    @Query("UPDATE category SET sortOrder = :order WHERE playlistId = :pid AND categoryId = :id")
    suspend fun setOrder(pid: Long, id: String, order: Int)

    @Query("UPDATE category SET channelCount = (SELECT COUNT(*) FROM channel c WHERE c.playlistId = :pid AND c.categoryId = category.categoryId AND c.hidden = 0) WHERE playlistId = :pid")
    suspend fun refreshCounts(pid: Long)

    @Query("DELETE FROM category WHERE playlistId = :pid AND categoryId NOT IN (:keep)")
    suspend fun deleteOthers(pid: Long, keep: List<String>)
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
           ORDER BY COALESCE(sortOrder, number), name""",
    )
    fun pageAll(pid: Long, q: String): PagingSource<Int, ChannelEntity>

    @Query(
        """SELECT * FROM channel WHERE playlistId = :pid AND hidden = 0 AND categoryId = :cat
           AND (:q = '' OR name LIKE '%' || :q || '%' OR CAST(number AS TEXT) LIKE :q || '%')
           ORDER BY COALESCE(sortOrder, number), name""",
    )
    fun pageCategory(pid: Long, cat: String, q: String): PagingSource<Int, ChannelEntity>

    @Query(
        """SELECT c.* FROM channel c JOIN favourite f ON f.playlistId = c.playlistId AND f.streamId = c.streamId
           WHERE c.playlistId = :pid AND (:q = '' OR c.name LIKE '%' || :q || '%' OR CAST(c.number AS TEXT) LIKE :q || '%')
           ORDER BY COALESCE(c.sortOrder, c.number), c.name""",
    )
    fun pageFavourites(pid: Long, q: String): PagingSource<Int, ChannelEntity>

    @Query(
        """SELECT c.* FROM channel c JOIN recent r ON r.playlistId = c.playlistId AND r.streamId = c.streamId
           WHERE c.playlistId = :pid AND (:q = '' OR c.name LIKE '%' || :q || '%' OR CAST(c.number AS TEXT) LIKE :q || '%')
           ORDER BY r.watchedAt DESC""",
    )
    fun pageRecent(pid: Long, q: String): PagingSource<Int, ChannelEntity>

    @Query("SELECT streamId FROM channel WHERE playlistId = :pid AND hidden = 0 ORDER BY COALESCE(sortOrder, number), name")
    suspend fun idsAll(pid: Long): List<Long>

    @Query("SELECT streamId FROM channel WHERE playlistId = :pid AND hidden = 0 AND categoryId = :cat ORDER BY COALESCE(sortOrder, number), name")
    suspend fun idsCategory(pid: Long, cat: String): List<Long>

    @Query(
        """SELECT c.streamId FROM channel c JOIN favourite f ON f.playlistId = c.playlistId AND f.streamId = c.streamId
           WHERE c.playlistId = :pid ORDER BY COALESCE(c.sortOrder, c.number), c.name""",
    )
    suspend fun idsFavourites(pid: Long): List<Long>

    @Query("SELECT c.streamId FROM channel c JOIN recent r ON r.playlistId = c.playlistId AND r.streamId = c.streamId WHERE c.playlistId = :pid ORDER BY r.watchedAt DESC")
    suspend fun idsRecent(pid: Long): List<Long>

    @Query(
        """SELECT c.* FROM channel c JOIN favourite f ON f.playlistId = c.playlistId AND f.streamId = c.streamId
           WHERE c.playlistId = :pid ORDER BY COALESCE(c.sortOrder, c.number), c.name LIMIT :limit""",
    )
    fun observeFavourites(pid: Long, limit: Int): Flow<List<ChannelEntity>>

    @Query("SELECT c.* FROM channel c JOIN recent r ON r.playlistId = c.playlistId AND r.streamId = c.streamId WHERE c.playlistId = :pid ORDER BY r.watchedAt DESC LIMIT :limit")
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

    @Query("UPDATE channel SET sortOrder = :order WHERE playlistId = :pid AND streamId = :id")
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

    @Query("DELETE FROM recent WHERE playlistId = :pid AND streamId NOT IN (SELECT streamId FROM recent WHERE playlistId = :pid ORDER BY watchedAt DESC LIMIT :keep)")
    suspend fun trimRecent(pid: Long, keep: Int)
}

/** Now/next row: a programme for a playlist channel with the channel's time shift already applied. */
data class ChannelProgramme(
    val streamId: Long,
    val startMs: Long,
    val stopMs: Long,
    val title: String,
    val subtitle: String?,
    val description: String?,
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
    val sourceName: String?,
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
           ORDER BY displayName LIMIT :limit""",
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

    @Query(
        """SELECT m.streamId, p.startMs + m.offsetMinutes * 60000 AS startMs, p.stopMs + m.offsetMinutes * 60000 AS stopMs,
                  p.title, p.subtitle, p.description
           FROM channel_epg_mapping m JOIN programme p ON p.sourceId = m.sourceId AND p.xmlId = m.xmlId
           WHERE m.playlistId = :pid AND m.streamId IN (:ids)
             AND p.stopMs + m.offsetMinutes * 60000 > :from AND p.startMs + m.offsetMinutes * 60000 < :to
           ORDER BY m.streamId, p.startMs""",
    )
    suspend fun programmes(pid: Long, ids: List<Long>, from: Long, to: Long): List<ChannelProgramme>

    @Query("SELECT * FROM channel_epg_mapping WHERE playlistId = :pid")
    suspend fun mappings(pid: Long): List<ChannelEpgMappingEntity>

    @Query(
        """SELECT m.streamId, m.sourceId, m.xmlId, m.manual, m.score, m.offsetMinutes, e.displayName, s.name AS sourceName
           FROM channel_epg_mapping m LEFT JOIN epg_channel e ON e.sourceId = m.sourceId AND e.xmlId = m.xmlId
           LEFT JOIN epg_source s ON s.id = m.sourceId
           WHERE m.playlistId = :pid AND m.streamId IN (:ids)""",
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

    @Query(
        """SELECT * FROM channel WHERE playlistId = :pid AND hidden = 0
           AND (:q = '' OR name LIKE '%' || :q || '%')
           AND CASE :status
               WHEN 1 THEN NOT EXISTS (SELECT 1 FROM channel_epg_mapping m WHERE m.playlistId = channel.playlistId AND m.streamId = channel.streamId AND m.sourceId IS NOT NULL)
               WHEN 2 THEN EXISTS (SELECT 1 FROM channel_epg_mapping m WHERE m.playlistId = channel.playlistId AND m.streamId = channel.streamId AND m.manual = 0)
               WHEN 3 THEN EXISTS (SELECT 1 FROM channel_epg_mapping m WHERE m.playlistId = channel.playlistId AND m.streamId = channel.streamId AND m.manual = 1)
               ELSE 1 END
           ORDER BY COALESCE(sortOrder, number), name""",
    )
    fun pageForMatching(pid: Long, q: String, status: Int): PagingSource<Int, ChannelEntity>

    @Query(
        """SELECT
             (SELECT COUNT(*) FROM channel c WHERE c.playlistId = :pid AND c.hidden = 0) AS total,
             (SELECT COUNT(*) FROM channel c WHERE c.playlistId = :pid AND c.hidden = 0 AND NOT EXISTS
                (SELECT 1 FROM channel_epg_mapping m WHERE m.playlistId = c.playlistId AND m.streamId = c.streamId AND m.sourceId IS NOT NULL)) AS unmatched,
             (SELECT COUNT(*) FROM channel_epg_mapping m WHERE m.playlistId = :pid AND m.manual = 0) AS auto,
             (SELECT COUNT(*) FROM channel_epg_mapping m WHERE m.playlistId = :pid AND m.manual = 1) AS manual""",
    )
    fun observeMatchCounts(pid: Long): Flow<MatchCounts>
}

data class MatchCounts(val total: Int, val unmatched: Int, val auto: Int, val manual: Int)
