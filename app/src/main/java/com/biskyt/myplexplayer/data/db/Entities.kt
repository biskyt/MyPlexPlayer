package com.biskyt.myplexplayer.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

const val KIND_XTREAM = "xtream"
const val KIND_PLEX = "plex"

/** The kinds of source the app has; any other (M3U, before 0.2.2) is removed (see AppDatabase.RetiredSources). */
val SOURCE_KINDS = setOf(KIND_PLEX, KIND_XTREAM)

/**
 * A source. [kind] is [KIND_PLEX] (a Plex Media Server: [server] is the address chosen for it, [password] its access
 * token and [machineId] its identifier) or [KIND_XTREAM] (server + login).
 */
@Entity(tableName = "playlist")
@Serializable
data class PlaylistEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    @ColumnInfo(defaultValue = KIND_XTREAM) val kind: String = KIND_XTREAM,
    val server: String,
    val username: String,
    val password: String,
    val lastSyncAt: Long = 0,
    val expiresAt: Long? = null,
    val maxConnections: Int? = null,
    /** Unused since M3U playlists went (0.2.2); see AppDatabase. */
    val moviesUrl: String? = null,
    val seriesUrl: String? = null,
    val vodFeedHash: Long? = null,
    /** Plex: the server's machine identifier, which stays the same when its address changes. */
    val machineId: String? = null
) {
    val isPlex: Boolean get() = kind == KIND_PLEX
    val isXtream: Boolean get() = kind == KIND_XTREAM

    /** Channels are refreshed in the background when the last download is older than this. */
    fun isStale(now: Long = System.currentTimeMillis()) = now - lastSyncAt > 12 * 3_600_000L
}

/** What a category holds. */
const val CAT_LIVE = "live"
const val CAT_MOVIE = "movie"
const val CAT_SERIES = "series"

/** Movies and series where they're shown side by side: series first. */
val VOD_TYPES = listOf(CAT_SERIES, CAT_MOVIE)

/**
 * A provider category of one [type] (providers number live, movie and series categories separately). Only items from
 * [included] categories are downloaded.
 */
@Entity(tableName = "category", primaryKeys = ["playlistId", "type", "categoryId"])
@Serializable
data class CategoryEntity(
    val playlistId: Long,
    val categoryId: String,
    val name: String,
    /** Language/country group detected from the name (see CategoryLanguageDetector), null if unknown. */
    val lang: String?,
    val included: Boolean,
    val sortOrder: Int,
    /** Channels, movies or series in it. */
    val channelCount: Int = 0,
    @ColumnInfo(defaultValue = CAT_LIVE) val type: String = CAT_LIVE
)

/** A movie from the provider's list. [title] and [year] are cleaned from the provider's name (see VodTitles). */
@Entity(
    tableName = "movie",
    primaryKeys = ["playlistId", "streamId"],
    // Title is in the order indexes so paged lists never sort (many providers number every title the same).
    indices = [
        Index(
            "playlistId",
            "categoryId",
            "position",
            "title"
        ), Index("playlistId", "position", "title"), Index("playlistId", "added")
    ]
)
data class MovieEntity(
    val playlistId: Long,
    val streamId: Long,
    val title: String,
    val year: Int?,
    val categoryId: String,
    val posterUrl: String?,
    val rating: Double?,
    val added: Long,
    val extension: String,
    /** Provider order. */
    val position: Int,
    val syncStamp: Long,
    /** Unused since 0.2.2 (M3U films); see AppDatabase. */
    val streamUrl: String? = null
)

@Entity(
    tableName = "series",
    primaryKeys = ["playlistId", "seriesId"],
    indices = [
        Index("playlistId", "categoryId", "position", "title"),
        Index("playlistId", "position", "title"),
        Index("playlistId", "lastModified")
    ]
)
data class SeriesEntity(
    val playlistId: Long,
    val seriesId: Long,
    val title: String,
    val year: Int?,
    val categoryId: String,
    val coverUrl: String?,
    val rating: Double?,
    val lastModified: Long,
    val position: Int,
    val syncStamp: Long,
    /** Plex: how many of its episodes haven't been watched, for the badge on its poster (null: not known). */
    @ColumnInfo(defaultValue = "NULL") val unwatched: Int? = null
)

/** Unused since 0.2.2 (M3U episodes): see AppDatabase. */
@Entity(tableName = "episode", primaryKeys = ["playlistId", "episodeId"], indices = [Index("playlistId", "seriesId", "season", "number")])
data class EpisodeEntity(
    val playlistId: Long,
    val episodeId: Long,
    val seriesId: Long,
    val season: Int,
    val number: Int,
    /** The playlist's title; blank when it gave none (the app names it when shown, see episodeTitle). */
    val title: String,
    val streamUrl: String,
    val syncStamp: Long
)

const val VOD_MOVIE = "movie"
const val VOD_EPISODE = "episode"

/**
 * How far into a movie or episode the user got, for resuming and "Carry on watching" (which shows [title] and
 * [imageUrl] without another lookup). [kind] is [VOD_MOVIE] or [VOD_EPISODE].
 */
@Entity(
    tableName = "watch_progress",
    primaryKeys = ["playlistId", "kind", "itemId"],
    indices = [Index("playlistId", "updatedAt"), Index("playlistId", "seriesId", "updatedAt")]
)
@Serializable
data class WatchProgressEntity(
    val playlistId: Long,
    val kind: String,
    val itemId: Long,
    val positionMs: Long,
    val durationMs: Long,
    val updatedAt: Long,
    val title: String,
    val imageUrl: String?,
    /** Episodes: the series they belong to, and where in it. */
    val seriesId: Long? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val episodeTitle: String? = null,
    /** Episodes: whether another follows, so a finished one still leads "Carry on watching" to the next. */
    val hasNext: Boolean = false,
    /**
     * Where it sorts in "Carry on watching" (newest first): when it was last played here, or for Plex, by the
     * server's Continue Watching. Null keeps it off the row (taken off it, there or here), with its resume point.
     */
    val carryOnAt: Long? = updatedAt,
    /** Plex: played here, and not yet reported to the server (sent again at the next sync; the server can't overwrite it). */
    @ColumnInfo(defaultValue = "0") val pending: Boolean = false
) {
    val finished: Boolean get() = durationMs > 0 && positionMs >= durationMs * FINISHED_FRACTION
    val fraction: Float get() = if (durationMs > 0) positionMs.toFloat() / durationMs else 0f
    val remainingMs: Long get() = (durationMs - positionMs).coerceAtLeast(0)

    companion object {
        /** Past this share of the running time (the credits), something counts as watched. */
        const val FINISHED_FRACTION = 0.92
    }
}

/**
 * A favourite movie or series. [kind] is [CAT_MOVIE] or [CAT_SERIES]. [position]: where the user put it (0, as for
 * favourites from before 1.2.0, keeps the original order: newest first).
 */
@Entity(tableName = "vod_favourite", primaryKeys = ["playlistId", "kind", "itemId"])
@Serializable
data class VodFavouriteEntity(
    val playlistId: Long,
    val kind: String,
    val itemId: Long,
    val addedAt: Long,
    @ColumnInfo(defaultValue = "0") val position: Long = 0
)

/**
 * Provider fields are refreshed on every sync; [hidden] and [sortOrder] are the user's and survive syncs.
 * [position] is the effective list order (the user's [sortOrder], else the provider number), stored so lists of
 * thousands of channels page straight from an index instead of sorting every row.
 */
@Entity(
    tableName = "channel",
    primaryKeys = ["playlistId", "streamId"],
    indices = [
        Index("playlistId", "hidden", "position", "name"),
        Index("playlistId", "categoryId", "hidden", "position", "name"),
        Index("playlistId", "number"),
        // The Catch-up list, and whether a playlist has one (asked on every screen).
        Index("playlistId", "hidden", "archiveDays")
    ]
)
@Serializable
data class ChannelEntity(
    val playlistId: Long,
    val streamId: Long,
    val number: Int,
    val name: String,
    val categoryId: String,
    val logoUrl: String?,
    val providerEpgId: String?,
    /** Catch-up days offered by the provider (0: no catch-up). */
    val archiveDays: Int,
    val hidden: Boolean = false,
    val sortOrder: Int? = null,
    val position: Int = sortOrder ?: number,
    @ColumnInfo(defaultValue = "0") val syncStamp: Long = 0,
    /** Unused since 0.2.2 (M3U channels' own URL and headers); see AppDatabase. Xtream URLs are built from the login. */
    val streamUrl: String? = null,
    val userAgent: String? = null,
    val referrer: String? = null,
    /** Unused since 0.2.2 (M3U catch-up); see AppDatabase. */
    val catchupType: String? = null,
    val catchupSource: String? = null
)

/** [position]: where the user put it (0, as for favourites from before 1.2.0, keeps channel-number order). */
@Entity(tableName = "favourite", primaryKeys = ["playlistId", "streamId"])
@Serializable
data class FavouriteEntity(
    val playlistId: Long,
    val streamId: Long,
    val addedAt: Long,
    @ColumnInfo(defaultValue = "0") val position: Long = 0
)

@Entity(tableName = "recent", primaryKeys = ["playlistId", "streamId"], indices = [Index("playlistId", "watchedAt")])
data class RecentEntity(val playlistId: Long, val streamId: Long, val watchedAt: Long)

/** An XMLTV guide source. Provider guides are added automatically with [playlistId] set. */
@Entity(tableName = "epg_source")
@Serializable
data class EpgSourceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val url: String,
    val playlistId: Long? = null,
    val enabled: Boolean = true,
    val lastImportAt: Long = 0,
    val channelCount: Int = 0,
    val lastError: String? = null
)

@Entity(tableName = "epg_channel", primaryKeys = ["sourceId", "xmlId"])
data class EpgChannelEntity(
    val sourceId: Long,
    val xmlId: String,
    val displayName: String,
    val nameNorm: String
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
    /** Import run that last wrote this row; rows from older runs are deleted once an import finishes. */
    @ColumnInfo(defaultValue = "0") val stamp: Long = 0,
    /** Series, film or neither (a ProgrammeKind constant), for "Find series" / "Find in Movies". */
    @ColumnInfo(defaultValue = "0") val kind: Int = 0,
    val season: Int? = null,
    val episode: Int? = null
)

/**
 * Which guide channel a playlist channel uses. [manual] mappings are the user's choice and are never overwritten
 * by automatic matching. A manual mapping with null [sourceId] means "no guide". [offsetMinutes] shifts programme times.
 */
@Entity(tableName = "channel_epg_mapping", primaryKeys = ["playlistId", "streamId"], indices = [Index("sourceId", "xmlId")])
@Serializable
data class ChannelEpgMappingEntity(
    val playlistId: Long,
    val streamId: Long,
    val sourceId: Long?,
    val xmlId: String?,
    val manual: Boolean,
    val score: Double,
    val offsetMinutes: Int = 0
)
