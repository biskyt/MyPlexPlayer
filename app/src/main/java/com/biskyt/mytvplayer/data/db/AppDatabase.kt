package com.biskyt.mytvplayer.data.db

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    version = 3,
    exportSchema = true,
    // 2: M3U playlists (playlist.kind; channel.streamUrl/userAgent/referrer).
    // 3: movies and series (category.type; movie, series, watch_progress, vod_favourite).
    autoMigrations = [AutoMigration(from = 1, to = 2), AutoMigration(from = 2, to = 3)],
    entities = [
        PlaylistEntity::class, CategoryEntity::class, ChannelEntity::class, FavouriteEntity::class, RecentEntity::class,
        EpgSourceEntity::class, EpgChannelEntity::class, ProgrammeEntity::class, ChannelEpgMappingEntity::class,
        MovieEntity::class, SeriesEntity::class, WatchProgressEntity::class, VodFavouriteEntity::class
    ]
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun playlists(): PlaylistDao
    abstract fun categories(): CategoryDao
    abstract fun channels(): ChannelDao
    abstract fun favourites(): FavouriteDao
    abstract fun epg(): EpgDao
    abstract fun vod(): VodDao

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

    companion object {
        fun create(context: Context): AppDatabase = Room.databaseBuilder(context, AppDatabase::class.java, "mytvplayer.db")
            // Write-ahead logging lets the list read while a background sync writes.
            .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
            .build()
    }
}
