package com.biskyt.myplexplayer.dvdiag

import android.content.Context
import android.os.Build
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** Just enough of Plex for a test play: sign-in by code at plex.tv/link, the account's servers, and finding a title. */
class Plex(context: Context) {
    private val prefs = context.getSharedPreferences("plex", Context.MODE_PRIVATE)
    private val clientId = prefs.getString("clientId", null) ?: UUID.randomUUID().toString().also {
        prefs.edit().putString("clientId", it).apply()
    }

    /** The account's token, kept on this device only (never in the report). */
    var accountToken: String?
        get() = prefs.getString("token", null)
        set(value) = prefs.edit().putString("token", value).apply()

    class Pin(val id: Long, val code: String)
    class Connection(val uri: String, val local: Boolean, val relay: Boolean)
    class Server(val name: String, val token: String, val connections: List<Connection>)
    class Connected(val name: String, val base: String, val token: String)
    class Item(val key: String, val type: String, val label: String)

    suspend fun createPin(): Pin {
        val o = request("$PLEX_TV/api/v2/pins", null, "POST")
        return Pin(o.getLong("id"), o.getString("code"))
    }

    /** The account's token once the code has been entered at plex.tv/link, or null until then. */
    suspend fun pinToken(pin: Pin): String? =
        request("$PLEX_TV/api/v2/pins/${pin.id}", null).optString("authToken").takeIf { it.isNotEmpty() && it != "null" }

    suspend fun servers(token: String): List<Server> {
        return JSONArray(fetch("$PLEX_TV/api/v2/resources?includeHttps=1&includeRelay=1", token)).objects()
            .filter { "server" in it.optString("provides") }
            .map { s ->
                Server(
                    s.optString("name"),
                    s.optString("accessToken").ifEmpty { token },
                    s.optJSONArray("connections").objects().map { Connection(it.getString("uri"), it.optBoolean("local"), it.optBoolean("relay")) }
                )
            }
    }

    /** The server at the first of its addresses that answers: home ones first, then outside ones, then Plex's relay. */
    suspend fun connect(server: Server): Connected? {
        val ordered = server.connections.sortedBy { if (it.relay) 2 else if (it.local) 0 else 1 }
        for (c in ordered) {
            val ok = runCatching { request("${c.uri}/identity", server.token, timeoutMs = 4000) }.isSuccess
            if (ok) return Connected(server.name, c.uri, server.token)
        }
        return null
    }

    suspend fun onDeck(s: Connected): List<Item> = items(container(s, "/library/onDeck"))

    suspend fun search(s: Connected, query: String): List<Item> {
        val hubs = container(s, "/hubs/search?limit=20&query=${URLEncoder.encode(query, "UTF-8")}").optJSONArray("Hub")
            ?: return emptyList()
        return hubs.objects().flatMap { items(it) }
            .filter { it.type in setOf("movie", "episode", "show") }
    }

    suspend fun episodes(s: Connected, show: Item): List<Item> = items(container(s, "/library/metadata/${show.key}/allLeaves"))

    /** A film's or episode's details: its first file's video stream, as the server describes it. */
    suspend fun metadata(s: Connected, item: Item): JSONObject =
        container(s, "/library/metadata/${item.key}").getJSONArray("Metadata").getJSONObject(0)

    /** The file played directly, as MyPlexPlayer does (the token in the address). */
    fun playUrl(s: Connected, partKey: String) = "${s.base}$partKey?X-Plex-Token=${s.token}"

    private suspend fun container(s: Connected, path: String) = request(s.base + path, s.token).getJSONObject("MediaContainer")

    private fun items(container: JSONObject): List<Item> {
        return container.optJSONArray("Metadata").objects().map {
            val type = it.optString("type")
            val label = when (type) {
                "episode" -> "${it.optString("grandparentTitle")} S${it.optInt("parentIndex")}E${it.optInt("index")}: " +
                    it.optString("title")
                "show" -> "${it.optString("title")} (series: pick an episode)"
                else -> "${it.optString("title")} (${it.optInt("year")})"
            }
            Item(it.optString("ratingKey"), type, label)
        }
    }

    private suspend fun request(url: String, token: String?, method: String = "GET", timeoutMs: Int = 15_000) =
        JSONObject(fetch(url, token, method, timeoutMs))

    private suspend fun fetch(url: String, token: String?, method: String = "GET", timeoutMs: Int = 15_000): String = withContext(Dispatchers.IO) {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.requestMethod = method
            c.connectTimeout = timeoutMs
            c.readTimeout = timeoutMs
            c.setRequestProperty("Accept", "application/json")
            c.setRequestProperty("X-Plex-Client-Identifier", clientId)
            c.setRequestProperty("X-Plex-Product", "MyPlexPlayer DV diagnostics")
            c.setRequestProperty("X-Plex-Version", "1")
            c.setRequestProperty("X-Plex-Platform", "Android")
            c.setRequestProperty("X-Plex-Device", Build.MODEL)
            if (token != null) c.setRequestProperty("X-Plex-Token", token)
            if (c.responseCode !in 200..299) error("HTTP ${c.responseCode} from ${URL(url).path}")
            c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }

    private companion object {
        const val PLEX_TV = "https://plex.tv"
    }
}

/** A JSON array's objects; none for a missing array. */
fun JSONArray?.objects(): List<JSONObject> = if (this == null) emptyList() else List(length()) { getJSONObject(it) }
