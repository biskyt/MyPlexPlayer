package com.biskyt.myplexplayer.core.plex

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive

/**
 * Where a server's Live TV is: its DVR ([dvrKey], for tuning) and its guide provider's channel list
 * ([channelsPath]) and programme grid ([gridPath]). As proven on the owner's server (docs/plex-api.md, 12-13).
 */
data class PlexLiveTv(val dvrKey: String, val channelsPath: String, val gridPath: String) {
    /** The guide provider's identifier (`tv.plex.providers.epg.cloud:4`), which a favourite's source names. */
    val provider: String get() = channelsPath.substringBefore("/lineups/")
}

/**
 * A Live TV channel of the guide provider's lineup. Its key ([tuneKey]) is the first of `key`, `ratingKey`,
 * `identifier` and `id` it has (as Plezy reads it; the owner's server gives `id`), and is what a programme names
 * its channel by ([PlexAiring.channelIdentifier]).
 */
@Serializable
data class PlexChannel(
    val key: String? = null,
    val ratingKey: String? = null,
    val identifier: String? = null,
    val id: String? = null,
    val title: String = "",
    val callSign: String? = null,
    val thumb: String? = null,
    /** The channel number ("101", or "2.1"): a string or a number in the JSON. */
    private val vcn: JsonPrimitive? = null,
    private val channelVcn: JsonPrimitive? = null,
) {
    /** Every name it may be given by a programme. */
    val keys: Set<String> get() = setOfNotNull(key, ratingKey, identifier, id)

    /** The one to tune by (part of the tune's address, so never a path). */
    val tuneKey: String? get() = keys.firstOrNull { '/' !in it }

    val number: String? get() = (vcn ?: channelVcn)?.content

    val name: String get() = title.ifBlank { callSign.orEmpty() }
}

/**
 * A programme in the guide grid, with its [airings] (one per showing: its times and channel). As a tune's result,
 * [key] is the live session's path and [ratingKey] the programme.
 */
@Serializable
data class PlexProgramme(
    val ratingKey: String? = null,
    val key: String = "",
    /** Plex's own id for it (`plex://episode/…`): what its recording template is asked for by. */
    val guid: String? = null,
    /** Its show's (`plex://show/…`): what a series recording names. */
    val grandparentGuid: String? = null,
    val type: String = "",
    val title: String = "",
    val grandparentTitle: String? = null,
    val summary: String = "",
    val year: Int? = null,
    val index: Int? = null,
    val parentIndex: Int? = null,
    val duration: Long? = null,
    // Its `Channel` list isn't read: those are tags (a number and a name), not the channel's key.
    @SerialName("Media") @Serializable(with = SkippingList::class) val airings: List<PlexAiring> = emptyList(),
)

/** One showing of a programme: from [beginsAt] to [endsAt], in seconds since 1970, on the channel [channelIdentifier]. */
@Serializable
data class PlexAiring(val beginsAt: Long? = null, val endsAt: Long? = null, val channelIdentifier: String? = null)

@Serializable
internal class PlexDvr(val key: String = "", val lineup: String = "")

@Serializable
internal class PlexProvider(
    val identifier: String = "",
    val protocols: String = "",
    @SerialName("Feature") val features: List<PlexFeature> = emptyList(),
)

@Serializable
internal class PlexFeature(val type: String = "", val key: String? = null)

/** A tune's reply: the grab of a tuner, whose programme (one object, not a list) has the session's path. */
@Serializable
internal class PlexSubscription(@SerialName("MediaGrabOperation") val grabs: List<PlexGrab> = emptyList())

@Serializable
internal class PlexGrab(@SerialName("Metadata") val programme: PlexProgramme? = null)

@Serializable
internal class PlexLiveResponse(@SerialName("MediaContainer") val container: PlexLiveContainer = PlexLiveContainer())

/** The Live TV replies: the DVRs (named `Dvr` on the owner's server; `DVR` in the spec), providers, channels, grid. */
@Serializable
internal class PlexLiveContainer(
    /** How many items the server sent (before any that can't be read are left out). */
    val size: Int = 0,
    @SerialName("Dvr") val dvrs: List<PlexDvr> = emptyList(),
    @SerialName("DVR") val dvrsAsSpecified: List<PlexDvr> = emptyList(),
    @SerialName("MediaProvider") val providers: List<PlexProvider> = emptyList(),
    // Not skipped: a channel left out would be removed (and its favourite with it). A guide entry left out isn't.
    @SerialName("Channel") val channels: List<PlexChannel> = emptyList(),
    @SerialName("Metadata") @Serializable(with = SkippingList::class) val programmes: List<PlexProgramme> = emptyList(),
    @SerialName("MediaSubscription") val subscriptions: List<PlexSubscription> = emptyList(),
)
