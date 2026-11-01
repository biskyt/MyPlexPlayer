package com.biskyt.myplexplayer.testutil

import com.biskyt.myplexplayer.AppContainer
import com.biskyt.myplexplayer.core.plex.PlexRecordingOption
import com.biskyt.myplexplayer.core.plex.PlexRecordingRule
import com.biskyt.myplexplayer.data.PlexSources
import com.biskyt.myplexplayer.data.db.AppDatabase
import com.biskyt.myplexplayer.data.plexChannelId
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonArrayBuilder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
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

    /** A library: its key, type (movie or show) and name. */
    data class Library(val key: String, val type: String, val title: String)

    /** The server's libraries. */
    @Volatile var libraries = listOf(Library("1", "movie", "Films"), Library("2", "show", "TV Programmes"))

    /** The films, shows and episodes (built with [film], [show] and [episode]), as the server lists them. */
    @Volatile var movies: List<JsonObject> = MOVIES

    @Volatile var shows: List<JsonObject> = SHOWS

    @Volatile var episodes: List<JsonObject> = EPISODES

    /** Images by their path on the server (a thumb's), served through the image service; none: an empty reply. */
    @Volatile var images: (String) -> ByteArray? = { null }

    /**
     * [path] (one of [images]) at a full address through the image service, with the server's token: for a channel's
     * logo ([Channel.thumb]), which the app takes only as a full address, as a guide's logos come.
     */
    fun imageAddress(path: String): String = "$url/photo/:/transcode?url=$path&X-Plex-Token=$SERVER_TOKEN"

    /** How many more times checking the code fails (plex.tv not answering) before it answers again. */
    @Volatile var codeCheckFailures = 0

    /** Play state, by ratingKey: how far in (ms), when last played (s), and the watched ones. */
    val viewOffset: MutableMap<String, Long> = java.util.concurrent.ConcurrentHashMap()
    val lastViewedAt: MutableMap<String, Long> = java.util.concurrent.ConcurrentHashMap()
    val watched: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    /** Continue Watching, newest first. */
    @Volatile var continueWatching: List<String> = emptyList()

    /** The server's "Allow media deletion"; and what was deleted (ratingKeys). */
    @Volatile var allowsDeletion = true

    /** How often the server was asked whether it allows deletion. */
    @Volatile var deletionAsked = 0
    val deleted: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf())

    /** Each show's "Delete episodes after playing" (by ratingKey; 0, never, when not set). */
    val autoDelete: MutableMap<String, Int> = java.util.concurrent.ConcurrentHashMap()

    /** Whether the films' files are in a container no player reads (so they're streamed by the server instead). */
    @Volatile var unreadableFiles = false

    /** Whether the films' files come back empty ("OK", with no data), as from a server that no longer has them. */
    @Volatile var emptyFiles = false

    /** Whether the server would stream a file with its picture and sound as they are (false: only by converting). */
    @Volatile var copies = true

    /** The sessions a direct stream was started for (its playlist asked for), and those stopped. */
    val streamed: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf())
    val stopped: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf())

    /** The progress reports (timeline) received: ratingKey, state, time and session. */
    val reports: MutableList<List<String?>> = java.util.Collections.synchronizedList(mutableListOf())

    val url: String get() = server.url("/").toString().trimEnd('/')

    /** A tuner channel: its number, name and logo (a full address: see [imageAddress]); its key is ch-<number>. */
    data class Channel(val number: String, val title: String, val thumb: String? = null) {
        val key: String get() = "ch-$number"
    }

    /** The tuner's channels. */
    @Volatile var channels = listOf(Channel("1", "One"), Channel("2", "Two"))

    /** A showing in the guide: an episode of [show] when it has one, else a film. */
    data class Programme(
        val title: String,
        val show: String? = null,
        val season: Int? = null,
        val episode: Int? = null,
        val summary: String = "About it."
    )

    /**
     * What channel [i] (of [channels]) shows from half an hour ago ([n] 0), for the hour after (1), and for the
     * [laterHours] after that ("Later 1 of <i>"…, an hour each); null: nothing.
     */
    @Volatile var programme: (i: Int, n: Int) -> Programme? = { i, n ->
        when (n) {
            0 -> Programme("Episode $i", show = "Show $i")
            1 -> Programme("Film $i")
            else -> Programme("Later ${n - 1} of $i")
        }
    }

    /** Hours of programmes after the first two (a full guide). */
    @Volatile var laterHours = 0

    /** Minutes each channel's guide is moved by, by channel key (else all start at the same times). */
    @Volatile var guideShiftMin: Map<String, Int> = emptyMap()

    /** Whether it has Live TV (a DVR and its guide), with [channels]. */
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

    /**
     * The account's favourite Live TV channels in Plex, all of them in Plex's order (Plex's guide service keeps them,
     * and a save replaces them): to start with, one on a friend's server, which isn't this source's.
     */
    @Volatile var accountFavourites: List<JsonObject> = listOf(favourite("ch-1", "friend-plex"))

    /** The account's favourites on this server, by channel key: set, they're put before the friend's one. */
    var favouriteChannels: List<String>
        get() = accountFavourites.filter { it.onThisServer() }.map { it.string("id")!! }
        set(keys) {
            accountFavourites = keys.map { favourite(it, MACHINE_ID) } + accountFavourites.filterNot { it.onThisServer() }
        }

    private fun JsonObject.onThisServer() = string("source")!!.contains("//$MACHINE_ID/")

    /** The guide service failing (500), so favourites can't be read or saved. */
    @Volatile var favouritesFail = false

    /** The guide out of reach (the connection dropped), the rest answering. */
    @Volatile var guideUnreachable = false

    /** Tuning answers 500 ("Tuning failed"). */
    @Volatile var tuneFails = false

    /**
     * A live session's stream answering, but with next to nothing (a few bytes a second of a playlist that never
     * ends), never enough to play: it never fails on the read timeout (in real time), so no retry plays it again
     * mid-test. Otherwise it doesn't answer at all, as a tuner still locking.
     */
    @Volatile var liveTrickles = false

    /** The live sessions (their paths) whose stream was asked for, each time it was. */
    val liveStreamed: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf())

    /** The channels tuned (asked for, failed or not), in order, and the live sessions stopped (their paths): each tune takes a tuner till then. */
    val tuned: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf())
    val liveStopped: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf())

    /** How many requests it has had. */
    val requestCount: Int get() = server.requestCount

    /** The paths asked for, in order. */
    val paths: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf())

    /** How long the library list (`/library/sections`) takes to come. Under 5 s: [close] waits that long at most. */
    @Volatile var sectionsDelayMs = 0L

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
            page(request, inLibrary(shows, request).filter { viewedSince(request, it) }.map(::withState))
        }

    private fun answer(request: RecordedRequest): MockResponse {
        val path = request.requestUrl!!.encodedPath
        paths += path
        liveTvAnswer(path, request)?.let { return it }
        val token = request.getHeader("X-Plex-Token") ?: request.requestUrl!!.queryParameter("X-Plex-Token")
        return when {
            path == "/api/v2/pins" && request.method == "POST" -> json(pin(), code = 201)
            path == "/api/v2/pins/$PIN_ID" && codeCheckFailures > 0 -> MockResponse().setResponseCode(503).also { codeCheckFailures-- }
            path == "/api/v2/pins/$PIN_ID" -> json(pin())
            path == "/api/v2/resources" && token == ACCOUNT_TOKEN && tokenAccepted -> json(resources())
            path == "/api/v2/resources" -> MockResponse().setResponseCode(401)
            path == "/settings/favoriteChannels" -> favourites(token, request)
            // Friend's Plex: not reachable from here.
            path.startsWith("/friend/") -> MockResponse().setResponseCode(404)
            token != SERVER_TOKEN -> MockResponse().setResponseCode(401)
            !libraryUp && path.startsWith("/library/") -> MockResponse().setResponseCode(500)
            path == "/identity" -> container { put("machineIdentifier", MACHINE_ID) }
            path == "/" -> container { put("allowMediaDeletion", allowsDeletion) }.also { deletionAsked++ }
            LIBRARY.any(path::startsWith) -> libraryAnswer(path, request)
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
            path == "/photo/:/transcode" ->
                MockResponse().setBody(okio.Buffer().write(request.requestUrl!!.queryParameter("url")?.let(images) ?: ByteArray(0)))
            path == "/video/:/transcode/universal/decision" -> decision()
            // Reached only with the server's token (above), in a header now rather than the address.
            // A live session's stream: a tuner still locking, it doesn't answer yet.
            path == "/video/:/transcode/universal/start.m3u8" &&
                request.requestUrl!!.queryParameter("path").orEmpty().startsWith("/livetv/sessions/") ->
                liveStream(request.requestUrl!!.queryParameter("path")!!)
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

    /** An intro or credits marker, as [episode] lists it. */
    data class Marker(val type: String, val startMs: Long, val endMs: Long, val final: Boolean = false)

    /** The libraries, what's in them, and each title's details. */
    private fun libraryAnswer(path: String, request: RecordedRequest): MockResponse = when {
        // A file: empty, or in a container no player reads (else not served here).
        path.startsWith("/library/parts/") -> when {
            emptyFiles -> MockResponse()
            unreadableFiles -> MockResponse().setBody("not a container anyone reads")
            else -> MockResponse().setResponseCode(404)
        }
        path.startsWith("/library/metadata/") && request.method == "DELETE" -> delete(path.substringAfterLast('/'))
        path.startsWith("/library/metadata/") && path.endsWith("/prefs") && request.method == "PUT" -> {
            val key = path.split('/')[3]
            autoDelete[key] = request.requestUrl!!.queryParameter("autoDeletionItemPolicyWatchedLibrary")!!.toInt()
            MockResponse()
        }
        path == "/library/sections" -> container {
            putJsonArray("Directory") { libraries.forEach { add(section(it.key, it.type, it.title)) } }
        }.setBodyDelay(sectionsDelayMs, java.util.concurrent.TimeUnit.MILLISECONDS)
        path.startsWith("/library/sections/") && path.endsWith("/all") -> when (library(path)?.type) {
            "movie" -> page(request, inLibrary(movies, request).filter { viewedSince(request, it) }.map(::withState))
            "show" -> shows(request)
            else -> MockResponse().setResponseCode(404)
        }
        path.startsWith("/library/metadata/") && path.endsWith("/allLeaves") ->
            container { put("Metadata", JsonArray(episodesOf(path.split('/')[3]).map(::withState))) }
        // One item, or several ("11,12"): those it has.
        path.startsWith("/library/metadata/") -> path.substringAfterLast('/').split(',').mapNotNull(::byKey)
            .takeIf { it.isNotEmpty() }?.let { items -> details(items, request) }
            ?.setBodyDelay(metadataDelayMs, java.util.concurrent.TimeUnit.MILLISECONDS)
            ?: MockResponse().setResponseCode(404)
        else -> MockResponse().setResponseCode(404)
    }

    /**
     * The stream of the live session at [path]: none (a tuner still locking), or with [liveTrickles] a playlist's start,
     * then blank lines a byte at a time.
     */
    private fun liveStream(path: String): MockResponse {
        liveStreamed += path
        if (!liveTrickles) return MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.NO_RESPONSE)
        return MockResponse().setHeader("Content-Type", "application/vnd.apple.mpegurl")
            .setBody(okio.Buffer().writeUtf8("#EXTM3U\n" + "\n".repeat(10_000)))
            .throttleBody(1, 200, java.util.concurrent.TimeUnit.MILLISECONDS)
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
        path == "/$EPG/lineups/dvr/channels" -> container { put("Channel", JsonArray(channels.map(::lineupChannel))) }
        path == "/$EPG/grid" && guideFails -> MockResponse().setResponseCode(500)
        path == "/$EPG/grid" && guideUnreachable -> MockResponse().setSocketPolicy(
            okhttp3.mockwebserver.SocketPolicy.DISCONNECT_AFTER_REQUEST
        )
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
        path.startsWith("/media/subscriptions") || path.startsWith("/media/grabbers/") -> dvrAnswer(path, request)
        path == "/:/timeline" && request.requestUrl!!.queryParameter("key").orEmpty().startsWith("/livetv/sessions/") -> {
            val url = request.requestUrl!!
            if (url.queryParameter("state") == "stopped") liveStopped += url.queryParameter("key")!!
            MockResponse()
        }
        else -> null
    }

    /** A recording rule made here: its key, type (4 an airing, 2 a series), what it records and its settings. */
    data class Rule(val key: String, val type: Int, val guid: String, val prefs: Map<String, String>)

    /** The recording rules, made with Record (each one's airings follow from the guide, see [airings]). */
    val rules: MutableList<Rule> = java.util.Collections.synchronizedList(mutableListOf())

    /** Airings cancelled one at a time (their programme's guid). */
    val cancelledAirings: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    /** Airings recording now (their programme's guid): the server says `inprogress` for them. */
    val recordingNow: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    /** Refuses recording, as a server does for someone who isn't its admin. */
    @Volatile var recordingRefused = false

    private fun programmeGuid(channel: String, n: Int) = "plex://episode/$channel-$n"
    private fun showGuid(show: String) = "plex://show/${show.replace(' ', '-')}"

    /** A showing in the guide, as the DVR answers know it. */
    private data class Showing(
        val guid: String,
        val showGuid: String?,
        val channel: String,
        val begins: Long,
        val ends: Long,
        val title: String,
        val show: String?
    )

    /** Every showing in the guide. */
    private fun showings(): List<Showing> = channels.withIndex().flatMap { (i, ch) ->
        val hour = guideStart + (guideShiftMin[ch.key] ?: 0) * 60
        (0..1 + laterHours).mapNotNull { n ->
            val p = programme(i, n) ?: return@mapNotNull null
            Showing(programmeGuid(ch.key, n), p.show?.let(::showGuid), ch.key, hour + n * 3600, hour + (n + 1) * 3600, p.title, p.show)
        }
    }

    /** What [rule] records: its programme, or every airing of its show; less those cancelled. */
    private fun airings(rule: Rule) = showings().filter {
        (if (rule.type == PlexRecordingOption.SERIES) it.showGuid == rule.guid else it.guid == rule.guid) && it.guid !in cancelledAirings
    }

    private fun grab(rule: Rule, airing: Showing) = obj {
        put("key", "/media/grabbers/operations/g-${airing.guid}")
        put("status", if (airing.guid in recordingNow) "inprogress" else "scheduled")
        put("mediaSubscriptionID", rule.key.toInt()) // a number, as Plex sends it (the rule's key is text)
        putJsonObject("Metadata") {
            put("guid", airing.guid)
            airing.showGuid?.let { put("grandparentGuid", it) }
            put("title", airing.title)
            airing.show?.let { put("grandparentTitle", it) }
            putJsonArray("Media") {
                add(
                    obj {
                        put("beginsAt", airing.begins)
                        put("endsAt", airing.ends)
                        put("channelIdentifier", airing.channel)
                    }
                )
            }
        }
    }

    private fun ruleJson(rule: Rule) = obj {
        put("key", rule.key)
        put("type", rule.type)
        // No settings, as in Plex's list of rules: only the airings it records (docs/plex-api.md, 26).
        val newOnly = rule.prefs[PlexRecordingRule.ONLY_NEW_AIRINGS] == PlexRecordingRule.NEW_ONLY
        put("airingsType", if (newOnly) PlexRecordingRule.NEW_AIRINGS_ONLY else PlexRecordingRule.NEW_AND_REPEAT_AIRINGS)
        val series = rule.type == PlexRecordingOption.SERIES
        // Its title, as Plex sends it: the show's, or the programme's.
        val title = showings().firstOrNull {
            if (series) it.showGuid == rule.guid else it.guid == rule.guid
        }?.let { if (series) it.show else it.title }
        // One object, not a list, as Plex sends it.
        putJsonObject(if (series) "Directory" else "Video") {
            put("guid", rule.guid)
            title?.let { put("title", it) }
        }
    }

    /** Plex's DVR: recording templates, rules (made, listed, removed) and scheduled airings (listed, cancelled). */
    private fun dvrAnswer(path: String, request: RecordedRequest): MockResponse {
        val url = request.requestUrl!!
        return when {
            path == "/media/subscriptions/template" -> {
                val guid = url.queryParameter("guid")!!
                val show = showings().firstOrNull { it.guid == guid }?.showGuid
                container {
                    putJsonArray("SubscriptionTemplate") {
                        add(
                            obj {
                                putJsonArray("MediaSubscription") {
                                    add(template(PlexRecordingOption.EPISODE, guid, oneShot = true))
                                    if (show != null) add(template(PlexRecordingOption.SERIES, show, oneShot = false))
                                }
                            }
                        )
                    }
                }
            }
            path == "/media/subscriptions" && request.method == "POST" -> {
                if (recordingRefused) return MockResponse().setResponseCode(403)
                val guid = url.queryParameter("hints[guid]")!!
                if (rules.any { it.guid == guid }) return MockResponse().setResponseCode(409)
                val prefs = url.queryParameterNames.filter { it.startsWith("prefs[") }
                    .associate { it.removePrefix("prefs[").removeSuffix("]") to url.queryParameter(it).orEmpty() }
                val rule = Rule("${rules.size + 1}", url.queryParameter("type")!!.toInt(), guid, prefs)
                rules += rule
                container { putJsonArray("MediaSubscription") { add(ruleJson(rule)) } }
            }
            path == "/media/subscriptions" -> container {
                putJsonArray("MediaSubscription") { rules.toList().forEach { add(ruleJson(it)) } }
            }
            path == "/media/subscriptions/scheduled" -> container {
                putJsonArray("MediaGrabOperation") { rules.toList().forEach { r -> airings(r).forEach { add(grab(r, it)) } } }
            }
            request.method == "PUT" && path.startsWith("/media/subscriptions/") -> {
                val key = path.substringAfterLast('/')
                val changes = url.queryParameterNames.filter { it.startsWith("prefs[") }
                    .associate { it.removePrefix("prefs[").removeSuffix("]") to url.queryParameter(it).orEmpty() }
                synchronized(rules) { rules.replaceAll { if (it.key == key) it.copy(prefs = it.prefs + changes) else it } }
                MockResponse()
            }
            request.method == "DELETE" && path.startsWith("/media/subscriptions/") -> {
                rules.removeAll { it.key == path.substringAfterLast('/') }
                MockResponse()
            }
            request.method == "DELETE" && path.startsWith("/media/grabbers/operations/g-") -> {
                cancelledAirings += path.substringAfter("/g-")
                MockResponse()
            }
            else -> MockResponse().setResponseCode(404)
        }
    }

    private fun template(type: Int, guid: String, oneShot: Boolean) = obj {
        put("type", type)
        put("targetLibrarySectionID", 2)
        put("parameters", "hints%5Bguid%5D=${java.net.URLEncoder.encode(guid, Charsets.UTF_8)}&params%5BlibraryType%5D=2")
        putJsonArray("Setting") {
            if (type == PlexRecordingOption.SERIES) {
                add(
                    obj {
                        put("id", "onlyNewAirings")
                        put("type", "int")
                        put("default", 0)
                    }
                )
            }
            add(
                obj {
                    put("id", "oneShot")
                    put("type", "bool")
                    put("default", oneShot)
                    put("hidden", true)
                }
            )
        }
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
        return channels.withIndex().flatMap { (i, ch) ->
            val hour = guideStart + (guideShiftMin[ch.key] ?: 0) * 60
            (0..1 + laterHours).filter { n -> hour + (n + 1) * 3600 >= from && hour + n * 3600 <= to }.mapNotNull { n ->
                val p = programme(i, n) ?: return@mapNotNull null
                obj {
                    put("ratingKey", "plex://programme/${ch.key}-$n")
                    put("guid", programmeGuid(ch.key, n))
                    p.show?.let { put("grandparentGuid", showGuid(it)) }
                    put("type", if (p.show != null) "episode" else "movie")
                    put("title", p.title)
                    p.show?.let { put("grandparentTitle", it) }
                    p.season?.let { put("parentIndex", it) }
                    p.episode?.let { put("index", it) }
                    put("summary", p.summary)
                    putJsonArray("Media") {
                        add(
                            obj {
                                put("beginsAt", hour + n * 3600)
                                put("endsAt", hour + (n + 1) * 3600)
                                put("channelIdentifier", ch.key)
                                // The rest as the owner's server sends them (numbers among them).
                                put("id", 100 + i * 2 + n)
                                put("channelID", i + 1)
                                put("channelVcn", "00$i")
                                put("gridKey", ch.number)
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
        val keys = leaves(key)
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
        val keys = leaves(item.string("ratingKey")!!)
        val after = System.currentTimeMillis() / 1000 - seconds
        return keys.any { (lastViewedAt[it] ?: return@any false) >= after }
    }

    private fun details(items: List<JsonObject>, request: RecordedRequest) =
        container { put("Metadata", JsonArray(items.map { withPreferences(withMarkers(withState(it), request), request) })) }

    /** A show's own settings, only when asked for, as from a real server. */
    private fun withPreferences(item: JsonObject, request: RecordedRequest): JsonObject {
        if (request.requestUrl?.queryParameter("includePreferences") != "1" || item.string("type") != "show") return item
        val policy = autoDelete[item.string("ratingKey")] ?: 0
        val setting = obj {
            put("id", "autoDeletionItemPolicyWatchedLibrary")
            put("value", policy.toString())
        }
        return JsonObject(item + ("Preferences" to obj { putJsonArray("Setting") { add(setting) } }))
    }

    /** Deletes a film or episode, where the server allows it. */
    private fun delete(key: String): MockResponse {
        if (!allowsDeletion) return MockResponse().setResponseCode(403)
        if (byKey(key) == null) return MockResponse().setResponseCode(404)
        deleted += key
        val show = byKey(key)?.string("grandparentRatingKey")
        movies = movies.filter { it.string("ratingKey") != key }
        // A show goes with its episodes; and, as on a real server, with its last episode.
        episodes = episodes.filter { it.string("ratingKey") != key && it.string("grandparentRatingKey") != key }
        shows = shows.filter { s ->
            val k = s.string("ratingKey")!!
            k != key && (k != show || episodesOf(k).isNotEmpty())
        }
        return MockResponse()
    }

    /** Markers come only when asked for, as from a real server. */
    private fun withMarkers(item: JsonObject, request: RecordedRequest) =
        if (request.requestUrl?.queryParameter("includeMarkers") == "1") item else JsonObject(item - "Marker")

    private fun byKey(key: String) = (movies + shows + episodes).firstOrNull { it.string("ratingKey") == key }

    private fun episodesOf(show: String) = episodes.filter { it.string("grandparentRatingKey") == show }

    /** What [key] stands for when played or marked: a show's episodes, or the title itself. */
    private fun leaves(key: String) =
        if (byKey(key)?.string("type") == "show") episodesOf(key).map { it.string("ratingKey")!! } else listOf(key)

    /** The library a /library/sections/<key>/... path is in. */
    private fun library(path: String) = libraries.firstOrNull { it.key == path.split('/')[3] }

    /** Those of [items] in the library the request's path names. */
    private fun inLibrary(items: List<JsonObject>, request: RecordedRequest): List<JsonObject> {
        val key = request.requestUrl!!.encodedPath.split('/')[3]
        return items.filter { it.string("librarySectionID") == key }
    }

    private fun lineupChannel(ch: Channel) = obj {
        put("id", ch.key)
        put("vcn", ch.number)
        put("title", ch.title)
        ch.thumb?.let { put("thumb", it) }
    }

    /** [item] with its play state, as the server lists it (a show: its episodes, and how many are watched). */
    private fun withState(item: JsonObject): JsonObject {
        val key = item.string("ratingKey")!!
        return JsonObject(
            item + buildMap {
                viewOffset[key]?.let { put("viewOffset", JsonPrimitive(it)) }
                lastViewedAt[key]?.let { put("lastViewedAt", JsonPrimitive(it)) }
                if (key in watched) put("viewCount", JsonPrimitive(1))
                if (item.string("type") == "show") {
                    val leaves = episodesOf(key)
                    put("leafCount", JsonPrimitive(leaves.size))
                    put("viewedLeafCount", JsonPrimitive(leaves.count { it.string("ratingKey") in watched }))
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

    /** Plex's guide service: the account's favourite channels, read (GET) or saved whole (PUT, a JSON array). */
    private fun favourites(token: String?, request: RecordedRequest) = when {
        favouritesFail -> MockResponse().setResponseCode(500)
        token != ACCOUNT_TOKEN || request.getHeader("X-Plex-Provider-Version") != "5.1" -> MockResponse().setResponseCode(401)
        request.method == "PUT" -> {
            accountFavourites = Json.parseToJsonElement(request.body.readUtf8()).jsonArray.map { it.jsonObject }
            container { put("size", 0) }
        }
        else -> container { put("FavoriteChannel", JsonArray(accountFavourites)) }
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
        private val LIBRARY = listOf("/library/sections", "/library/metadata/", "/library/parts/")
        private const val EPG = "tv.plex.providers.epg.cloud:12"

        private fun obj(body: JsonObjectBuilder.() -> Unit) = buildJsonObject(body)

        /** A favourite channel [key] of [server]'s guide, as Plex keeps it. */
        fun favourite(key: String, server: String) = obj {
            put("id", key)
            put("source", "server://$server/$EPG")
            put("title", "Channel $key")
        }

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

        /**
         * A film in [library], as the server lists it: [addedAt] in seconds, [container] its file's, [thumb] and [art]
         * paths on the server.
         */
        @Suppress("LongParameterList") // a film's fields, as the server lists them
        fun film(
            key: String,
            title: String,
            year: Int? = null,
            library: String = "1",
            addedAt: Long = 1_700_000_000,
            duration: Long = 596_000,
            container: String = "mp4",
            summary: String? = null,
            thumb: String? = null,
            art: String? = null
        ) = obj {
            put("ratingKey", key)
            put("type", "movie")
            put("title", title)
            put("librarySectionID", library.toInt())
            year?.let { put("year", it) }
            summary?.let { put("summary", it) }
            thumb?.let { put("thumb", it) }
            art?.let { put("art", it) }
            put("duration", duration)
            put("addedAt", addedAt)
            put("Media", media(key.toInt() * 10 + 1, container))
        }

        /** A show in [library]. */
        @Suppress("LongParameterList") // a show's fields, as the server lists them
        fun show(
            key: String,
            title: String,
            year: Int? = null,
            library: String = "2",
            addedAt: Long = 1_700_000_200,
            summary: String? = null,
            thumb: String? = null,
            art: String? = null
        ) = obj {
            put("ratingKey", key)
            put("type", "show")
            put("title", title)
            put("librarySectionID", library.toInt())
            year?.let { put("year", it) }
            summary?.let { put("summary", it) }
            thumb?.let { put("thumb", it) }
            art?.let { put("art", it) }
            put("addedAt", addedAt)
        }

        /** Episode [index] of [season] of [show] (its key and title), its key [key], with Plex's [markers] if any. */
        @Suppress("LongParameterList") // an episode's fields, as the server lists them
        fun episode(
            show: String,
            showTitle: String,
            key: String,
            season: Int,
            index: Int,
            title: String,
            duration: Long = 600_000,
            markers: List<Marker> = emptyList(),
            summary: String? = null,
            aired: String? = null
        ) = obj {
            put("ratingKey", key)
            put("type", "episode")
            summary?.let { put("summary", it) }
            aired?.let { put("originallyAvailableAt", it) }
            put("title", title)
            put("parentIndex", season)
            put("index", index)
            put("grandparentRatingKey", show)
            put("grandparentTitle", showTitle)
            put("duration", duration)
            put("Media", media(show.toInt() * 100 + (season - 1) * 50 + index, "mkv")) // series 1: 2101, 2102 for show 21
            if (markers.isNotEmpty()) {
                putJsonArray("Marker") {
                    markers.forEach { m ->
                        add(
                            obj {
                                put("type", m.type)
                                put("startTimeOffset", m.startMs)
                                put("endTimeOffset", m.endMs)
                                if (m.final) put("final", true)
                            }
                        )
                    }
                }
            }
        }

        private val MOVIES = listOf(
            film(
                "11",
                "Big Buck Bunny",
                2008,
                summary = "A giant rabbit takes on three bullies.",
                thumb = "/library/metadata/11/thumb/1",
                art = "/library/metadata/11/art/1"
            ),
            film("12", "Sintel", 2010, addedAt = 1_700_000_100, duration = 888_000, container = "mkv"),
        )

        private val SHOWS = listOf(show("21", "Test Show", 2020, summary = "Two episodes of colour bars."))

        private val EPISODES = listOf(1, 2).map { n ->
            episode(
                "21",
                "Test Show",
                "21$n",
                1,
                n,
                if (n ==
                    1
                ) {
                    "Pilot"
                } else {
                    "Second"
                },
                summary = if (n == 2) "Colour bars, one more time." else null
            )
        }
    }
}

/** Signs in to [plex] (as if code ABCD had been entered) and adds its servers: the id of Home Plex's source. */
suspend fun AppContainer.addHomePlex(plex: FakePlex): Long {
    this.plex.plexTv = plex.url
    this.plex.plexGuide = plex.url
    plex.claimed = true
    return this.plex.signInHome(db)
}

/** Signs in with FakePlex's account (its plex.tv already set) and adds its servers: the id of Home Plex's source. */
suspend fun PlexSources.signInHome(db: AppDatabase): Long {
    signIn(FakePlex.ACCOUNT_TOKEN)
    return db.playlists().all().single { it.machineId == FakePlex.MACHINE_ID }.id
}

/** A Home Plex channel's id in the app, by its number on the server ("1" is ch-1's). */
fun plexChannel(number: String): Long = plexChannelId("ch-$number")

/** As [addHomePlex], then syncs Home Plex (its channels and guide, films and series): the start of most UI tests. */
suspend fun AppContainer.addSyncedHomePlex(plex: FakePlex): Long = addHomePlex(plex).also { sync.refreshNow(it).getOrThrow() }
