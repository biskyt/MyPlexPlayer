package com.biskyt.mytvplayer.core.xtream

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/** Login details for one Xtream Codes account. [server] is the base URL, e.g. `http://example.tv:8080`. */
data class XtreamCredentials(val server: String, val username: String, val password: String) {
    val base: String = server.trim().trimEnd('/').let { if ("://" in it) it else "http://$it" }
}

data class XtreamAccount(
    val active: Boolean,
    val expiresAtEpochSec: Long?,
    val maxConnections: Int?,
    val activeConnections: Int?,
)

data class XtreamCategory(val id: String, val name: String)

data class XtreamLiveStream(
    val streamId: Long,
    val number: Int,
    val name: String,
    val categoryId: String,
    val logoUrl: String?,
    val epgChannelId: String?,
    val archiveDays: Int,
)

// Xtream panels are inconsistent about types: numbers often arrive as strings, empty strings or null.
// These helpers read whatever is there and fall back quietly.

internal fun JsonObject.str(key: String): String? = when (val v = this[key]) {
    null, JsonNull -> null
    is JsonPrimitive -> v.content.takeIf { it.isNotBlank() && it != "null" }
    else -> null
}

internal fun JsonObject.long(key: String): Long? = str(key)?.let { it.toLongOrNull() ?: it.toDoubleOrNull()?.toLong() }

internal fun JsonObject.int(key: String): Int? = long(key)?.toInt()

internal fun JsonObject.bool(key: String): Boolean = when (str(key)?.lowercase()) {
    "1", "true", "active" -> true
    else -> false
}

internal fun JsonElement.toCategory(): XtreamCategory? {
    val o = this as? JsonObject ?: return null
    val id = o.str("category_id") ?: return null
    return XtreamCategory(id, o.str("category_name") ?: id)
}

internal fun JsonElement.toLiveStream(): XtreamLiveStream? {
    val o = this as? JsonObject ?: return null
    val id = o.long("stream_id") ?: return null
    return XtreamLiveStream(
        streamId = id,
        number = o.int("num") ?: 0,
        name = o.str("name")?.trim() ?: "Channel $id",
        categoryId = o.str("category_id") ?: "",
        logoUrl = o.str("stream_icon"),
        epgChannelId = o.str("epg_channel_id"),
        archiveDays = if (o.bool("tv_archive")) o.int("tv_archive_duration") ?: 0 else 0,
    )
}

internal fun JsonElement.toAccount(): XtreamAccount? {
    val info = (this as? JsonObject)?.get("user_info")?.jsonObject ?: return null
    return XtreamAccount(
        active = info.str("auth") == "1" && (info.str("status")?.equals("Active", ignoreCase = true) ?: true),
        expiresAtEpochSec = info.long("exp_date"),
        maxConnections = info.int("max_connections"),
        activeConnections = info.int("active_cons"),
    )
}
