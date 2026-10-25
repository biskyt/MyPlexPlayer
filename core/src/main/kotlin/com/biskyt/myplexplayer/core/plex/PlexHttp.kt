package com.biskyt.myplexplayer.core.plex

import com.biskyt.myplexplayer.core.plex.PlexException.Reason
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.jsonArray
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

/**
 * A failure talking to plex.tv or a Plex server. [message] is in English, for logs; [reason] (with [code]) lets the app tell
 * the user in their language.
 */
class PlexException(
    message: String,
    cause: Throwable? = null,
    val reason: Reason = Reason.OTHER,
    /** The server's HTTP status, for [Reason.SERVER_ERROR]. */
    val code: Int = 0,
) : IOException(message, cause) {
    enum class Reason {
        /** The token was refused (401): sign in again. */
        UNAUTHORIZED,
        UNREACHABLE,
        SERVER_ERROR,
        UNEXPECTED_RESPONSE,
        INVALID_ADDRESS,

        /** The file can't be played without the server re-encoding it, which the app never asks for. */
        NEEDS_TRANSCODE,

        /** The server can't stream this file at all (missing, or no free streams): [message] has its reason. */
        CANNOT_STREAM,
        OTHER,
    }
}

internal val plexJson = Json {
    ignoreUnknownKeys = true
    // A null where a value has a default (a programme without a title) takes the default, rather than failing.
    coerceInputValues = true
}

/**
 * A list without the items that can't be read (a programme with a field of another type, say), rather than failing
 * the whole reply: one odd programme mustn't cost the whole guide.
 */
internal class SkippingList<T>(private val item: KSerializer<T>) : KSerializer<List<T>> {
    private val list = ListSerializer(item)
    override val descriptor = list.descriptor

    override fun deserialize(decoder: Decoder): List<T> {
        val json = decoder as JsonDecoder
        return json.decodeJsonElement().jsonArray.mapNotNull {
            try {
                json.json.decodeFromJsonElement(item, it)
            } catch (ignored: IllegalArgumentException) { // SerializationException included
                null
            }
        }
    }

    override fun serialize(encoder: Encoder, value: List<T>) = list.serialize(encoder, value)
}

/** Who is asking, the [token] and (during playback) the [session], as Plex's X-Plex-* headers; blank ones left out. */
internal fun plexHeaders(device: PlexDevice, token: String?, session: String? = null): Map<String, String> = listOfNotNull(
    "X-Plex-Client-Identifier" to device.clientId,
    "X-Plex-Product" to device.product,
    "X-Plex-Version" to device.version,
    "X-Plex-Platform" to device.platform,
    "X-Plex-Platform-Version" to device.platformVersion,
    "X-Plex-Device" to device.device,
    "X-Plex-Device-Name" to device.deviceName,
    token?.let { "X-Plex-Token" to it },
    session?.let { "X-Plex-Session-Identifier" to it },
).filter { it.second.isNotEmpty() }.toMap()

/** A request for JSON, with [headers]. */
internal fun plexRequest(method: String, url: HttpUrl, headers: Map<String, String>): Request = Request.Builder().url(url)
    .method(method, if (method == "GET") null else ByteArray(0).toRequestBody())
    .headers(
        Headers.Builder().add("Accept", "application/json").apply {
            // A device's own name ("Télé du salon") isn't ASCII, which OkHttp otherwise refuses.
            headers.forEach { (name, value) -> addUnsafeNonAscii(name, value) }
        }.build()
    )
    .build()

/** [address] as the base of calls, or a [Reason.INVALID_ADDRESS] failure. */
internal fun plexBase(address: String): HttpUrl = address.trim().trimEnd('/').toHttpUrlOrNull()
    ?: throw PlexException("Invalid address: $address", reason = Reason.INVALID_ADDRESS)

/** [path] under this address, with [query]. */
internal fun HttpUrl.at(path: String, query: HttpUrl.Builder.() -> Unit = {}): HttpUrl =
    newBuilder().addPathSegments(path).apply(query).build()

/** [method] [url] with [headers], its JSON reply read as [T]. */
@OptIn(ExperimentalSerializationApi::class)
internal suspend inline fun <reified T> OkHttpClient.fetch(method: String, url: HttpUrl, headers: Map<String, String>): T =
    execute(plexRequest(method, url, headers)) { plexJson.decodeFromStream<T>(it) }

/**
 * Runs [request] and reads its body as a stream (library pages can be large); maps every failure. Cancelling the
 * coroutine cancels the call, while reading too.
 */
@Suppress("ThrowsCount") // each failure gets its own reason
internal suspend fun <T> OkHttpClient.execute(request: Request, read: (InputStream) -> T): T = coroutineScope {
    val call = newCall(request)
    // Cancels the call as soon as this coroutine is cancelled (closing its socket ends a read that's waiting).
    val watcher = launch(start = CoroutineStart.UNDISPATCHED) {
        try {
            awaitCancellation()
        } finally {
            call.cancel()
        }
    }
    try {
        val response = try {
            call.await()
        } catch (e: IOException) {
            throw PlexException("Couldn't reach the server: ${e.message}", e, Reason.UNREACHABLE)
        }
        response.use {
            withContext(Dispatchers.IO) {
                when {
                    it.code == HTTP_UNAUTHORIZED -> throw PlexException(
                        "The server refused the token",
                        reason = Reason.UNAUTHORIZED,
                        code = it.code
                    )
                    !it.isSuccessful -> throw PlexException(
                        "Server returned error ${it.code}",
                        reason = Reason.SERVER_ERROR,
                        code = it.code
                    )
                    else -> try {
                        read(it.body?.byteStream() ?: ByteArrayInputStream(ByteArray(0)))
                    } catch (e: IllegalArgumentException) { // SerializationException included
                        throw PlexException("Unexpected response from server", e, Reason.UNEXPECTED_RESPONSE)
                    } catch (e: IOException) { // the body is read as it arrives: the connection can drop mid-way
                        throw PlexException("Couldn't reach the server: ${e.message}", e, Reason.UNREACHABLE)
                    }
                }
            }
        }
    } finally {
        watcher.cancel()
    }
}

/** Runs the call without holding a thread. */
private suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
    enqueue(
        object : Callback {
            override fun onResponse(call: Call, response: Response) = continuation.resume(response) { _, _, _ -> response.close() }

            override fun onFailure(call: Call, e: IOException) = continuation.resumeWithException(e)
        }
    )
}

private const val HTTP_UNAUTHORIZED = 401
