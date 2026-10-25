package com.biskyt.myplexplayer.testutil

import com.biskyt.myplexplayer.AppContainer
import kotlinx.serialization.json.JsonArray
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

    val url: String get() = server.url("/").toString().trimEnd('/')

    init {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = answer(request)
        }
        server.start()
    }

    override fun close() = server.shutdown()

    private fun answer(request: RecordedRequest): MockResponse {
        val path = request.requestUrl!!.encodedPath
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
            path == "/library/sections/1/all" -> page(request, MOVIES)
            path == "/library/sections/2/all" -> page(request, SHOWS)
            path == "/library/metadata/21/allLeaves" -> container { put("Metadata", JsonArray(EPISODES)) }
            path.startsWith("/library/metadata/") ->
                (MOVIES + SHOWS + EPISODES).firstOrNull { it.string("ratingKey") == path.substringAfterLast('/') }
                    ?.let { item -> container { putJsonArray("Metadata") { add(item) } } }
                    ?: MockResponse().setResponseCode(404)
            path == "/photo/:/transcode" -> MockResponse().setBody("")
            else -> MockResponse().setResponseCode(404)
        }
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

        private fun obj(body: JsonObjectBuilder.() -> Unit) = buildJsonObject(body)

        private fun JsonObject.string(key: String) = (this[key] as? JsonPrimitive)?.content

        private fun media(id: Int, container: String) = buildJsonArray {
            add(
                obj {
                    put("container", container)
                    putJsonArray("Part") {
                        add(
                            obj {
                                put("id", id)
                                put("key", "/library/parts/$id/1/file.$container")
                                put("container", container)
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
