package com.biskyt.myplexplayer.core.plex

import com.biskyt.myplexplayer.core.plex.PlexException.Reason
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * A failure talking to a Plex server. [message] is in English, for logs; [reason] (with [code]) lets the app tell
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

/**
 * What the player opens: [url], with [headers] sent on every request it makes, the HLS playlists and segments
 * included (their addresses are relative, so a token in [url]'s query wouldn't reach them). Built at play time and
 * never stored: the headers hold the token.
 */
data class PlexStream(val url: String, val headers: Map<String, String>) {
    /** Without the headers' values: they hold the token, and this may end up in a log. */
    override fun toString() = "PlexStream($url, headers=${headers.keys})"
}

/**
 * One Plex Media Server's libraries, home rows, search, playback and play progress. Every call here was proven
 * against a real server first (`spikes/plex_spike.py`; the findings are in docs/plex-api.md).
 *
 * Playback never transcodes: the owner's server (a low-powered machine) can't. Files play as they are ([directPlay]), or,
 * when the player can't read the file's container, the server repackages it without touching the audio or video
 * ([directStream]).
 */
class PlexClient(private val http: OkHttpClient, private val server: PlexServer, private val device: PlexDevice) {

    private val base: HttpUrl = server.baseUrl.trim().trimEnd('/').toHttpUrlOrNull()
        ?: throw PlexException("Invalid server address: ${server.baseUrl}", reason = Reason.INVALID_ADDRESS)

    /** The server's machine identifier, which stays the same across addresses. */
    suspend fun identity(): String = container(url("identity")).machineIdentifier
        ?: throw PlexException("No machine identifier in /identity", reason = Reason.UNEXPECTED_RESPONSE)

    suspend fun sections(): List<PlexSection> = container(url("library/sections")).directories

    /** [size] items of a library from [start], of one [type] (films or shows), sorted by [sort] (e.g. "titleSort"). */
    suspend fun page(section: String, type: PlexType, start: Int, size: Int, sort: String? = null): PlexPage {
        val container = container(
            url("library/sections/$section/all") {
                addQueryParameter("type", type.number.toString())
                if (sort != null) addQueryParameter("sort", sort)
                addQueryParameter("X-Plex-Container-Start", start.toString())
                addQueryParameter("X-Plex-Container-Size", size.toString())
            }
        )
        return PlexPage(container.items, container.offset, container.totalSize)
    }

    /**
     * A library's newest [count] films, or its shows with the newest episodes first. Sorting the library is used
     * rather than the recentlyAdded hubs: those give seasons rather than shows, and miss films in a library with
     * no metadata agent.
     */
    suspend fun recentlyAdded(section: String, type: PlexType, count: Int): List<PlexItem> =
        page(section, type, 0, count, if (type == PlexType.SHOW) "episode.addedAt:desc" else "addedAt:desc").items

    suspend fun item(ratingKey: String): PlexItem = container(url("library/metadata/$ratingKey")).items.firstOrNull()
        ?: throw PlexException("No item $ratingKey", reason = Reason.UNEXPECTED_RESPONSE)

    /** A show's seasons, or a season's episodes. */
    suspend fun children(ratingKey: String): List<PlexItem> = container(url("library/metadata/$ratingKey/children")).items

    /** Every episode of a show, in order. */
    suspend fun episodes(showRatingKey: String): List<PlexItem> = container(url("library/metadata/$showRatingKey/allLeaves")).items

    /** Films and episodes part-watched, newest first; one episode per show. */
    suspend fun continueWatching(): List<PlexItem> = container(url("hubs/continueWatching")).let { c ->
        c.items + c.hubs.flatMap { it.items }
    }

    /** Films, shows and episodes matching [query] (at most [limit] of each). */
    suspend fun search(query: String, limit: Int = 20): List<PlexItem> = container(
        url("hubs/search") {
            addQueryParameter("query", query)
            addQueryParameter("limit", limit.toString())
        }
    ).hubs.filter { it.type in SEARCHED }.flatMap { it.items }

    /**
     * Reports playback of [item] at [timeMs], every 10 seconds while playing and whenever the state changes, all
     * with one [session] id per playback. This saves the resume point (after the first minute: the server ignores
     * earlier ones), and the server marks the item watched itself once it plays on past its threshold (90%), so
     * there's no [setWatched] after playing. [item] needs its duration: without it every report would read as
     * played to the end, which the server takes as watched.
     */
    suspend fun timeline(item: PlexItem, state: PlexPlayState, timeMs: Long, session: String) {
        val known = requireNotNull(item.duration) { "timeline needs the item's duration" }
        val duration = maxOf(known, timeMs) // past the end (a programme overrunning): a shorter one is refused (400)
        send(
            "POST",
            url(":/timeline") {
                addQueryParameter("ratingKey", item.ratingKey)
                addQueryParameter("key", "/library/metadata/${item.ratingKey}")
                addQueryParameter("state", state.value)
                addQueryParameter("time", timeMs.toString())
                addQueryParameter("duration", duration.toString())
            },
            session
        )
    }

    /** "Mark as watched / unwatched". Unwatched also clears the resume point. */
    suspend fun setWatched(ratingKey: String, watched: Boolean) = send(
        "PUT",
        url(if (watched) ":/scrobble" else ":/unscrobble") {
            addQueryParameter("identifier", "com.plexapp.plugins.library")
            addQueryParameter("key", ratingKey)
        }
    )

    /** The file as it is: the first choice for every film and episode. */
    fun directPlay(part: PlexPart): PlexStream = PlexStream(url(part.key.trimStart('/')).toString(), headers(null))

    /**
     * The file repackaged as HLS from [offsetMs], with its audio and video untouched, for a file whose container
     * the player can't read. The server first decides how for this [session] (it refuses the stream without that);
     * if it would have to re-encode anything, this throws [Reason.NEEDS_TRANSCODE] instead.
     *
     * The request names Plex's empty "Generic" profile plus the codecs the app plays ([codecs]): a platform
     * profile such as Android's has limits (1080p, stereo AAC) that make the server re-encode.
     */
    suspend fun directStream(ratingKey: String, session: String, offsetMs: Long = 0, codecs: PlexCodecs = PlexCodecs()): PlexStream {
        val headers = headers(session) + mapOf(
            "X-Plex-Client-Profile-Name" to "Generic",
            "X-Plex-Client-Profile-Extra" to codecs.profileExtra(),
        )
        val decision = container(directStreamUrl("decision", ratingKey, session, offsetMs), headers)
        refusal(decision)?.let { refused ->
            runCatching { stopStream(session) } // the decision opened a session on the server: close it
            throw refused
        }
        return PlexStream(directStreamUrl("start.m3u8", ratingKey, session, offsetMs).toString(), headers)
    }

    /**
     * Why [decision] can't be played as a direct stream, or null if it can: every picture and sound stream copied
     * as it is. Not "transcode", not "ignore" (a video stream the server can neither copy nor, with this profile,
     * convert is dropped, leaving the sound alone), and no stream of any kind "burn"t into the picture.
     */
    private fun refusal(decision: PlexContainer): PlexException? {
        if ((decision.generalDecisionCode ?: 0) >= CANNOT_STREAM) {
            return PlexException("The server can't stream this: ${decision.generalDecisionText}", reason = Reason.CANNOT_STREAM)
        }
        val streams = decision.items.flatMap { it.media }.flatMap { it.parts }.flatMap { it.streams }
        if (streams.isEmpty()) {
            return PlexException("No streams in the server's decision", reason = Reason.UNEXPECTED_RESPONSE)
        }
        val notCopied = streams.filter {
            (it.streamType == VIDEO || it.streamType == AUDIO) && it.decision != "copy" || it.decision in REENCODES
        }
        if (notCopied.isEmpty() && streams.any { it.streamType == VIDEO }) return null
        val why = notCopied.joinToString { "${it.codec}: ${it.decision}" }.ifEmpty { "no video stream" }
        return PlexException("The server can't stream this as it is: $why", reason = Reason.NEEDS_TRANSCODE)
    }

    /** Ends a [directStream] on the server. */
    suspend fun stopStream(session: String) =
        send("GET", url("video/:/transcode/universal/stop") { addQueryParameter("session", session) }, session)

    private fun directStreamUrl(call: String, ratingKey: String, session: String, offsetMs: Long): HttpUrl =
        url("video/:/transcode/universal/$call") {
            addQueryParameter("path", "/library/metadata/$ratingKey")
            addQueryParameter("mediaIndex", "0")
            addQueryParameter("partIndex", "0")
            addQueryParameter("protocol", "hls")
            addQueryParameter("directPlay", "0")
            addQueryParameter("directStream", "1")
            addQueryParameter("directStreamAudio", "1")
            addQueryParameter("subtitles", "none") // "auto" may burn subtitles into the picture, a re-encode
            addQueryParameter("offset", (offsetMs / 1000).toString())
            addQueryParameter("session", session)
        }

    /**
     * [path] (a `thumb` or `art`) scaled on the server to fit [width] x [height]. The token goes in the address:
     * the image loader sends no headers of its own.
     */
    fun imageUrl(path: String, width: Int, height: Int): String = url("photo/:/transcode") {
        addQueryParameter("url", path)
        addQueryParameter("width", width.toString())
        addQueryParameter("height", height.toString())
        addQueryParameter("minSize", "1")
        addQueryParameter("upscale", "1")
        server.token?.let { addQueryParameter("X-Plex-Token", it) }
    }.toString()

    private fun url(path: String, query: HttpUrl.Builder.() -> Unit = {}): HttpUrl =
        base.newBuilder().addPathSegments(path).apply(query).build()

    /** Who is asking, the token and (during playback) the session, as Plex's X-Plex-* headers. */
    private fun headers(session: String?): Map<String, String> = listOfNotNull(
        "X-Plex-Client-Identifier" to device.clientId,
        "X-Plex-Product" to device.product,
        "X-Plex-Version" to device.version,
        "X-Plex-Platform" to device.platform,
        "X-Plex-Platform-Version" to device.platformVersion,
        "X-Plex-Device" to device.device,
        "X-Plex-Device-Name" to device.deviceName,
        server.token?.let { "X-Plex-Token" to it },
        session?.let { "X-Plex-Session-Identifier" to it },
    ).filter { it.second.isNotEmpty() }.toMap()

    private fun request(method: String, url: HttpUrl, headers: Map<String, String>): Request = Request.Builder().url(url)
        .method(method, if (method == "GET") null else ByteArray(0).toRequestBody())
        .headers(
            Headers.Builder().add("Accept", "application/json").apply {
                // A device's own name ("Télé du salon") isn't ASCII, which OkHttp otherwise refuses.
                headers.forEach { (name, value) -> addUnsafeNonAscii(name, value) }
            }.build()
        )
        .build()

    private suspend fun container(url: HttpUrl, headers: Map<String, String> = headers(null)): PlexContainer = withContext(Dispatchers.IO) {
        execute(request("GET", url, headers)) { json.decodeFromStream<PlexResponse>(it).container }
    }

    private suspend fun send(method: String, url: HttpUrl, session: String? = null) {
        withContext(Dispatchers.IO) { execute(request(method, url, headers(session))) {} }
    }

    /** Runs [request] and reads its body as a stream (library pages can be large); maps every failure. */
    @Suppress("ThrowsCount") // each failure gets its own reason
    private inline fun <T> execute(request: Request, read: (InputStream) -> T): T {
        val response = try {
            http.newCall(request).execute()
        } catch (e: IOException) {
            throw PlexException("Couldn't reach the server: ${e.message}", e, Reason.UNREACHABLE)
        }
        return response.use {
            when {
                it.code == HTTP_UNAUTHORIZED -> throw PlexException(
                    "The server refused the token",
                    reason = Reason.UNAUTHORIZED,
                    code = it.code
                )
                !it.isSuccessful -> throw PlexException("Server returned error ${it.code}", reason = Reason.SERVER_ERROR, code = it.code)
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

    @OptIn(ExperimentalSerializationApi::class)
    private companion object {
        const val HTTP_UNAUTHORIZED = 401
        const val CANNOT_STREAM = 2000
        val REENCODES = setOf("transcode", "burn")
        const val VIDEO = 1
        const val AUDIO = 2
        val SEARCHED = setOf("movie", "show", "episode")
        val json = Json { ignoreUnknownKeys = true }
    }
}

/**
 * The codecs the app plays, for a direct stream: the server copies streams in these codecs as they are, into an
 * MPEG-TS HLS stream. The defaults are what Media3 plays with the app's FFmpeg audio decoders; video depends on
 * the device's hardware, so the app narrows [video] to what it can decode.
 */
data class PlexCodecs(
    val video: List<String> = listOf("h264", "hevc", "mpeg2video", "mpeg4", "vp9", "av1"),
    val audio: List<String> = listOf("aac", "ac3", "eac3", "mp3", "mp2", "dts", "truehd", "flac", "opus", "vorbis"),
) {
    internal fun profileExtra(): String = "add-transcode-target(type=videoProfile&context=streaming&protocol=hls" +
        "&container=mpegts&videoCodec=${video.joinToString(",")}&audioCodec=${audio.joinToString(",")})"
}
