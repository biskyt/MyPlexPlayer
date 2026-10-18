package com.biskyt.mytvplayer.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "playlist")
data class PlaylistEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val server: String,
    val username: String,
    val password: String,
    val lastSyncAt: Long = 0,
    val expiresAt: Long? = null,
    val maxConnections: Int? = null,
)

/** A provider category. Only channels from [included] categories are downloaded. */
@Entity(tableName = "category", primaryKeys = ["playlistId", "categoryId"])
data class CategoryEntity(
    val playlistId: Long,
    val categoryId: String,
    val name: String,
    /** Language/country group detected from the name (see CategoryLanguageDetector), null if unknown. */
    val lang: String?,
    val included: Boolean,
    val sortOrder: Int,
    val channelCount: Int = 0,
)

/**
 * Provider fields are refreshed on every sync; [hidden] and [sortOrder] are the user's and survive syncs
 * (sync uses an upsert that only touches provider columns).
 */
@Entity(
    tableName = "channel",
    primaryKeys = ["playlistId", "streamId"],
    indices = [Index("playlistId", "categoryId", "number"), Index("playlistId", "number")],
)
data class ChannelEntity(
    val playlistId: Long,
    val streamId: Long,
    val number: Int,
    val name: String,
    val categoryId: String,
    val logoUrl: String?,
    val providerEpgId: String?,
    val archiveDays: Int,
    val hidden: Boolean = false,
    val sortOrder: Int? = null,
    @ColumnInfo(defaultValue = "0") val syncStamp: Long = 0,
)

@Entity(tableName = "favourite", primaryKeys = ["playlistId", "streamId"])
data class FavouriteEntity(val playlistId: Long, val streamId: Long, val addedAt: Long)

@Entity(tableName = "recent", primaryKeys = ["playlistId", "streamId"], indices = [Index("playlistId", "watchedAt")])
data class RecentEntity(val playlistId: Long, val streamId: Long, val watchedAt: Long)

/** An XMLTV guide source. Provider guides are added automatically with [playlistId] set. */
@Entity(tableName = "epg_source")
data class EpgSourceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val url: String,
    val playlistId: Long? = null,
    val enabled: Boolean = true,
    val lastImportAt: Long = 0,
    val channelCount: Int = 0,
    val lastError: String? = null,
)

@Entity(tableName = "epg_channel", primaryKeys = ["sourceId", "xmlId"], indices = [Index("nameNorm")])
data class EpgChannelEntity(
    val sourceId: Long,
    val xmlId: String,
    val displayName: String,
    val nameNorm: String,
    val iconUrl: String?,
)

@Entity(tableName = "programme", primaryKeys = ["sourceId", "xmlId", "startMs"], indices = [Index("stopMs")])
data class ProgrammeEntity(
    val sourceId: Long,
    val xmlId: String,
    val startMs: Long,
    val stopMs: Long,
    val title: String,
    val subtitle: String?,
    val description: String?,
    val category: String?,
)

/**
 * Which guide channel a playlist channel uses. [manual] mappings are the user's choice and are never overwritten
 * by automatic matching. A manual mapping with null [sourceId] means "no guide". [offsetMinutes] shifts programme times.
 */
@Entity(tableName = "channel_epg_mapping", primaryKeys = ["playlistId", "streamId"], indices = [Index("sourceId", "xmlId")])
data class ChannelEpgMappingEntity(
    val playlistId: Long,
    val streamId: Long,
    val sourceId: Long?,
    val xmlId: String?,
    val manual: Boolean,
    val score: Double,
    val offsetMinutes: Int = 0,
)
