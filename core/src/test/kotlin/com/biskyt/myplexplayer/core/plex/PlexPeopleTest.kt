package com.biskyt.myplexplayer.core.plex

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * A cast or crew member's page: the person and their credits on Discover, and their titles on a server. The fixtures
 * have the shapes of S13's replies (docs/plex-api.md 29), with made-up people and titles.
 */
class PlexPeopleTest {
    private val discover = MockWebServer()
    private val server = MockWebServer()
    private val device = PlexDevice(clientId = "client-1", product = "MyPlexPlayer", version = "0.1.0")
    private lateinit var account: PlexAccount
    private lateinit var client: PlexClient

    @Before
    fun setUp() {
        discover.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.getHeader("X-Plex-Token") != "account-token") return MockResponse().setResponseCode(401)
                return when (request.requestUrl!!.encodedPath) {
                    "/library/people/$ACTOR" -> MockResponse().setBody(fixture("person-discover"))
                    "/library/people/$WRITER" -> MockResponse().setBody(fixture("person-discover-writer"))
                    "/library/people/$ACTOR/credits" -> MockResponse().setBody(fixture("person-credits"))
                    "/library/people/$NOBODY" -> MockResponse().setBody("""{"MediaContainer":{"size":0}}""")
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = when (request.requestUrl!!.encodedPath) {
                "/library/people/4101/media" -> MockResponse().setBody(fixture("person-media"))
                else -> MockResponse().setResponseCode(404)
            }
        }
        account = PlexAccount(OkHttpClient(), device, discoverUrl = discover.url("/").toString())
        client = PlexClient(OkHttpClient(), PlexServer(server.url("/").toString(), "server-token"), device)
    }

    @After
    fun tearDown() {
        discover.shutdown()
        server.shutdown()
    }

    @Test
    fun `a person on Discover, with the account's token`() = runTest {
        val person = account.person("account-token", ACTOR)!!
        assertEquals("Mira Kell", person.title)
        assertEquals("1984-05-03", person.bornAt)
        assertEquals("Leeds, England", person.birthPlace)
        assertTrue(person.summary!!.startsWith("Mira Kell is an English actor"))
        assertEquals("https://metadata-static.plex.tv/a/people/person-a01.jpg", person.thumb)
        assertNull("not sent, so not there", person.diedAt)
        assertEquals(listOf("writer", "actor", "appeared", "producer"), person.creditTypes.map { it.type })
        assertEquals("account-token", discover.takeRequest().getHeader("X-Plex-Token"))
    }

    @Test
    fun `a person with no biography, birth or photo has none`() = runTest {
        val person = account.person("account-token", WRITER)!!
        assertEquals("Tomas Brann", person.title)
        assertNull(person.summary)
        assertNull(person.bornAt)
        assertNull(person.thumb)
        assertNull("no such person", account.person("account-token", NOBODY))
    }

    @Test
    fun `a person's credits come in one reply, by kind`() = runTest {
        val groups = account.credits("account-token", ACTOR)
        assertEquals(listOf("actor", "writer", "producer", "appeared", "other"), groups.map { it.type })
        val first = groups[0].credits[0]
        assertEquals("Mara", first.role)
        assertEquals(
            PlexCreditTitle("60000000000000000000a001", "The Harbour", "show", 2023, first.title!!.thumb, "2023-01-10T00:00:00.000Z"),
            first.title
        )
        val url = discover.takeRequest().requestUrl!!
        assertEquals("1", url.queryParameter("includeMeta"))
        assertNull("not paged: Discover ignores it", url.queryParameter("X-Plex-Container-Size"))
    }

    @Test
    fun `a person's titles on the server, with their guids and cast ids`() = runTest {
        val items = client.personMedia(4101)
        assertEquals(listOf("301", "302"), items.map { it.ratingKey })
        assertEquals(listOf("plex://show/60000000000000000000a001", "plex://movie/60000000000000000000a002"), items.map { it.guid })
        val role = items[0].roles[0]
        assertEquals(PlexTag("Mira Kell", "Mara", "https://metadata-static.plex.tv/a/people/person-a01.jpg", 4101, ACTOR), role)
        assertEquals(4201L to "5e0000000000000000000c03", items[1].directors[0].let { it.id to it.tagKey })
        assertEquals("a writer may have no photo", null, items[1].writers[0].thumb)
        assertEquals(4202L, items[1].writers[0].id)
    }

    @Test
    fun `a tag whose id is not a number keeps its name, with no id, and an episode names its show's guid`() {
        val item = plexJson.decodeFromString<PlexItem>(
            """{"ratingKey":"7","type":"episode","grandparentGuid":"plex://show/abc","Genre":[{"id":"g-1","tag":"Drama"}],""" +
                """"Role":[{"id":"4101","tag":"Mira Kell","tagKey":"k"},{"id":{"x":1},"tag":"June Okafor"}]}"""
        )
        assertEquals(listOf("Drama"), item.genres.map { it.tag })
        assertEquals(listOf(4101L, null), item.roles.map { it.id })
        assertEquals("abc", plexGuidId(item.grandparentGuid))
    }

    @Test
    fun `a credit's title is matched to a server title by the end of its guid`() {
        assertEquals("60000000000000000000a002", plexGuidId("plex://movie/60000000000000000000a002"))
        assertEquals("60000000000000000000a001", plexGuidId("plex://show/60000000000000000000a001"))
        assertNull(plexGuidId("com.plexapp.agents.none://302"))
        assertNull(plexGuidId(null))
    }

    @Test
    fun `All credits are acting, directing, writing and producing, newest first, each title once a kind`() = runTest {
        val credits = personCredits(account.credits("account-token", ACTOR))
        assertEquals(
            listOf(
                "Still Waters" to "writer",
                "The Harbour" to "actor",
                "Glass Coast" to "actor",
                "Glass Coast" to "writer",
                "Signal Hill" to "actor",
                "North Light" to "actor",
                "Paper Boats" to "producer",
                "First Frost" to "actor", // no date or year: last
            ),
            credits.map { it.title to it.kind }
        )
        assertEquals(listOf(null, "Mara", "Iona", "Screenplay"), credits.take(4).map { it.role })
        assertEquals(listOf(true, true, false), credits.take(3).map { it.show })
    }

    @Test
    fun `Also appears in is what they acted in, directed or wrote that the server does not have, each once`() = runTest {
        val credits = personCredits(account.credits("account-token", ACTOR))
        val onServer = client.personMedia(4101).mapNotNullTo(HashSet()) { plexGuidId(it.guid) }
        assertEquals(setOf("60000000000000000000a001", "60000000000000000000a002"), onServer)
        val elsewhere = alsoAppearsIn(credits, onServer)
        assertEquals(listOf("Still Waters", "Glass Coast", "Signal Hill", "First Frost"), elsewhere.map { it.title })
        assertEquals("the acting credit, not the writing one", "Iona", elsewhere[1].role)
        assertEquals(listOf("Still Waters", "Glass Coast"), alsoAppearsIn(credits, onServer, max = 2).map { it.title })
    }

    @Test
    fun `the kinds of work are those listed, most credits first, two at most`() {
        val types =
            listOf(PlexCreditType("writer", 3), PlexCreditType("actor", 12), PlexCreditType("appeared", 40), PlexCreditType("producer", 1))
        assertEquals(listOf("actor", "writer"), kindsOfWork(types))
        assertEquals(listOf("actor", "writer", "producer"), kindsOfWork(types, max = 3))
        assertEquals(emptyList<String>(), kindsOfWork(listOf(PlexCreditType("actor", 0))))
    }

    private fun fixture(name: String) = javaClass.getResource("/plex/$name.json")!!.readText()

    private companion object {
        const val ACTOR = "5e0000000000000000000a01"
        const val WRITER = "5e0000000000000000000b02"
        const val NOBODY = "5e0000000000000000000d04"
    }
}
