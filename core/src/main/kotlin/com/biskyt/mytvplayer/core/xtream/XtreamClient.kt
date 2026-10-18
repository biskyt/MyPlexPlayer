package com.biskyt.mytvplayer.core.xtream

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.DecodeSequenceMode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeToSequence
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.io.InputStream

class XtreamException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * Minimal Xtream Codes API client. Large responses (the live stream list can hold tens of thousands of entries)
 * are decoded as a stream, one element at a time, so the full list is never held in memory.
 */
class XtreamClient(private val http: OkHttpClient, private val credentials: XtreamCredentials) {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    suspend fun account(): XtreamAccount = withContext(Dispatchers.IO) {
        get(apiUrl()) { json.decodeFromStream(it).toAccount() }
            ?: throw XtreamException("Login failed: check the server address, username and password")
    }

    suspend fun liveCategories(): List<XtreamCategory> = withContext(Dispatchers.IO) {
        get(apiUrl("get_live_categories")) { input -> json.decodeArray(input).mapNotNull { it.toCategory() }.toList() }
    }

    /**
     * Streams live channels to [onStream]. Pass [categoryId] to fetch a single category.
     * Returns the number of channels read.
     */
    suspend fun liveStreams(categoryId: String? = null, onStream: (XtreamLiveStream) -> Unit): Int = withContext(Dispatchers.IO) {
        val url = apiUrl("get_live_streams") { if (categoryId != null) addQueryParameter("category_id", categoryId) }
        get(url) { input ->
            var count = 0
            json.decodeArray(input).forEach { el -> el.toLiveStream()?.let { onStream(it); count++ } }
            count
        }
    }

    fun liveStreamUrl(streamId: Long, extension: String = "ts"): String =
        "${credentials.base}/live/${credentials.username.enc()}/${credentials.password.enc()}/$streamId.$extension"

    fun xmltvUrl(): String = "${credentials.base}/xmltv.php".toHttpUrl().newBuilder()
        .addQueryParameter("username", credentials.username)
        .addQueryParameter("password", credentials.password)
        .build().toString()

    private fun apiUrl(action: String? = null, extra: HttpUrl.Builder.() -> Unit = {}): HttpUrl {
        val base = "${credentials.base}/player_api.php".toHttpUrlOrThrow()
        return base.newBuilder()
            .addQueryParameter("username", credentials.username)
            .addQueryParameter("password", credentials.password)
            .apply { if (action != null) addQueryParameter("action", action) }
            .apply(extra)
            .build()
    }

    private fun <T> get(url: HttpUrl, read: (InputStream) -> T): T {
        val response = try {
            http.newCall(Request.Builder().url(url).build()).execute()
        } catch (e: IOException) {
            throw XtreamException("Couldn't reach the server: ${e.message}", e)
        }
        response.use {
            if (!it.isSuccessful) throw XtreamException("Server returned error ${it.code}")
            val body = it.body?.byteStream() ?: throw XtreamException("Empty response from server")
            return try {
                read(body)
            } catch (e: SerializationException) {
                throw XtreamException("Unexpected response from server", e)
            } catch (e: NoSuchElementException) {
                throw XtreamException("Empty response from server", e)
            }
        }
    }

    @OptIn(ExperimentalSerializationApi::class)
    private fun Json.decodeFromStream(input: InputStream): JsonElement =
        decodeToSequence<JsonElement>(input, DecodeSequenceMode.WHITESPACE_SEPARATED).first()

    @OptIn(ExperimentalSerializationApi::class)
    private fun Json.decodeArray(input: InputStream): Sequence<JsonElement> =
        decodeToSequence<JsonElement>(input, DecodeSequenceMode.ARRAY_WRAPPED)

    private fun String.toHttpUrlOrThrow(): HttpUrl = try {
        toHttpUrl()
    } catch (e: IllegalArgumentException) {
        throw XtreamException("Invalid server address", e)
    }

    private fun String.enc(): String = java.net.URLEncoder.encode(this, "UTF-8").replace("+", "%20")
}
