package com.biskyt.myplexplayer.core.plex

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.Dispatcher
import okhttp3.HttpUrl
import okhttp3.OkHttpClient

/**
 * A sign-in code: the user enters [code] at plex.tv/link; [authToken] is set once they have. It lasts [expiresIn]
 * seconds (15 minutes on the owner's account), after which a new one is needed.
 */
@Serializable
data class PlexPin(val id: Long, val code: String, val expiresIn: Int = 0, val authToken: String? = null)

/**
 * A server the account can use, from plex.tv: [clientIdentifier] is its machine identifier, [accessToken] the token
 * for it (its own, not the account's), and [owned] false for a server shared by someone else.
 */
@Serializable
data class PlexResource(
    val name: String = "",
    val clientIdentifier: String,
    val provides: String = "",
    val owned: Boolean = false,
    val accessToken: String? = null,
    val connections: List<PlexConnection> = emptyList(),
)

/** One address of a server: on the home network ([local]), from outside, or through Plex's [relay]. */
@Serializable
data class PlexConnection(val uri: String, val local: Boolean = false, val relay: Boolean = false)

/**
 * One of the account's favourite Live TV channels: [id] is the channel's id in its server's lineup (as
 * [PlexChannel.tuneKey]), and [source] says which server and guide (`server://{machine id}/{provider}`).
 */
@Serializable
data class PlexFavouriteChannel(val id: String, val source: String = "") {
    /** The machine identifier of the server the channel is on, or null if [source] doesn't name one. */
    val machineId: String?
        get() = source.removePrefix(SERVER).takeIf { it != source }?.substringBefore('/')?.ifEmpty { null }

    private companion object {
        const val SERVER = "server://"
    }
}

@Serializable
internal class PlexFavouritesResponse(@SerialName("MediaContainer") val container: PlexFavourites = PlexFavourites())

@Serializable
internal class PlexFavourites(
    @SerialName("FavoriteChannel") @Serializable(with = SkippingList::class) val channels: List<PlexFavouriteChannel> = emptyList(),
)

/**
 * The account side of Plex, on plex.tv: sign-in with a short code entered at plex.tv/link, then the account's
 * servers and the best address for each. Proven on the owner's account (`spikes/plex_spike.py`; see
 * docs/plex-api.md).
 */
class PlexAccount(
    private val http: OkHttpClient,
    private val device: PlexDevice,
    baseUrl: String = PLEX_TV,
    guideUrl: String = EPG_PROVIDER,
) {

    private val base: HttpUrl = plexBase(baseUrl)
    private val guide: HttpUrl = plexBase(guideUrl)

    /** A new short code for plex.tv/link ("strong" codes are longer, for a link the app opens itself). */
    suspend fun createPin(): PlexPin = call("POST", url("api/v2/pins"), token = null)

    /** [id]'s code as it stands: [PlexPin.authToken] stays null until the user enters it. Ask every second or two. */
    suspend fun pin(id: Long): PlexPin = call("GET", url("api/v2/pins/$id"), token = null)

    /** The servers the account ([token]) can use, its own and shared ones, with every address of each. */
    suspend fun servers(token: String): List<PlexResource> = call<List<PlexResource>>(
        "GET",
        url("api/v2/resources") {
            addQueryParameter("includeHttps", "1")
            addQueryParameter("includeRelay", "1")
            addQueryParameter("includeIPv6", "1")
        },
        token
    ).filter { "server" in it.provides.split(',') }

    /**
     * [server] at its best address: every one is tried at once, and the first on the home network to answer wins.
     * Failing that, the first other one, once home ones have had [homeGraceMs] more; and Plex's relay (slow, and
     * limited) only when nothing else answers. Null if none answers within [timeoutMs] (the relay took over 6
     * seconds to fail on the owner's network, and a shared server's home addresses don't answer from outside it).
     */
    @OptIn(ExperimentalCoroutinesApi::class) // onTimeout
    suspend fun connect(
        server: PlexResource,
        timeoutMs: Long = CONNECT_TIMEOUT_MS,
        homeGraceMs: Long = HOME_GRACE_MS,
    ): PlexServer? = coroutineScope {
        // Its own dispatcher: probes aren't queued behind other calls to the same host.
        val quick = http.newBuilder().dispatcher(Dispatcher()).callTimeout(timeoutMs, TimeUnit.MILLISECONDS).build()
        val answered = Channel<PlexConnection>(Channel.UNLIMITED)
        val probes = server.connections.distinctBy { it.uri }.map { connection ->
            launch { if (isServer(quick, connection.uri, server)) answered.send(connection) }
        }
        launch {
            probes.joinAll()
            answered.close()
        }
        var best: PlexConnection? = null
        var graceEnds: Long? = null // once an outside address has answered
        while (best == null || rank(best) > 0) { // a home address: nothing beats it, so don't wait for the rest
            val next = select<PlexConnection?> {
                answered.onReceiveCatching { it.getOrNull() }
                graceEnds?.let { onTimeout(maxOf(0, it - System.nanoTime()) / NANOS_PER_MS) { null } }
            } ?: break
            if (best == null || rank(next) < rank(best)) best = next
            if (graceEnds == null && rank(next) == 1) graceEnds = System.nanoTime() + homeGraceMs * NANOS_PER_MS
        }
        coroutineContext.cancelChildren() // cancels the calls still waiting
        best?.let { PlexServer(it.uri, server.accessToken) }
    }

    /**
     * The account's ([token]) favourite Live TV channels, kept by Plex's guide service rather than a server (as
     * Plezy reads them): each names its server ([PlexFavouriteChannel.machineId]) and channel.
     */
    suspend fun favouriteChannels(token: String): List<PlexFavouriteChannel> =
        call<PlexFavouritesResponse>("GET", guide.at("settings/favoriteChannels"), token, PROVIDER_VERSION to "5.1").container.channels

    private fun rank(connection: PlexConnection): Int = when {
        connection.relay -> 2
        connection.local -> 0
        else -> 1
    }

    /**
     * Whether [uri] is [server]: its /identity gives the machine identifier plex.tv listed it under (the same on
     * the owner's server). Another server can answer at an address, such as a shared server's home address on
     * this home network.
     */
    private suspend fun isServer(client: OkHttpClient, uri: String, server: PlexResource): Boolean = try {
        PlexClient(client, PlexServer(uri, server.accessToken), device).identity() == server.clientIdentifier
    } catch (ignored: PlexException) { // doesn't answer (in time), or isn't a Plex server
        false
    }

    private fun url(path: String, query: HttpUrl.Builder.() -> Unit = {}): HttpUrl = base.at(path, query)

    private suspend inline fun <reified T> call(method: String, url: HttpUrl, token: String?, vararg extra: Pair<String, String>): T =
        http.fetch(method, url, plexHeaders(device, token) + extra)

    companion object {
        const val PLEX_TV = "https://plex.tv/"

        /** Plex's guide service, which keeps the account's favourite channels. */
        const val EPG_PROVIDER = "https://epg.provider.plex.tv/"
        private const val PROVIDER_VERSION = "X-Plex-Provider-Version"
        const val CONNECT_TIMEOUT_MS = 6_000L

        /** After an outside address answers, how long a home one still has. */
        const val HOME_GRACE_MS = 500L
        private const val NANOS_PER_MS = 1_000_000L
    }
}
