package com.biskyt.myplexplayer.data.db

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.DeleteColumn
import androidx.room.DeleteTable
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.AutoMigrationSpec
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    version = 16,
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
    // 12: Plex only (0.6.0): Xtream accounts and added guides removed, with what only they, M3U or Catch-up used.
    // 13: the channels that were favourites in Plex at the last sync (plex_favourite_seen, 0.7.0).
    // 14: that table goes: favourite channels are Plex's, mirrored at each sync (0.9.0).
    // 16: a guide programme's Plex ids (programme.guid, showGuid), for recording (0.17.0).
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
        AutoMigration(from = 10, to = 11),
        AutoMigration(from = 11, to = 12, spec = AppDatabase.PlexOnly::class),
        AutoMigration(from = 12, to = 13),
        AutoMigration(from = 13, to = 14, spec = AppDatabase.PlexFavourites::class),
        AutoMigration(from = 14, to = 15),
        AutoMigration(from = 15, to = 16, spec = AppDatabase.RecordingIds::class)
    ],
    entities = [
        PlaylistEntity::class, CategoryEntity::class, ChannelEntity::class, FavouriteEntity::class, RecentEntity::class,
        EpgSourceEntity::class, ProgrammeEntity::class, ChannelEpgMappingEntity::class, MovieEntity::class, SeriesEntity::class,
        WatchProgressEntity::class, VodFavouriteEntity::class
    ]
)
abstract class AppDatabase : RoomDatabase() {
    /** Favourite channels are Plex's (0.9.0): what Plex had at the last sync, kept to merge with it, isn't needed. */
    @DeleteTable(tableName = "plex_favourite_seen")
    class PlexFavourites : AutoMigrationSpec

    /** Removes M3U playlists (offered until 0.2.1), with everything they had (see [removeRetired]). */
    class RetiredSources : AutoMigrationSpec {
        override fun onPostMigrate(db: SupportSQLiteDatabase) = removeRetired(db, keep = listOf(KIND_PLEX, "xtream"), before12 = true)
    }

    /**
     * Plex only (0.6.0): Xtream accounts go, with everything they had, and so do the guides added by hand (with their
     * programmes and the channels matched to them). So do what only they, M3U playlists or Catch-up used: the list of
     * each guide's channels, M3U episodes, and channels' and films' own addresses, headers and catch-up.
     */
    @DeleteTable.Entries(DeleteTable(tableName = "epg_channel"), DeleteTable(tableName = "episode"))
    @DeleteColumn.Entries(
        DeleteColumn(tableName = "playlist", columnName = "username"),
        DeleteColumn(tableName = "playlist", columnName = "expiresAt"),
        DeleteColumn(tableName = "playlist", columnName = "maxConnections"),
        DeleteColumn(tableName = "playlist", columnName = "moviesUrl"),
        DeleteColumn(tableName = "playlist", columnName = "seriesUrl"),
        DeleteColumn(tableName = "playlist", columnName = "vodFeedHash"),
        DeleteColumn(tableName = "channel", columnName = "archiveDays"),
        DeleteColumn(tableName = "channel", columnName = "streamUrl"),
        DeleteColumn(tableName = "channel", columnName = "userAgent"),
        DeleteColumn(tableName = "channel", columnName = "referrer"),
        DeleteColumn(tableName = "channel", columnName = "catchupType"),
        DeleteColumn(tableName = "channel", columnName = "catchupSource"),
        DeleteColumn(tableName = "movie", columnName = "streamUrl")
    )
    class PlexOnly : AutoMigrationSpec {
        override fun onPostMigrate(db: SupportSQLiteDatabase) {
            removeRetired(db, keep = listOf(KIND_PLEX), before12 = false)
            val added = "SELECT id FROM epg_source WHERE playlistId IS NULL"
            listOf("programme", "channel_epg_mapping").forEach { db.execSQL("DELETE FROM $it WHERE sourceId IN ($added)") }
            db.execSQL("DELETE FROM epg_source WHERE playlistId IS NULL")
            // A time shift on a channel set by hand: nothing sets one any more, and the next sync would clear it.
            db.execSQL("UPDATE channel_epg_mapping SET manual = 0, offsetMinutes = 0")
        }
    }

    /** Every row already saved was on "Carry on watching", in the order it was last played. */
    /**
     * The guide's rows had no Plex ids before 16, and recording needs them: each server's last sync is made long
     * ago, so the app's next start syncs again (as it does when one is due) and the guide comes back with them.
     */
    class RecordingIds : AutoMigrationSpec {
        override fun onPostMigrate(db: SupportSQLiteDatabase) = db.execSQL("UPDATE playlist SET lastSyncAt = 1 WHERE lastSyncAt > 0")
    }

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

/**
 * Removes sources of kinds the app no longer has (all but those to [keep]), with everything they had, once and before
 * anything reads them. A backup holding one leaves it out when restored (see BackupRepository). [before12]: the guides'
 * channel lists and M3U episodes still have tables of their own.
 */
private fun removeRetired(db: SupportSQLiteDatabase, keep: List<String>, before12: Boolean) {
    val retired = "SELECT id FROM playlist WHERE kind NOT IN (${keep.joinToString { "'$it'" }})"
    val guides = "SELECT id FROM epg_source WHERE playlistId IN ($retired)"
    listOfNotNull("programme", "epg_channel".takeIf { before12 }).forEach { db.execSQL("DELETE FROM $it WHERE sourceId IN ($guides)") }
    db.execSQL("DELETE FROM channel_epg_mapping WHERE sourceId IN ($guides)")
    db.execSQL("DELETE FROM epg_source WHERE playlistId IN ($retired)")
    val perSource = listOf(
        "category", "channel", "favourite", "recent", "channel_epg_mapping", "movie", "series", "watch_progress", "vod_favourite"
    )
    (perSource + listOfNotNull("episode".takeIf { before12 })).forEach { db.execSQL("DELETE FROM $it WHERE playlistId IN ($retired)") }
    db.execSQL("DELETE FROM playlist WHERE id IN ($retired)")
}
