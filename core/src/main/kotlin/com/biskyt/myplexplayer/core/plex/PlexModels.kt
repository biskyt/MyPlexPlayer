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
    val addedAt: Long? = null,
    val leafCount: Int? = null,
    val viewedLeafCount: Int? = null,
    @SerialName("Media") val media: List<PlexMedia> = emptyList(),
) {
    /** The first file of the first version, the one played. */
    val part: PlexPart? get() = media.firstOrNull()?.parts?.firstOrNull()
}

@Serializable
data class PlexMedia(
    val id: Long = 0,
    val container: String? = null,
    val videoCodec: String? = null,
    val audioCodec: String? = null,
    val videoResolution: String? = null,
    val duration: Long? = null,
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
    @SerialName("Stream") val streams: List<PlexStreamInfo> = emptyList(),
)

/**
 * One stream of a file: [streamType] 1 video, 2 audio, 3 subtitles. [decision], in a direct stream decision:
 * "copy" (sent as it is), "transcode" (re-encoded) or "ignore" (left out).
 */
@Serializable
data class PlexStreamInfo(val streamType: Int = 0, val codec: String? = null, val decision: String? = null)

/** What a player reports in a timeline. */
enum class PlexPlayState(val value: String) {
    PLAYING("playing"),
    PAUSED("paused"),
    BUFFERING("buffering"),
    STOPPED("stopped"),
}
