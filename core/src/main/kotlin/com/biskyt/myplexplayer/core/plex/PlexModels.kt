package com.biskyt.myplexplayer.core.plex

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * One Plex Media Server: its address (the connection chosen for it) and its access token (null for a test server
 * that needs none). Responses are read as JSON (`Accept: application/json`); see docs/plex-api.md.
 */
data class PlexServer(val baseUrl: String, val token: String?)

/** Who is asking: sent as the X-Plex-* headers Plex needs (the server lists the app under Authorized Devices). */
data class PlexDevice(
    /** Made once per install and kept: Plex tells clients apart by it. */
    val clientId: String,
    val product: String,
    val version: String,
    val platform: String = "Android",
    val platformVersion: String = "",
    val device: String = "",
    val deviceName: String = "",
)

/** Plex item types, as `type=` in queries. */
enum class PlexType(val number: Int) {
    MOVIE(1),
    SHOW(2),
    SEASON(3),
    EPISODE(4),
}

/** A page of a library: [items] from [offset], out of [totalSize] in all (null if the server didn't say). */
data class PlexPage(val items: List<PlexItem>, val offset: Int, val totalSize: Int?)

@Serializable
internal class PlexResponse(@SerialName("MediaContainer") val container: PlexContainer = PlexContainer())

@Serializable
internal class PlexContainer(
    val size: Int = 0,
    val totalSize: Int? = null,
    val offset: Int = 0,
    val machineIdentifier: String? = null,
    /** A direct stream decision: under 2000 it can be streamed (see each [PlexPart.streams]); 2000 and up, not. */
    val generalDecisionCode: Int? = null,
    val generalDecisionText: String? = null,
    @SerialName("Directory") val directories: List<PlexSection> = emptyList(),
    @SerialName("Metadata") val items: List<PlexItem> = emptyList(),
    @SerialName("Hub") val hubs: List<PlexHub> = emptyList(),
)

/** `/`, of which only whether the server lets its media be deleted is read (its other lists aren't libraries). */
@Serializable
internal class PlexRootResponse(@SerialName("MediaContainer") val container: PlexRoot = PlexRoot())

@Serializable
internal class PlexRoot(val allowMediaDeletion: Boolean = false)

/** A genre, person, country or collection; for the cast, [role] is the part played and [thumb] a photo. */
@Serializable
data class PlexTag(val tag: String = "", val role: String? = null, val thumb: String? = null)

@Serializable
data class PlexGuid(val id: String = "")

/** A library ("section"): [key] is its id, [type] "movie" or "show" (or another kind the app doesn't show). */
@Serializable
data class PlexSection(val key: String, val type: String, val title: String = "", val uuid: String? = null)

@Serializable
internal class PlexHub(
    val type: String = "",
    @SerialName("Metadata") val items: List<PlexItem> = emptyList(),
)

/**
 * A film, show, season or episode. Times are in milliseconds ([duration], [viewOffset]) or epoch seconds
 * ([addedAt]). For an episode, `parent*` is its season and `grandparent*` its show.
 */
@Serializable
data class PlexItem(
    val ratingKey: String,
    val key: String = "",
    val type: String = "",
    val title: String = "",
    val summary: String = "",
    val year: Int? = null,
    val index: Int? = null,
    val parentIndex: Int? = null,
    val parentRatingKey: String? = null,
    val parentTitle: String? = null,
    val grandparentRatingKey: String? = null,
    val grandparentTitle: String? = null,
    val thumb: String? = null,
    val art: String? = null,
    val parentThumb: String? = null,
    val grandparentThumb: String? = null,
    val duration: Long? = null,
    val viewOffset: Long? = null,
    val viewCount: Int? = null,
    /** When it was last played, in seconds since 1970. */
    val lastViewedAt: Long? = null,
    val addedAt: Long? = null,
    val leafCount: Int? = null,
    val viewedLeafCount: Int? = null,
    /** A show's seasons. */
    val childCount: Int? = null,
    val contentRating: String? = null,
    /** Plex's critic rating and audience rating, out of 10. */
    val rating: Double? = null,
    val audienceRating: Double? = null,
    val studio: String? = null,
    val originalTitle: String? = null,
    /** "2019-03-14". */
    val originallyAvailableAt: String? = null,
    @SerialName("Genre") @Serializable(with = SkippingList::class) val genres: List<PlexTag> = emptyList(),
    @SerialName("Director") @Serializable(with = SkippingList::class) val directors: List<PlexTag> = emptyList(),
    @SerialName("Writer") @Serializable(with = SkippingList::class) val writers: List<PlexTag> = emptyList(),
    @SerialName("Producer") @Serializable(with = SkippingList::class) val producers: List<PlexTag> = emptyList(),
    @SerialName("Country") @Serializable(with = SkippingList::class) val countries: List<PlexTag> = emptyList(),
    @SerialName("Collection") @Serializable(with = SkippingList::class) val collections: List<PlexTag> = emptyList(),
    /** The cast, in billing order: [PlexTag.tag] the actor, [PlexTag.role] the part, [PlexTag.thumb] a photo. */
    @SerialName("Role") @Serializable(with = SkippingList::class) val roles: List<PlexTag> = emptyList(),
    /** Its ids elsewhere ("imdb://tt0118799", "tmdb://…", "tvdb://…"); sent only when asked for ([PlexClient.item]). */
    @SerialName("Guid") @Serializable(with = SkippingList::class) val guids: List<PlexGuid> = emptyList(),
    @SerialName("Media") val media: List<PlexMedia> = emptyList(),
    /** Its intro and credits, when Plex has found them; sent only when asked for ([PlexClient.item] does). */
    @SerialName("Marker") @Serializable(with = SkippingList::class) val markers: List<PlexMarker> = emptyList(),
    /** A show's own settings, sent only when asked for ([PlexClient.autoDeleteWatched] does). */
    @SerialName("Preferences") val preferences: PlexPreferences? = null,
) {
    /** The first file of the first version, the one played. */
    val part: PlexPart? get() = media.firstOrNull()?.parts?.firstOrNull()
}

/** An item's own settings (`includePreferences=1`); a show's are what [PlexAutoDelete] is read from. */
@Serializable
class PlexPreferences(@SerialName("Setting") val settings: List<PlexSetting> = emptyList())

@Serializable
class PlexSetting(val id: String = "", val value: String? = null)

/**
 * A show's "Delete episodes after playing": how soon Plex deletes an episode once the server's admin has watched
 * it. [value] is the setting's value on the server.
 */
enum class PlexAutoDelete(val value: Int) {
    NEVER(0),
    AFTER_A_DAY(1),
    AFTER_A_WEEK(7),
    AFTER_A_MONTH(30),
    ON_NEXT_REFRESH(100),
    ;

    companion object {
        fun of(value: Int?): PlexAutoDelete? = entries.firstOrNull { it.value == value }
    }
}

/**
 * A stretch of a film or episode that Plex Pass found ([type] [INTRO] or [CREDITS]), from [startMs] to [endMs]
 * into it. [final] credits run to the end (Plex's "next episode" moment); others are mid-film or mid-episode.
 */
@Serializable
data class PlexMarker(
    val type: String,
    @SerialName("startTimeOffset") val startMs: Long,
    @SerialName("endTimeOffset") val endMs: Long,
    val final: Boolean = false,
) {
    companion object {
        const val INTRO = "intro"
        const val CREDITS = "credits"
        val SKIPPABLE = setOf(INTRO, CREDITS)
    }
}

@Serializable
data class PlexMedia(
    val id: Long = 0,
    val container: String? = null,
    val videoCodec: String? = null,
    val audioCodec: String? = null,
    val videoResolution: String? = null,
    val duration: Long? = null,
    /** Kbit/s. */
    val bitrate: Int? = null,
    val audioChannels: Int? = null,
    @SerialName("Part") val parts: List<PlexPart> = emptyList(),
)

/**
 * A file: [key] is its path on the server (`/library/parts/{id}/{changestamp}/file.mkv`). In a direct stream
 * decision, [streams] says what the server would do with each stream.
 */
@Serializable
data class PlexPart(
    val id: Long = 0,
    val key: String = "",
    val container: String? = null,
    val duration: Long? = null,
    /** Bytes. */
    val size: Long? = null,
    @SerialName("Stream") val streams: List<PlexStreamInfo> = emptyList(),
)

/**
 * One stream of a file: [streamType] 1 video, 2 audio, 3 subtitles. [decision], in a direct stream decision:
 * "copy" (sent as it is), "transcode" (re-encoded) or "ignore" (left out). The rest come with an item's details:
 * [displayTitle] is the server's own name for it ("English (TRUEHD 7.1)", "4K DoVi/HDR10 (HEVC Main 10)").
 */
@Serializable
data class PlexStreamInfo(
    val streamType: Int = 0,
    val codec: String? = null,
    val decision: String? = null,
    val displayTitle: String? = null,
    val extendedDisplayTitle: String? = null,
    val title: String? = null,
    val language: String? = null,
    val forced: Boolean = false,
    val channels: Int? = null,
    val width: Int? = null,
    val height: Int? = null,
    val frameRate: Double? = null,
    /** "smpte2084" is HDR10 (or HDR10+, or Dolby Vision's base), "arib-std-b67" HLG. */
    val colorTrc: String? = null,
    @SerialName("DOVIPresent") val doviPresent: Boolean = false,
    @SerialName("DOVIProfile") val doviProfile: Int? = null,
)

/** What a player reports in a timeline. */
enum class PlexPlayState(val value: String) {
    PLAYING("playing"),
    PAUSED("paused"),
    BUFFERING("buffering"),
    STOPPED("stopped"),
}
