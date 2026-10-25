package com.biskyt.myplexplayer.testutil

import com.biskyt.myplexplayer.AppContainer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonArrayBuilder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest

/**
 * plex.tv and a Plex Media Server in one, answering as the real ones did in the spikes (docs/plex-api.md): sign-in
 * with code ABCD (entered once [claimed] is set), an account with two servers (Home Plex, which answers, and
 * Friend's Plex, shared and out of reach), and Home Plex's film and TV libraries.
 */
class FakePlex : AutoCloseable {
    private val server = MockWebServer()

    /** Whether code ABCD has been entered at plex.tv/link. */
    @Volatile var claimed = false

    /** Whether plex.tv still accepts the account's token (false: signed out on plex.tv). */
    @Volatile var tokenAccepted = true

    /** How long each code lasts, in seconds. */
    @Volatile var codeSeconds = 900

    /** Whether Home Plex's libraries answer (false: it fails with error 500). */
    @Volatile var libraryUp = true

    /** The film library's name; null when it has been deleted. */
    @Volatile var filmLibrary: String? = "Films"

    /** How many more times checking the code fails (plex.tv not answering) before it answers again. */
    @Volatile var codeCheckFailures = 0

    /** Play state, by ratingKey: how far in (ms), when last played (s), and the watched ones. */
    val viewOffset: MutableMap<String, Long> = java.util.concurrent.ConcurrentHashMap()
    val lastViewedAt: MutableMap<String, Long> = java.util.concurrent.ConcurrentHashMap()
    val watched: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    /** Continue Watching, newest first. */
    @Volatile var continueWatching: List<String> = emptyList()

    /** Whether the films' files are in a container no player reads (so they're streamed by the server instead). */
    @Volatile var unreadableFiles = false

    /** Whether the server would stream a file with its picture and sound as they are (false: only by converting). */
    @Volatile var copies = true

    /** The sessions a direct stream was started for (its playlist asked for), and those stopped. */
    val streamed: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf())
    val stopped: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf())

    /** The progress reports (timeline) received: ratingKey, state, time and session. */
    val reports: MutableList<List<String?>> = java.util.Collections.synchronizedList(mutableListOf())

    val url: String get() = server.url("/").toString().trimEnd('/')

    /** Whether it has Live TV (a DVR and its guide), with [CHANNELS]. */
    @Volatile var liveTv = true

    /** How long a title's details take to come (a slow server). */
    @Volatile var metadataDelayMs = 0L

    /** Live TV failing for now (500), as a server still starting up might. */
    @Volatile var liveTvFails = false

    /** Live TV refused (403), as a friend's server does to a user it's shared with. */
    @Volatile var liveTvRefused = false

    /** The shows by newest episode (Home's recently added series) failing (500), the library still listing. */
    @Volatile var newestFails = false

    /** The guide failing (500), its channels still answering. */
    @Volatile var guideFails = false

    /** The guide sent in a shape the app can't read (each showing's times as words). */
    @Volatile var guideUnreadable = false

    /** Tuning answers 500 ("Tuning failed"). */
    @Volatile var tuneFails = false

    /** The channels tuned (asked for, failed or not), in order, and the live sessions stopped (their paths): each tune takes a tuner till then. */
    val tuned: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf())
    val liveStopped: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf())

    /** How many requests it has had. */
    val requestCount: Int get() = server.requestCount

    init {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = answer(request)
        }
        server.start()
    }

    override fun close() = server.shutdown()

    /** The TV library's shows (by newest episode, failing when [newestFails]). */
    private fun shows(request: RecordedRequest): MockResponse =
        if (newestFails && request.requestUrl!!.queryParameter("sort") == "episode.addedAt:desc") {
            MockResponse().setResponseCode(500)
        } else {
            page(request, SHOWS.filter { viewedSince(request, it) }.map(::withState))
        }

    private fun answer(request: RecordedRequest): MockResponse {
        val path = request.requestUrl!!.encodedPath
        liveTvAnswer(path, request)?.let { return it }
        val token = request.getHeader("X-Plex-Token") ?: request.requestUrl!!.queryParameter("X-Plex-Token")
        return when {
            path == "/api/v2/pins" && request.method == "POST" -> json(pin(), code = 201)
            path == "/api/v2/pins/$PIN_ID" && codeCheckFailures > 0 -> MockResponse().setResponseCode(503).also { codeCheckFailures-- }
            path == "/api/v2/pins/$PIN_ID" -> json(pin())
            path == "/api/v2/resources" && token == ACCOUNT_TOKEN && tokenAccepted -> json(resources())
            path == "/api/v2/resources" -> MockResponse().setResponseCode(401)
            // Friend's Plex: not reachable from here.
            path.startsWith("/friend/") -> MockResponse().setResponseCode(404)
            token != SERVER_TOKEN -> MockResponse().setResponseCode(401)
            !libraryUp && path.startsWith("/library/") -> MockResponse().setResponseCode(500)
            path == "/identity" -> container { put("machineIdentifier", MACHINE_ID) }
            path == "/library/sections" -> container {
                putJsonArray("Directory") {
                    filmLibrary?.let { add(section("1", "movie", it)) }
                    add(section("2", "show", "TV Programmes"))
                }
            }
            path == "/library/sections/1/all" -> page(request, MOVIES.filter { viewedSince(request, it) }.map(::withState))
            path == "/library/sections/2/all" -> shows(request)
            path == "/library/metadata/21/allLeaves" -> container { put("Metadata", JsonArray(EPISODES.map(::withState))) }
            // One item, or several ("11,12"): those it has.
            path.startsWith("/library/metadata/") -> path.substringAfterLast('/').split(',').mapNotNull(::byKey)
                .takeIf { it.isNotEmpty() }?.let { items -> container { put("Metadata", JsonArray(items.map(::withState))) } }
                ?.setBodyDelay(metadataDelayMs, java.util.concurrent.TimeUnit.MILLISECONDS)
                ?: MockResponse().setResponseCode(404)
            path == "/hubs/continueWatching" -> {
                val items = continueWatching.map { withState(byKey(it)!!) }
                container { putJsonArray("Hub") { add(obj { put("Metadata", JsonArray(items)) }) } }
            }
            path == "/:/timeline" -> timeline(request)
            path == "/:/scrobble" || path == "/:/unscrobble" -> mark(request.requestUrl!!.queryParameter("key")!!, path == "/:/scrobble")
            path == "/actions/removeFromContinueWatching" -> {
                continueWatching = continueWatching - request.requestUrl!!.queryParameter("ratingKey").orEmpty()
                MockResponse()
            }
            path == "/photo/:/transcode" -> MockResponse().setBody("")
            path.startsWith("/library/parts/") && unreadableFiles -> MockResponse().setBody("not a container anyone reads")
            path == "/video/:/transcode/universal/decision" -> decision()
            // Reached only with the server's token (above), in a header now rather than the address.
            // A live session's stream: a tuner still locking, it doesn't answer yet.
            path == "/video/:/transcode/universal/start.m3u8" &&
                request.requestUrl!!.queryParameter("path").orEmpty().startsWith("/livetv/sessions/") ->
                MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.NO_RESPONSE)
            path == "/video/:/transcode/universal/start.m3u8" -> {
                streamed += request.requestUrl!!.queryParameter("session").orEmpty()
                MockResponse().setResponseCode(404)
            }
            path == "/video/:/transcode/universal/stop" -> {
                stopped += request.requestUrl!!.queryParameter("session").orEmpty()
                MockResponse()
            }
            else -> MockResponse().setResponseCode(404)
        }
    }

    /** Live TV: the DVR, its guide's channels and grid, tuning, and live sessions' reports; null for anything else. */
    private fun liveTvAnswer(path: String, request: RecordedRequest): MockResponse? = when {
        path == "/livetv/dvrs" && liveTvRefused -> MockResponse().setResponseCode(403)
        path == "/livetv/dvrs" && liveTvFails -> MockResponse().setResponseCode(500)
        path == "/livetv/dvrs" -> container { if (liveTv) putJsonArray("Dvr") { add(obj { put("key", DVR) }) } }
        path == "/media/providers" -> container {
            putJsonArray("MediaProvider") {
                if (liveTv) {
                    add(
                        obj {
                            put("identifier", EPG)
                            put("protocols", "livetv")
                            putJsonArray("Feature") {
                                add(
                                    obj {
                                        put("type", "grid")
                                        put("key", "/$EPG/grid")
                                    }
                                )
                            }
                        }
                    )
                }
            }
        }
        path == "/$EPG/lineups/dvr/channels" -> container { put("Channel", JsonArray(CHANNELS)) }
        path == "/$EPG/grid" && guideFails -> MockResponse().setResponseCode(500)
        path == "/$EPG/grid" && guideUnreadable -> container {
            put("size", 1)
            putJsonArray("Metadata") {
                add(
                    obj {
                        put("title", "Odd")
                        putJsonArray("Media") { add(obj { put("beginsAt", "soon") }) }
                    }
                )
            }
        }
        path == "/$EPG/grid" -> container { put("Metadata", JsonArray(guide(request))) }
        path.startsWith("/livetv/dvrs/$DVR/channels/") && path.endsWith("/tune") -> tune(path.split('/')[5])
        path == "/:/timeline" && request.requestUrl!!.queryParameter("key").orEmpty().startsWith("/livetv/sessions/") -> {
            val url = request.requestUrl!!
            if (url.queryParameter("state") == "stopped") liveStopped += url.queryParameter("key")!!
            MockResponse()
        }
        else -> null
    }

    /** A tune: what's on, with its live session's path (in a grab, as the owner's server answers). */
    private fun tune(channel: String): MockResponse {
        tuned += channel
        if (tuneFails) return MockResponse().setResponseCode(500)
        return container {
            putJsonArray("MediaSubscription") {
                add(
                    obj {
                        putJsonArray("MediaGrabOperation") {
                            add(
                                obj {
                                    put("status", "inprogress")
                                    putJsonObject("Metadata") {
                                        put("ratingKey", "plex://episode/$channel")
                                        put("key", "/livetv/sessions/${tuned.size}-$channel")
                                        put("duration", 3_600_000)
                                    }
                                }
                            )
                        }
                    }
                )
            }
        }
    }

    /** When the guide's first programmes began: fixed, as a server's guide is, whenever it's asked for. */
    private val guideStart = System.currentTimeMillis() / 1000 - 1800

    /**
     * Each channel's programme on when this began (half an hour in) and the one after, those on in the window asked
     * for (`endsAt>=` to `beginsAt<=`, in seconds), as the server answers.
     */
    private fun guide(request: RecordedRequest): List<JsonObject> {
        val url = request.requestUrl!!
        val from = url.queryParameter("endsAt>")?.toLong() ?: 0
        val to = url.queryParameter("beginsAt<")?.toLong() ?: Long.MAX_VALUE
        val hour = guideStart
        return CHANNELS.mapIndexed { i, ch -> ch.string("id")!! to i }.flatMap { (id, i) ->
            listOf(0, 1).filter { n -> hour + (n + 1) * 3600 >= from && hour + n * 3600 <= to }.map { n ->
                obj {
                    put("ratingKey", "plex://programme/$id-$n")
                    put("type", if (n == 0) "episode" else "movie")
                    put("title", if (n == 0) "Episode $i" else "Film $i")
                    if (n == 0) put("grandparentTitle", "Show $i")
                    put("summary", "About it.")
                    putJsonArray("Media") {
                        add(
                            obj {
                                put("beginsAt", hour + n * 3600)
                                put("endsAt", hour + (n + 1) * 3600)
                                put("channelIdentifier", id)
                                // The rest as the owner's server sends them (numbers among them).
                                put("id", 100 + i * 2 + n)
                                put("channelID", i + 1)
                                put("channelVcn", "00$i")
                                put("gridKey", id.substringAfter('-'))
                            }
                        )
                    }
                    // Tags, as the owner's server sends them: a number and a name, not the channel's key.
                    putJsonArray("Channel") {
                        add(
                            obj {
                                put("id", i + 1)
                                put("tag", "00$i Channel")
                            }
                        )
                    }
                }
            }
        }
    }

    /** The server's decision on a direct stream: picture and sound copied, or (not [copies]) the picture converted. */
    private fun decision() = container {
        putJsonArray("Metadata") {
            add(
                obj {
                    put("ratingKey", "11")
                    put(
                        "Media",
                        media(111, "mp4") {
                            add(obj { stream(1, "h264", if (copies) "copy" else "transcode") })
                            add(obj { stream(2, "aac", "copy") })
                        }
                    )
                }
            )
        }
    }

    private fun JsonObjectBuilder.stream(type: Int, codec: String, decision: String) {
        put("streamType", type)
        put("codec", codec)
        put("decision", decision)
    }

    /**
     * Marks [key] (a film, an episode, or a show: its episodes) [watched] or not, as the server does: either way its
     * resume point goes, and a watched one leaves Continue Watching.
     */
    fun mark(key: String, watched: Boolean): MockResponse {
        val keys = if (byKey(key)?.string("type") == "show") EPISODES.map { it.string("ratingKey")!! } else listOf(key)
        keys.forEach { k ->
            viewOffset -= k
            // As the server does: watched counts as played now; unwatched, it was never played.
            if (watched) this.watched += k else this.watched -= k
            if (watched) lastViewedAt[k] = System.currentTimeMillis() / 1000 else lastViewedAt -= k
        }
        continueWatching = continueWatching - keys // watched, it's done; unwatched, there's nothing to carry on from
        return MockResponse()
    }

    /**
     * Whether [item] passes the listing's "played in the last N seconds" filter, if it has one: a film by when it was
     * last played, a show by its episodes.
     */
    private fun viewedSince(request: RecordedRequest, item: JsonObject): Boolean {
        val url = request.requestUrl!!
        val seconds = (url.queryParameter("lastViewedAt>>") ?: url.queryParameter("episode.lastViewedAt>>"))
            ?.removePrefix("-")?.toLong() ?: return true
        val keys = if (item.string("type") == "show") EPISODES.map { it.string("ratingKey")!! } else listOf(item.string("ratingKey")!!)
        val after = System.currentTimeMillis() / 1000 - seconds
        return keys.any { (lastViewedAt[it] ?: return@any false) >= after }
    }

    private fun byKey(key: String) = (MOVIES + SHOWS + EPISODES).firstOrNull { it.string("ratingKey") == key }

    /** [item] with its play state, as the server lists it (a show: its episodes, and how many are watched). */
    private fun withState(item: JsonObject): JsonObject {
        val key = item.string("ratingKey")!!
        return JsonObject(
            item + buildMap {
                viewOffset[key]?.let { put("viewOffset", JsonPrimitive(it)) }
                lastViewedAt[key]?.let { put("lastViewedAt", JsonPrimitive(it)) }
                if (key in watched) put("viewCount", JsonPrimitive(1))
                if (item.string("type") == "show") {
                    put("leafCount", JsonPrimitive(EPISODES.size))
                    put("viewedLeafCount", JsonPrimitive(EPISODES.count { it.string("ratingKey") in watched }))
                }
            }
        )
    }

    /**
     * A progress report: kept, and as the server does, the resume point saved (and the title first on Continue
     * Watching, in place of its series' other episode), or watched once past 90%.
     */
    private fun timeline(request: RecordedRequest): MockResponse {
        val url = request.requestUrl!!
        val key = url.queryParameter("ratingKey")!!
        val time = url.queryParameter("time")!!.toLong()
        reports += listOf(key, url.queryParameter("state"), time.toString(), request.getHeader("X-Plex-Session-Identifier"))
        if (time >= url.queryParameter("duration")!!.toLong() * 9 / 10) {
            watched += key
            viewOffset -= key
        } else {
            viewOffset[key] = time
            val show = byKey(key)?.string("grandparentRatingKey")
            continueWatching =
                listOf(key) + continueWatching.filter { it != key && (show == null || byKey(it)?.string("grandparentRatingKey") != show) }
        }
        lastViewedAt[key] = System.currentTimeMillis() / 1000
        return MockResponse()
    }

    private fun section(key: String, type: String, title: String) = obj {
        put("key", key)
        put("type", type)
        put("title", title)
    }

    private fun pin() = obj {
        put("id", PIN_ID)
        put("code", "ABCD")
        put("expiresIn", codeSeconds)
        if (claimed) put("authToken", ACCOUNT_TOKEN)
    }

    private fun resources() = buildJsonArray {
        add(resource("Home Plex", MACHINE_ID, owned = true, url))
        add(resource("Friend's Plex", "friend-plex", owned = false, "$url/friend"))
        add(
            obj {
                put("name", "Phone")
                put("clientIdentifier", "phone")
                put("provides", "client,player")
            }
        )
    }

    private fun resource(name: String, id: String, owned: Boolean, uri: String) = obj {
        put("name", name)
        put("clientIdentifier", id)
        put("provides", "server")
        put("owned", owned)
        put("accessToken", SERVER_TOKEN)
        putJsonArray("connections") {
            add(
                obj {
                    put("uri", uri)
                    put("local", true)
                    put("relay", false)
                }
            )
        }
    }

    /** [items] from X-Plex-Container-Start, X-Plex-Container-Size at a time, with the total. */
    private fun page(request: RecordedRequest, items: List<JsonObject>): MockResponse {
        val url = request.requestUrl!!
        val start = url.queryParameter("X-Plex-Container-Start")?.toInt() ?: 0
        val size = url.queryParameter("X-Plex-Container-Size")?.toInt() ?: items.size
        val slice = items.drop(start).take(size)
        return container {
            put("offset", start)
            put("size", slice.size)
            put("totalSize", items.size)
            put("Metadata", JsonArray(slice))
        }
    }

    private fun container(body: JsonObjectBuilder.() -> Unit) = json(buildJsonObject { putJsonObject("MediaContainer", body) })

    private fun json(body: Any, code: Int = 200) = MockResponse().setResponseCode(code)
        .setHeader("Content-Type", "application/json").setBody(body.toString())

    companion object {
        const val PIN_ID = 1001L
        const val ACCOUNT_TOKEN = "account-token"
        const val SERVER_TOKEN = "server-token"
        const val MACHINE_ID = "home-plex"
        private const val DVR = "7"
        private const val EPG = "tv.plex.providers.epg.cloud:4"

        /** The tuner's channels, by their key on the server. */
        val CHANNELS = listOf("One" to "1", "Two" to "2").map { (name, number) ->
            obj {
                put("id", "ch-$number")
                put("vcn", number)
                put("title", name)
            }
        }

        private fun obj(body: JsonObjectBuilder.() -> Unit) = buildJsonObject(body)

        private fun JsonObject.string(key: String) = (this[key] as? JsonPrimitive)?.content

        /** A file's Media, its one Part with [streams] when given (as in the server's decisions). */
        private fun media(id: Int, container: String, streams: (JsonArrayBuilder.() -> Unit)? = null) = buildJsonArray {
            add(
                obj {
                    put("container", container)
                    putJsonArray("Part") {
                        add(
                            obj {
                                put("id", id)
                                put("key", "/library/parts/$id/1/file.$container")
                                put("container", container)
                                streams?.let { putJsonArray("Stream", it) }
                            }
                        )
                    }
                }
            )
        }

        private val MOVIES = listOf(
            obj {
                put("ratingKey", "11")
                put("type", "movie")
                put("title", "Big Buck Bunny")
                put("year", 2008)
                put("summary", "A giant rabbit takes on three bullies.")
                put("thumb", "/library/metadata/11/thumb/1")
                put("art", "/library/metadata/11/art/1")
                put("duration", 596_000)
                put("addedAt", 1_700_000_000)
                put("Media", media(111, "mp4"))
            },
            obj {
                put("ratingKey", "12")
                put("type", "movie")
                put("title", "Sintel")
                put("year", 2010)
                put("duration", 888_000)
                put("addedAt", 1_700_000_100)
                put("Media", media(121, "mkv"))
            },
        )

        private val SHOWS = listOf(
            obj {
                put("ratingKey", "21")
                put("type", "show")
                put("title", "Test Show")
                put("year", 2020)
                put("summary", "Two episodes of colour bars.")
                put("addedAt", 1_700_000_200)
            },
        )

        private val EPISODES = listOf(1, 2).map { n ->
            obj {
                put("ratingKey", "21$n")
                put("type", "episode")
                put("title", if (n == 1) "Pilot" else "Second")
                put("parentIndex", 1)
                put("index", n)
                put("grandparentRatingKey", "21")
                put("grandparentTitle", "Test Show")
                put("duration", 600_000)
                put("Media", media(2100 + n, "mkv"))
            }
        }
    }
}

/** Signs in to [plex] (as if code ABCD had been entered) and adds its servers: the id of Home Plex's source. */
suspend fun AppContainer.addHomePlex(plex: FakePlex): Long {
    this.plex.plexTv = plex.url
    plex.claimed = true
    this.plex.signIn(FakePlex.ACCOUNT_TOKEN)
    return db.playlists().all().first { it.machineId == FakePlex.MACHINE_ID }.id
}
