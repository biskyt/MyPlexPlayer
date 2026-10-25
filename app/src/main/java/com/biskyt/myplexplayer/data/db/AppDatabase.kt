package com.biskyt.myplexplayer.data.db

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.AutoMigrationSpec
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    version = 11,
    exportSchema = true,
    // 2: M3U playlists (playlist.kind; channel.streamUrl/userAgent/referrer).
    // 3: movies and series (category.type; movie, series, watch_progress, vod_favourite).
    // 4: what a programme is (programme.kind, season, episode).
    // 5: an index for catch-up channels.
    // 6: M3U movies and series (playlist.moviesUrl/seriesUrl/vodFeedHash; movie.streamUrl; episode) and catch-up
    //    (channel.catchupType/catchupSource).
    // 7: the user's order of favourites (favourite.position, vod_favourite.position).
    // 8: Plex servers (playlist.machineId).
    // 9: no M3U playlists (0.2.2): any source of a kind the app no longer has is removed, with all it had.
    // 10: play state from Plex (series.unwatched, watch_progress.carryOnAt and pending).
    // 11: Home's recently added series (series.newest).
    // Unused since M3U went (0.2.2), to drop with Xtream: playlist.moviesUrl/seriesUrl/vodFeedHash, movie.streamUrl,
    // the episode table, channel.streamUrl/userAgent/referrer/catchupType/catchupSource.
    // Unused since Catch-up went (DVR replaces it), also to drop with Xtream: channel.archiveDays and its index.
    autoMigrations = [
        AutoMigration(from = 1, to = 2),
        AutoMigration(from = 2, to = 3),
        AutoMigration(from = 3, to = 4),
        AutoMigration(from = 4, to = 5),
        AutoMigration(from = 5, to = 6),
        AutoMigration(from = 6, to = 7),
        AutoMigration(from = 7, to = 8),
        AutoMigration(from = 8, to = 9, spec = AppDatabase.RetiredSources::class),
        AutoMigration(from = 9, to = 10, spec = AppDatabase.CarryOnOrder::class),
        AutoMigration(from = 10, to = 11)
    ],
    entities = [
        PlaylistEntity::class, CategoryEntity::class, ChannelEntity::class, FavouriteEntity::class, RecentEntity::class,
        EpgSourceEntity::class, EpgChannelEntity::class, ProgrammeEntity::class, ChannelEpgMappingEntity::class,
        MovieEntity::class, SeriesEntity::class, EpisodeEntity::class, WatchProgressEntity::class, VodFavouriteEntity::class
    ]
)
abstract class AppDatabase : RoomDatabase() {
    /**
     * Removes sources of kinds the app no longer has (M3U playlists, offered until 0.2.1), with everything they had,
     * once and before anything reads them. Their guide's file is left for the daily clean-up (EpgRepository's
     * deleteOrphanFiles). A backup holding one leaves it out when restored (see BackupRepository).
     */
    class RetiredSources : AutoMigrationSpec {
        override fun onPostMigrate(db: SupportSQLiteDatabase) {
            val retired = "SELECT id FROM playlist WHERE kind NOT IN (${SOURCE_KINDS.joinToString { "'$it'" }})"
            val guides = "SELECT id FROM epg_source WHERE playlistId IN ($retired)"
            listOf("programme", "epg_channel").forEach { db.execSQL("DELETE FROM $it WHERE sourceId IN ($guides)") }
            db.execSQL("DELETE FROM channel_epg_mapping WHERE sourceId IN ($guides)")
            db.execSQL("DELETE FROM epg_source WHERE playlistId IN ($retired)")
            PER_SOURCE.forEach { db.execSQL("DELETE FROM $it WHERE playlistId IN ($retired)") }
            db.execSQL("DELETE FROM playlist WHERE id IN ($retired)")
        }

        private companion object {
            val PER_SOURCE = listOf(
                "category", "channel", "favourite", "recent", "channel_epg_mapping", "movie", "series", "episode",
                "watch_progress", "vod_favourite"
            )
        }
    }

    /** Every row already saved was on "Carry on watching", in the order it was last played. */
    class CarryOnOrder : AutoMigrationSpec {
        override fun onPostMigrate(db: SupportSQLiteDatabase) = db.execSQL("UPDATE watch_progress SET carryOnAt = updatedAt")
    }

    abstract fun playlists(): PlaylistDao
    abstract fun categories(): CategoryDao
    abstract fun channels(): ChannelDao
    abstract fun favourites(): FavouriteDao
    abstract fun epg(): EpgDao
    abstract fun vod(): VodDao
    abstract fun backup(): BackupDao

    /**
     * Gives space back to the system after large deletes (old programmes, removed playlists). SQLite never shrinks the
     * file on its own; this only runs VACUUM when at least a quarter of the file is free pages, so it is usually a no-op.
     */
    fun compactIfWorthwhile() {
        val db = openHelper.writableDatabase
        val pages = db.query("PRAGMA page_count").use { if (it.moveToFirst()) it.getLong(0) else 0L }
        val free = db.query("PRAGMA freelist_count").use { if (it.moveToFirst()) it.getLong(0) else 0L }
        if (pages > 0 && free * 4 >= pages) db.execSQL("VACUUM")
    }

    /**
     * Empties every table in the current transaction (unlike clearAllTables, which can't run inside one). Room's
     * change tracking uses triggers, so open lists still update.
     */
    fun clearEveryTable() {
        val db = openHelper.writableDatabase
        val tables = db.query(
            "SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' " +
                "AND name NOT IN ('android_metadata', 'room_master_table')"
        ).use { c ->
            List(c.count) {
                c.moveToNext()
                c.getString(0)
            }
        }
        tables.forEach { db.execSQL("DELETE FROM `$it`") }
    }

    companion object {
        fun create(context: Context): AppDatabase = Room.databaseBuilder(context, AppDatabase::class.java, "myplexplayer.db")
            // Write-ahead logging lets the list read while a background sync writes.
            .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
            .build()
    }
}
