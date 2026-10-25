package com.biskyt.myplexplayer.data

import android.os.Build
import androidx.annotation.VisibleForTesting
import androidx.room.withTransaction
import com.biskyt.myplexplayer.BuildConfig
import com.biskyt.myplexplayer.core.plex.PlexAccount
import com.biskyt.myplexplayer.core.plex.PlexClient
import com.biskyt.myplexplayer.core.plex.PlexDevice
import com.biskyt.myplexplayer.core.plex.PlexException
import com.biskyt.myplexplayer.core.plex.PlexItem
import com.biskyt.myplexplayer.core.plex.PlexPin
import com.biskyt.myplexplayer.core.plex.PlexServer
import com.biskyt.myplexplayer.core.plex.PlexType
import com.biskyt.myplexplayer.core.xtream.EpisodeInfo
import com.biskyt.myplexplayer.core.xtream.SeasonInfo
import com.biskyt.myplexplayer.core.xtream.SeriesDetails
import com.biskyt.myplexplayer.core.xtream.VodDetails
import com.biskyt.myplexplayer.core.xtream.XtreamCategory
import com.biskyt.myplexplayer.data.db.AppDatabase
import com.biskyt.myplexplayer.data.db.KIND_PLEX
import com.biskyt.myplexplayer.data.db.MovieEntity
import com.biskyt.myplexplayer.data.db.PlaylistEntity
import com.biskyt.myplexplayer.data.db.SeriesEntity
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient

/**
 * Plex servers as sources: signing in with a code entered at plex.tv/link, then one source per server the account
 * can use (switched from the top bar like any other), and its films and series. Every call was proven against the
 * owner's server first (docs/plex-api.md).
 */
class PlexSources(
    private val db: AppDatabase,
    private val http: OkHttpClient,
    private val settings: SettingsRepository,
) {
    /** plex.tv's address (tests point it at a stand-in). */
    @VisibleForTesting
    var plexTv: String = PlexAccount.PLEX_TV

    private val playlists = db.playlists()

    private var device: PlexDevice? = null

    /** This install, as Plex sees it (it lists the app under the account's Authorized Devices by these). */
    private suspend fun device(): PlexDevice = device ?: PlexDevice(
        clientId = settings.plexClientId(),
        product = PRODUCT,
        version = BuildConfig.VERSION_NAME,
        platformVersion = Build.VERSION.RELEASE.orEmpty(),
        device = Build.MODEL.orEmpty(),
        deviceName = Build.MODEL.orEmpty(),
    ).also { device = it }

    private suspend fun account() = PlexAccount(http, device(), plexTv)

    /** A new code to enter at plex.tv/link. */
    suspend fun createPin(): PlexPin = account().createPin()

    /** [id]'s code as it stands: its token once the user has entered it. */
    suspend fun pin(id: Long): PlexPin = account().pin(id)

    /** What adding the account's servers did: the sources added or updated, and the names of servers that didn't answer. */
    data class Added(val ids: List<Long>, val unreachable: List<String>)

    /** Keeps the account's [token] and adds its servers. */
    suspend fun signIn(token: String): Added {
        settings.setPlexToken(token)
        return addServers(token)
    }

    /**
     * Adds each server the account ([token]) can use as a source, at the best address that answers; one already
     * added (known by its machine identifier) gets its new address, name and token. The first one added becomes the
     * source in use when none is.
     */
    suspend fun addServers(token: String): Added {
        val account = account()
        val found = account.servers(token)
        val (reached, unreachable) = coroutineScope { found.map { async { it to account.connect(it) } }.awaitAll() }
            .partition { it.second != null }
        val ids = db.withTransaction {
            val existing = playlists.all().filter { it.isPlex }.associateBy { it.machineId }
            reached.map { (resource, server) ->
                val address = requireNotNull(server)
                // The server's own token: it's what plays from it, so a backup keeps it, like a provider's login.
                val old = existing[resource.clientIdentifier]
                val updated = old?.copy(name = resource.name, server = address.baseUrl, password = address.token.orEmpty())
                    ?: PlaylistEntity(
                        name = resource.name,
                        kind = KIND_PLEX,
                        server = address.baseUrl,
                        username = "",
                        password = address.token.orEmpty(),
                        machineId = resource.clientIdentifier,
                    )
                if (old == null) playlists.insert(updated) else updated.id.also { playlists.upsert(updated) }
            }
        }
        val active = settings.current().activePlaylistId
        if (ids.isNotEmpty() && playlists.get(active) == null) settings.update { it.copy(activePlaylistId = ids.first()) }
        return Added(ids, unreachable.map { it.first.name })
    }

    /**
     * [p] at a new address, when the one kept no longer answers (the server moved on the home network, or it was
     * signed in to from elsewhere); null if it can't be found. Needs the account's sign-in.
     */
    suspend fun reconnect(p: PlaylistEntity): PlaylistEntity? {
        val token = settings.plexToken() ?: return null
        val account = account()
        val resource = account.servers(token).firstOrNull { it.clientIdentifier == p.machineId } ?: return null
        val server = account.connect(resource) ?: return null
        return p.copy(server = server.baseUrl, password = server.token.orEmpty()).also { playlists.upsert(it) }
    }

    suspend fun client(p: PlaylistEntity): PlexClient = PlexClient(http, PlexServer(p.server, p.password.ifEmpty { null }), device())

    // --- films and series ---

    /** The server's libraries of films and of TV, as categories (each fetched list is split by type). */
    suspend fun libraries(client: PlexClient): Map<PlexType, List<XtreamCategory>> = client.sections().let { sections ->
        listOf(PlexType.MOVIE, PlexType.SHOW).associateWith { type ->
            sections.filter { it.type == type.section }.map { XtreamCategory(it.key, it.title) }
        }
    }

    /** Every title of [type] in [library], with the library's id. */
    suspend fun titles(client: PlexClient, type: PlexType, library: String, emit: suspend (Pair<String, PlexItem>) -> Unit) {
        var start = 0
        do {
            val page = client.page(library, type, start, PAGE, sort = "titleSort")
            page.items.forEach { emit(library to it) }
            start += page.items.size
        } while (page.items.size == PAGE && start < (page.totalSize ?: Int.MAX_VALUE))
    }

    /** A film from [titles], at [position] in the list; null for one without a usable id. */
    fun movie(client: PlexClient, playlistId: Long, title: Pair<String, PlexItem>, position: Int, stamp: Long): MovieEntity? {
        val (library, m) = title
        return MovieEntity(
            playlistId = playlistId,
            streamId = m.ratingKey.toLongOrNull() ?: return null,
            title = m.title,
            year = m.year,
            categoryId = library,
            posterUrl = image(client, m.thumb),
            rating = null,
            added = m.addedAt ?: 0,
            extension = m.extension,
            position = position,
            syncStamp = stamp,
        )
    }

    /** A series from [titles], at [position] in the list; null for one without a usable id. */
    fun series(client: PlexClient, playlistId: Long, title: Pair<String, PlexItem>, position: Int, stamp: Long): SeriesEntity? {
        val (library, s) = title
        return SeriesEntity(
            playlistId = playlistId,
            seriesId = s.ratingKey.toLongOrNull() ?: return null,
            title = s.title,
            year = s.year,
            categoryId = library,
            coverUrl = image(client, s.thumb),
            rating = null,
            lastModified = s.addedAt ?: 0,
            position = position,
            syncStamp = stamp,
        )
    }

    /** A poster or still, at a size the server scales it to. */
    fun image(client: PlexClient, path: String?, width: Int = POSTER_WIDTH, height: Int = POSTER_HEIGHT): String? =
        path?.let { client.imageUrl(it, width, height) }

    suspend fun movieDetails(p: PlaylistEntity, ratingKey: Long): VodDetails {
        val client = client(p)
        return details(client, client.item(ratingKey.toString()))
    }

    suspend fun seriesDetails(p: PlaylistEntity, ratingKey: Long): SeriesDetails {
        val client = client(p)
        val (show, leaves) = coroutineScope {
            val show = async { client.item(ratingKey.toString()) }
            val leaves = async { client.episodes(ratingKey.toString()) }
            show.await() to leaves.await()
        }
        val episodes = leaves.mapNotNull { e ->
            EpisodeInfo(
                id = e.ratingKey.toLongOrNull() ?: return@mapNotNull null,
                season = e.parentIndex ?: 0,
                number = e.index ?: 0,
                title = e.title,
                extension = e.extension,
                plot = e.summary.ifBlank { null },
                durationSec = e.durationSec,
                imageUrl = image(client, e.thumb, STILL_WIDTH, STILL_HEIGHT),
            )
        }
        return SeriesDetails(details(client, show), episodes.map { it.season }.distinct().map { SeasonInfo(it, null) }, episodes)
    }

    private fun details(client: PlexClient, item: PlexItem) = VodDetails(
        plot = item.summary.ifBlank { null },
        cast = null,
        director = null,
        genre = null,
        year = item.year,
        durationSec = item.durationSec,
        backdropUrl = image(client, item.art, BACKDROP_WIDTH, BACKDROP_HEIGHT),
        posterUrl = image(client, item.thumb),
        rating = null,
    )

    /**
     * Where a film or episode plays from: its file as it is (direct play; the server never re-encodes). The token
     * goes in the address for now, as the player sends no headers of its own yet; playing from Plex properly
     * (direct stream when the player can't read the file, progress kept in Plex) is roadmap step 5.
     */
    suspend fun playUrl(p: PlaylistEntity, ratingKey: Long): String {
        val client = client(p)
        val part = client.item(ratingKey.toString()).part
            ?: throw PlexException("No file for $ratingKey", reason = PlexException.Reason.UNEXPECTED_RESPONSE)
        val url = client.directPlay(part).url.toHttpUrl().newBuilder()
        p.password.takeIf { it.isNotEmpty() }?.let { url.addQueryParameter("X-Plex-Token", it) }
        return url.build().toString()
    }

    companion object {
        const val PRODUCT = "MyPlexPlayer"
        private const val PAGE = 200
        private const val POSTER_WIDTH = 240
        private const val POSTER_HEIGHT = 360
        private const val STILL_WIDTH = 400
        private const val STILL_HEIGHT = 225
        private const val BACKDROP_WIDTH = 1280
        private const val BACKDROP_HEIGHT = 720
        private const val MS_PER_SEC = 1000

        /** The file's type, as its extension ("mkv"). */
        private val PlexItem.extension: String get() = part?.container ?: media.firstOrNull()?.container.orEmpty()
        private val PlexItem.durationSec: Int? get() = duration?.let { (it / MS_PER_SEC).toInt() }
    }
}

/** The library type ("section") holding items of this type. */
private val PlexType.section: String get() = if (this == PlexType.SHOW) "show" else "movie"
