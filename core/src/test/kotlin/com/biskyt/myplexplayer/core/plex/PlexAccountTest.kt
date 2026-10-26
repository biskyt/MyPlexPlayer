package com.biskyt.myplexplayer.core.plex

import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * Sign-in and servers, against plex.tv's replies on the owner's account (spikes/plex_spike.py, sanitised). Real
 * time throughout (runBlocking): picking an address races real connections.
 */
class PlexAccountTest {
    private val plexTv = MockWebServer()
    private val home = MockWebServer()
    private val outside = MockWebServer()
    private val relay = MockWebServer()
    private val more = mutableListOf<MockWebServer>()

    /**
     * A server of its own, for a test's next case: a probe cut off before it reached the last case's server leaves that
     * server's queued answer unused, which the next request to it would get.
     */
    private fun fresh() = MockWebServer().also { more += it }
    private val device = PlexDevice(clientId = "client-1", product = "MyPlexPlayer", version = "0.1.0")
    private lateinit var account: PlexAccount

    private val identity = MockResponse().setBody(fixture("identity"))

    @Before
    fun setUp() {
        plexTv.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = when (request.requestUrl!!.encodedPath) {
                "/api/v2/pins" -> MockResponse().setResponseCode(201).setBody(fixture("pins-create"))
                "/api/v2/pins/1001" -> MockResponse().setBody(fixture("pins-claimed"))
                "/api/v2/resources" ->
                    if (request.getHeader("X-Plex-Token") == "account-token") {
                        MockResponse().setBody(fixture("resources"))
                    } else {
                        MockResponse().setResponseCode(401)
                    }
                "/settings/favoriteChannels" ->
                    if (request.getHeader("X-Plex-Token") == "account-token" && request.getHeader("X-Plex-Provider-Version") == "5.1") {
                        MockResponse().setBody(fixture("favourite-channels"))
                    } else {
                        MockResponse().setResponseCode(401)
                    }
                else -> MockResponse().setResponseCode(404)
            }
        }
        account = PlexAccount(OkHttpClient(), device, plexTv.url("/").toString(), guideUrl = plexTv.url("/").toString())
    }

    @After
    fun tearDown() = (listOf(plexTv, home, outside, relay) + more).forEach { it.shutdown() }

    @Test
    fun `the account's favourite channels, each with its server`() = runBlocking {
        val favourites = account.favouriteChannels("account-token")
        // One without an id is left out, not the whole list.
        assertEquals(listOf("5fc705db-ch1", "5fc705db-ch9"), favourites.map { it.id })
        assertEquals(listOf("MACHINEID0", "MACHINEID1"), favourites.map { it.machineId })
        assertNull(PlexFavouriteChannel("x", source = "elsewhere").machineId)
    }

    @Test
    fun `a short code to enter at plex dot tv slash link, then the token once it's entered`() = runBlocking {
        val pin = account.createPin()
        assertEquals("ABCD", pin.code)
        assertEquals(900, pin.expiresIn)
        assertNull(pin.authToken)
        val created = plexTv.takeRequest()
        assertEquals("POST", created.method)
        assertNull("the short code, not a strong one", created.requestUrl!!.queryParameter("strong"))
        assertEquals("client-1", created.getHeader("X-Plex-Client-Identifier"))
        assertEquals("MyPlexPlayer", created.getHeader("X-Plex-Product"))
        assertNull(created.getHeader("X-Plex-Token"))

        assertEquals("TOKEN", account.pin(pin.id).authToken)
    }

    @Test
    fun `the account's servers, its own and shared, with their own tokens`() = runBlocking {
        val servers = account.servers("account-token")
        assertEquals("players aren't servers", listOf("MACHINEID0", "MACHINEID1"), servers.map { it.clientIdentifier })
        assertEquals(listOf(true, false), servers.map { it.owned })
        assertEquals(listOf("TOKEN0", "TOKEN1"), servers.map { it.accessToken })
        assertEquals(listOf(true, false, true), servers[0].connections.map { it.local || it.relay })
        val url = plexTv.takeRequest().requestUrl!!
        assertEquals(listOf("1", "1", "1"), listOf("includeHttps", "includeRelay", "includeIPv6").map { url.queryParameter(it) })
    }

    @Test
    fun `a refused account token says so`() = runBlocking {
        try {
            account.servers("old-token")
            fail("expected a failure")
        } catch (e: PlexException) {
            assertEquals(PlexException.Reason.UNAUTHORIZED, e.reason)
        }
    }

    @Test
    fun `the home address wins even when another answers first, and gets the server's token`() = runBlocking {
        home.enqueue(identity.clone().setBodyDelay(300, TimeUnit.MILLISECONDS))
        outside.enqueue(identity.clone())
        // A long grace, so a busy test machine can't make the home answer late.
        val chosen = account.connect(server(home to Kind.HOME, outside to Kind.OUTSIDE), homeGraceMs = 5_000)
        assertEquals(PlexServer(uri(home), "server-token"), chosen)
        assertEquals("server-token", home.takeRequest().getHeader("X-Plex-Token"))
    }

    @Test
    fun `an outside address before the relay, and the relay when nothing else answers`() = runBlocking {
        relay.enqueue(identity.clone())
        outside.enqueue(identity.clone().setBodyDelay(300, TimeUnit.MILLISECONDS))
        assertEquals(uri(outside), account.connect(server(relay to Kind.RELAY, outside to Kind.OUTSIDE))?.baseUrl)

        relay.enqueue(identity.clone())
        repeat(2) {
            // a shared server's, from outside its home (twice: OkHttp may try again)
            home.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        }
        assertEquals(uri(relay), account.connect(server(home to Kind.HOME, relay to Kind.RELAY))?.baseUrl)
    }

    @Test
    fun `an address that answers doesn't wait long for others`() = runBlocking {
        home.enqueue(identity.clone())
        outside.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)) // still waiting
        assertQuick { assertEquals(uri(home), account.connect(server(home to Kind.HOME, outside to Kind.OUTSIDE))?.baseUrl) }

        // Away from home: the home address hangs, and the outside one is taken soon after it answers.
        val away = fresh().apply { enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)) }
        val reached = fresh().apply { enqueue(identity.clone()) }
        assertQuick { assertEquals(uri(reached), account.connect(server(away to Kind.HOME, reached to Kind.OUTSIDE))?.baseUrl) }

        // An address that sends its headers and then stalls is cut off too (the home one, slow, answers once it's stalled).
        val slow = fresh().apply { enqueue(identity.clone().setHeadersDelay(300, TimeUnit.MILLISECONDS)) }
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { stalls ->
            thread(isDaemon = true) {
                runCatching {
                    stalls.accept().use { socket ->
                        socket.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 1000\r\n\r\n{".toByteArray())
                        socket.getInputStream().readBytes() // until the client gives up and closes the socket
                    }
                }
            }
            val stalled = PlexConnection("http://127.0.0.1:${stalls.localPort}")
            val both = server(slow to Kind.HOME).let { it.copy(connections = it.connections + stalled) }
            assertQuick { assertEquals(uri(slow), account.connect(both)?.baseUrl) }
        }
    }

    private inline fun assertQuick(block: () -> Unit) {
        val started = System.nanoTime()
        block()
        val tookMs = (System.nanoTime() - started) / 1_000_000
        assertTrue("took $tookMs ms", tookMs < 3_000)
    }

    @Test
    fun `an address where another server answers isn't the server's`() = runBlocking {
        // Another server at a shared server's home address, on this home network.
        home.enqueue(MockResponse().setBody(fixture("identity").replace("MACHINEID", "OTHERSERVER")))
        outside.enqueue(identity.clone())
        assertEquals(uri(outside), account.connect(server(home to Kind.HOME, outside to Kind.OUTSIDE))?.baseUrl)
    }

    @Test
    fun `no address when none answers as a Plex server in time`() = runBlocking {
        home.enqueue(MockResponse().setBody("<html>a router's page</html>"))
        outside.enqueue(MockResponse().setResponseCode(401))
        relay.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val none = account.connect(
            server(home to Kind.HOME, outside to Kind.OUTSIDE, relay to Kind.RELAY),
            timeoutMs = 500
        )
        assertNull(none)
    }

    private enum class Kind { HOME, OUTSIDE, RELAY }

    private fun server(vararg connections: Pair<MockWebServer, Kind>) = PlexResource(
        clientIdentifier = "MACHINEID",
        provides = "server",
        accessToken = "server-token",
        connections = connections.map { (at, kind) -> PlexConnection(uri(at), kind == Kind.HOME, kind == Kind.RELAY) },
    )

    private fun uri(server: MockWebServer) = server.url("/").toString().trimEnd('/')

    private fun fixture(name: String) = javaClass.getResource("/plex/$name.json")!!.readText()
}
