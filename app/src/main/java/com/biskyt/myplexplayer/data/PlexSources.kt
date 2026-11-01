package com.biskyt.myplexplayer.data

import android.os.Build
import androidx.annotation.VisibleForTesting
import androidx.room.withTransaction
import com.biskyt.myplexplayer.BuildConfig
import com.biskyt.myplexplayer.core.plex.PlexAccount
import com.biskyt.myplexplayer.core.plex.PlexAutoDelete
import com.biskyt.myplexplayer.core.plex.PlexClient
import com.biskyt.myplexplayer.core.plex.PlexDevice
import com.biskyt.myplexplayer.core.plex.PlexException
import com.biskyt.myplexplayer.core.plex.PlexFavouriteChannel
import com.biskyt.myplexplayer.core.plex.PlexItem
import com.biskyt.myplexplayer.core.plex.PlexLiveTv
import com.biskyt.myplexplayer.core.plex.PlexMarker
import com.biskyt.myplexplayer.core.plex.PlexMediaInfo
import com.biskyt.myplexplayer.core.plex.PlexPin
import com.biskyt.myplexplayer.core.plex.PlexPlayState
import com.biskyt.myplexplayer.core.plex.PlexProgramme
import com.biskyt.myplexplayer.core.plex.PlexServer
import com.biskyt.myplexplayer.core.plex.PlexStream
import com.biskyt.myplexplayer.core.plex.PlexTag
import com.biskyt.myplexplayer.core.plex.PlexType
import com.biskyt.myplexplayer.core.plex.Score
import com.biskyt.myplexplayer.core.plex.ageRating
import com.biskyt.myplexplayer.core.plex.scores
import com.biskyt.myplexplayer.core.plex.titleLinks
import com.biskyt.myplexplayer.core.vod.CastMember
import com.biskyt.myplexplayer.core.vod.EpisodeInfo
import com.biskyt.myplexplayer.core.vod.SeasonInfo
import com.biskyt.myplexplayer.core.vod.SeriesDetails
import com.biskyt.myplexplayer.core.vod.VodDetails
import com.biskyt.myplexplayer.data.db.AppDatabase
import com.biskyt.myplexplayer.data.db.ChannelEntity
import com.biskyt.myplexplayer.data.db.MovieEntity
import com.biskyt.myplexplayer.data.db.PlaylistEntity
import com.biskyt.myplexplayer.data.db.SeriesEntity
import com.biskyt.myplexplayer.data.db.VOD_EPISODE
import com.biskyt.myplexplayer.data.db.VOD_MOVIE
import com.biskyt.myplexplayer.data.db.WatchProgressEntity
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

    /** Plex's guide service, which keeps the account's favourite channels (tests point it at a stand-in). */
    @VisibleForTesting
    var plexGuide: String = PlexAccount.EPG_PROVIDER

    private val playlists = db.playlists()

    /** The server's play state, brought into the app's progress rows. */
    val progress = PlexProgress(db)

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

    private suspend fun account() = PlexAccount(http, device(), plexTv, plexGuide)

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
            val existing = playlists.all().associateBy { it.machineId }
            reached.map { (resource, server) ->
                val address = requireNotNull(server)
                // The server's own token: it's what plays from it, so a backup keeps it, like a provider's login.
                val old = existing[resource.clientIdentifier]
                val updated = old?.copy(name = resource.name, server = address.baseUrl, password = address.token.orEmpty())
                    ?: PlaylistEntity(
                        name = resource.name,
                        server = address.baseUrl,
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
    suspend fun libraries(client: PlexClient): Map<PlexType, List<RemoteCategory>> = client.sections().let { sections ->
        listOf(PlexType.MOVIE, PlexType.SHOW).associateWith { type ->
            sections.filter { it.type == type.section }.map { RemoteCategory(it.key, it.title) }
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
            durationSec = m.durationSec,
            artUrl = backdrop(client, m.art),
        )
    }

    /**
     * A series from [titles], at [position] in the list, with its place on Home's recently added row ([newest], by
     * series id); null for one without a usable id.
     */
    @Suppress("LongParameterList") // a row's every field
    fun series(
        client: PlexClient,
        playlistId: Long,
        title: Pair<String, PlexItem>,
        position: Int,
        stamp: Long,
        newest: Map<Long, Int> = emptyMap(),
    ): SeriesEntity? {
        val (library, s) = title
        val id = s.ratingKey.toLongOrNull() ?: return null
        return SeriesEntity(
            playlistId = playlistId,
            seriesId = id,
            title = s.title,
            year = s.year,
            categoryId = library,
            coverUrl = image(client, s.thumb),
            rating = null,
            lastModified = s.addedAt ?: 0,
            position = position,
            syncStamp = stamp,
            unwatched = unwatched(s),
            newest = newest[id],
            episodes = s.leafCount,
            artUrl = backdrop(client, s.art),
        )
    }

    /** A poster or still, at a size the server scales it to. */
    fun image(client: PlexClient, path: String?, width: Int = POSTER_WIDTH, height: Int = POSTER_HEIGHT): String? =
        path?.let { client.imageUrl(it, width, height) }

    /** A film's page, bringing its play state from the server into its progress row. */
    suspend fun movieDetails(p: PlaylistEntity, ratingKey: Long): VodDetails {
        val client = client(p)
        val item = client.item(ratingKey.toString(), guids = true)
        progress.positions(p.id, VOD_MOVIE, listOf(item), rowFor(client, p.id))
        return details(client, item)
    }

    /** A film's play state again (its page already known). */
    suspend fun movieState(p: PlaylistEntity, ratingKey: Long) {
        val client = client(p)
        progress.positions(p.id, VOD_MOVIE, listOf(client.item(ratingKey.toString())), rowFor(client, p.id))
    }

    /** A series' page, bringing its episodes' play state and its unwatched count from the server. */
    suspend fun seriesDetails(p: PlaylistEntity, ratingKey: Long): SeriesDetails {
        val (client, show, leaves) = seriesState(p, ratingKey, guids = true)
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
                badges = PlexMediaInfo.of(e)?.badges.orEmpty(),
            )
        }
        // Each season's year and poster come with its episodes: no call of its own.
        val seasons = leaves.groupBy { it.parentIndex ?: 0 }.map { (number, its) ->
            SeasonInfo(number, null, its.mapNotNull { it.year }.minOrNull(), image(client, its.firstNotNullOfOrNull { it.parentThumb }))
        }
        return SeriesDetails(details(client, show), seasons, episodes)
    }

    /** A series' episodes' play state and unwatched count again (its page already known), with what was fetched. */
    suspend fun seriesState(
        p: PlaylistEntity,
        ratingKey: Long,
        listed: PlexItem? = null,
        guids: Boolean = false
    ): Triple<PlexClient, PlexItem, List<PlexItem>> {
        val client = client(p)
        val (show, leaves) = coroutineScope {
            val show = async { listed ?: client.item(ratingKey.toString(), guids = guids) }
            val leaves = async { client.episodes(ratingKey.toString()) }
            show.await() to leaves.await()
        }
        val last = leaves.lastOrNull()?.ratingKey
        progress.positions(p.id, VOD_EPISODE, leaves, rowFor(client, p.id, show = show) { it.ratingKey != last })
        db.vod().setEpisodeCounts(p.id, ratingKey, unwatched(show), show.leafCount)
        return Triple(client, show, leaves)
    }

    private fun details(client: PlexClient, item: PlexItem) = scores(item).let { scores -> details(client, item, scores) }

    private fun details(client: PlexClient, item: PlexItem, scores: List<Score>) = VodDetails(
        plot = item.summary.ifBlank { null },
        year = item.year,
        durationSec = item.durationSec,
        backdropUrl = backdrop(client, item.art),
        posterUrl = image(client, item.thumb),
        // Only where the server names no source for it: otherwise its scores show, with their logos.
        rating = (item.audienceRating ?: item.rating)?.takeIf { scores.isEmpty() },
        contentRating = ageRating(item.contentRating),
        scores = scores,
        genres = item.genres.tags(),
        directors = item.directors.tags(),
        writers = item.writers.tags(),
        producers = item.producers.tags(),
        countries = item.countries.tags(),
        collections = item.collections.tags(),
        cast = castOf(client, item),
        studio = item.studio,
        originalTitle = item.originalTitle?.takeIf { it.isNotBlank() && it != item.title },
        released = item.originallyAvailableAt,
        addedAt = item.addedAt,
        links = titleLinks(item),
        media = PlexMediaInfo.of(item),
    )

    private fun List<PlexTag>.tags() = map { it.tag }.filter { it.isNotBlank() }

    /**
     * A film or episode from the server: where it plays from (its file as it is: never re-encoded), and its play state
     * brought into its progress row first, so playback resumes from that row alone.
     */
    suspend fun playback(p: PlaylistEntity, ratingKey: Long, kind: String): PlexPlayback {
        val client = client(p)
        val item = client.item(ratingKey.toString(), markers = true)
        val part = item.part ?: throw PlexException("No file for $ratingKey", reason = PlexException.Reason.UNEXPECTED_RESPONSE)
        progress.positions(p.id, kind, listOf(item), rowFor(client, p.id))
        // The token goes in the request headers, not the address, so it isn't in logs or error reports.
        return PlexPlayback(client.directPlay(part), item, client) { refreshUnwatched(p, client, it) }
    }

    // --- Live TV ---

    /**
     * The keys of server [p]'s channels that the account has as favourites in Plex, in Plex's order; null when they
     * can't be read (not signed in, or out of reach: Plex keeps them, not the server).
     */
    suspend fun favouriteChannelKeys(p: PlaylistEntity): List<String>? {
        val token = settings.plexToken() ?: return null
        val machineId = p.machineId ?: return null
        return offlineOk { account().favouriteChannels(token) }?.filter { it.machineId == machineId }?.mapNotNull { it.id }
    }

    /**
     * Makes channel [ch] of server [p] a favourite in Plex, or not ([on]), in the account's list, which every Plex app
     * shares: a new one goes last, named as other Plex apps name it (its server and guide, title, number and logo).
     * Throws [PlexException] (or another IOException) when Plex can't be reached or refuses: then nothing changed.
     */
    suspend fun setFavouriteChannel(p: PlaylistEntity, ch: ChannelEntity, on: Boolean) = changeFavouriteChannels { now ->
        val key = ch.providerEpgId
        val isThis = { f: PlexFavouriteChannel -> f.machineId == p.machineId && f.id == key }
        when {
            !on -> now.filterNot(isThis)
            key == null || now.any(isThis) -> now
            else -> {
                val source = now.firstOrNull { it.machineId == p.machineId }?.source ?: favouriteSource(p)
                now + PlexFavouriteChannel.of(key, source, ch.name, ch.number.toString(), ch.logoUrl)
            }
        }
    }

    /**
     * Orders server [p]'s favourite channels in Plex as [keys] (channel keys): its places in the account's list are
     * filled in that order (any Plex has that [keys] hasn't, last), other servers' keep theirs. Throws as
     * [setFavouriteChannel].
     */
    suspend fun orderFavouriteChannels(p: PlaylistEntity, keys: List<String>) = changeFavouriteChannels { now ->
        val place = keys.withIndex().associate { it.value to it.index }
        // A stable sort: one twice in Plex's list keeps both places; any [keys] hasn't, last.
        val ordered = now.filter { it.machineId == p.machineId }.sortedBy { place[it.id] ?: Int.MAX_VALUE }.iterator()
        now.map { if (it.machineId == p.machineId) ordered.next() else it }
    }

    private val favouritesLock = Mutex()

    /**
     * Runs [block] while no other change to the favourite channels, nor a sync's copy of them, runs: two changes
     * mustn't each save the list as read before the other's, nor a sync put back a copy read before a change.
     */
    suspend fun <T> withFavourites(block: suspend () -> T): T = favouritesLock.withLock { block() }

    /** The account's favourite channels as [change] makes them, from the list Plex has now (saved only if changed). */
    private suspend fun changeFavouriteChannels(change: suspend (List<PlexFavouriteChannel>) -> List<PlexFavouriteChannel>) {
        val account = account()
        val token = token()
        val now = account.favouriteChannels(token)
        val changed = change(now)
        if (changed != now) account.saveFavouriteChannels(token, changed)
    }

    /** The source a new favourite of server [p]'s channels names: the server, and its guide provider. */
    private suspend fun favouriteSource(p: PlaylistEntity): String {
        val machineId = p.machineId ?: throw PlexException("No server for ${p.id}", reason = PlexException.Reason.UNEXPECTED_RESPONSE)
        return PlexFavouriteChannel.source(machineId, liveTvOf(p).provider)
    }

    private suspend fun token(): String =
        settings.plexToken() ?: throw PlexException("Not signed in", reason = PlexException.Reason.UNAUTHORIZED)

    /** Each server's Live TV, as last found: tuning needs its DVR. */
    private val liveTv = java.util.concurrent.ConcurrentHashMap<Long, PlexLiveTv>()

    /** Server [p]'s Live TV, as last found, else asked for; throws when it has none. */
    private suspend fun liveTvOf(p: PlaylistEntity, client: PlexClient? = null): PlexLiveTv = liveTv[p.id]
        ?: (client ?: client(p)).liveTv()?.also { liveTv[p.id] = it }
        ?: throw PlexException("No Live TV on this server", reason = PlexException.Reason.UNEXPECTED_RESPONSE)

    /** A server's Live TV and its channels, or null when it has none. */
    suspend fun lineup(p: PlaylistEntity): PlexLineup? {
        val client = client(p)
        val live = client.liveTv() ?: return null.also { liveTv -= p.id }
        liveTv[p.id] = live
        return PlexLineup(live, client.channels(live))
    }

    /**
     * Every channel's programmes from [fromMs] to [toMs], asked for [GUIDE_CHUNK_MS] at a time (a low-powered server builds each
     * answer). A part that can't be read is left out; only when none of it can be is the guide not understood.
     */
    suspend fun guide(p: PlaylistEntity, live: PlexLiveTv, fromMs: Long, toMs: Long): List<PlexProgramme> {
        val client = client(p)
        var unreadable: PlexException? = null
        val programmes = (fromMs until toMs step GUIDE_CHUNK_MS).flatMap { start ->
            try {
                client.guide(live, start / 1000, minOf(start + GUIDE_CHUNK_MS, toMs) / 1000)
            } catch (e: PlexException) {
                if (e.reason != PlexException.Reason.UNEXPECTED_RESPONSE) throw e
                unreadable = e
                emptyList()
            }
        }
        unreadable?.takeIf { programmes.isEmpty() }?.let { throw it }
        return programmes
    }

    /** A Live TV channel ([channelKey], its key on the server) to tune and play: see [PlexLive]. */
    suspend fun live(p: PlaylistEntity, channelKey: String): PlexLive {
        val client = client(p)
        return PlexLive(client, liveTvOf(p, client), channelKey)
    }

    // --- play state (see PlexProgress) ---

    /**
     * How a film's or episode's progress row is made from the server's item: where it got to, or the end once
     * watched; null when it hasn't been played. An episode is filed under its series ([show], else the one the
     * item names), with [hasNext] saying whether another follows.
     */
    private fun rowFor(
        client: PlexClient,
        playlistId: Long,
        show: PlexItem? = null,
        hasNext: (PlexItem) -> Boolean = { false },
    ): (PlexItem) -> WatchProgressEntity? {
        val showImage = show?.let { image(client, it.thumb) }
        return { item -> row(client, playlistId, item, show, showImage, hasNext(item)) }
    }

    @Suppress("LongParameterList")
    private fun row(
        client: PlexClient,
        playlistId: Long,
        item: PlexItem,
        show: PlexItem?,
        showImage: String?,
        hasNext: Boolean,
    ): WatchProgressEntity? {
        val kind = item.vodKind
        val id = item.ratingKey.toLongOrNull()
        val duration = item.duration
        val position = item.viewOffset ?: duration.takeIf { (item.viewCount ?: 0) > 0 }
        if (kind == null || id == null || duration == null || position == null) return null
        val episode = kind == VOD_EPISODE
        return WatchProgressEntity(
            playlistId = playlistId,
            kind = kind,
            itemId = id,
            positionMs = position,
            durationMs = duration,
            updatedAt = item.lastViewedMs ?: 0,
            title = if (episode) show?.title ?: item.grandparentTitle.orEmpty() else item.title,
            imageUrl = if (episode) showImage ?: image(client, item.grandparentThumb) else image(client, item.thumb),
            seriesId = if (episode) (show?.ratingKey ?: item.grandparentRatingKey)?.toLongOrNull() else null,
            season = item.parentIndex.takeIf { episode },
            episode = item.index.takeIf { episode },
            episodeTitle = item.title.ifBlank { null }.takeIf { episode },
            hasNext = hasNext,
            carryOnAt = null,
        )
    }

    /** Before a library sync: progress that didn't reach the server, sent again (so the listing has it). */
    suspend fun sendPending(p: PlaylistEntity, client: PlexClient) = progress.sendPending(p.id) { row ->
        client.timeline(row.asItem(), PlexPlayState.STOPPED, row.positionMs, UUID.randomUUID().toString())
    }

    /**
     * After a library sync: the films' play state (from the listing), then the server's Continue Watching as "Carry
     * on watching". Episodes' state comes with their series' page.
     */
    suspend fun syncPlayState(p: PlaylistEntity, client: PlexClient, films: List<PlexItem>, hub: List<PlexItem>? = null) {
        progress.positions(p.id, VOD_MOVIE, films, rowFor(client, p.id))
        carryOn(p, client, hub) // the films are in; out of reach now, Carry on watching just waits for the next sync
    }

    /**
     * Play state changed on the server since [sinceMs] (in the owner's other Plex apps, say), brought here without a
     * library sync: first what played here and wasn't reported yet; then the films of [movieLibraries] and the
     * series of [showLibraries] played since (a series: its episodes again), and those that were on Carry on watching
     * here but have left the server's Continue Watching (marked unwatched elsewhere, a title's resume point goes, and
     * no date shows it); then Continue Watching itself.
     */
    suspend fun refreshPlayState(p: PlaylistEntity, movieLibraries: List<String>, showLibraries: List<String>, sinceMs: Long) {
        val client = client(p)
        sendPending(p, client)
        val seconds = (System.currentTimeMillis() - sinceMs).coerceAtLeast(0) / 1000 + SINCE_MARGIN_S
        val (films, shows, hub) = coroutineScope {
            val films = async { movieLibraries.flatMap { client.viewedSince(it, PlexType.MOVIE, seconds) } }
            val shows = async { showLibraries.flatMap { client.viewedSince(it, PlexType.SHOW, seconds) } }
            val hub = async { client.continueWatching(CARRY_ON_LIMIT) }
            Triple(films.await(), shows.await(), hub.await())
        }
        val onHub = hub.mapNotNullTo(HashSet()) { it.ratingKey.toLongOrNull() }
        suspend fun leftHub(kind: String) = db.vod().progressOf(p.id, kind).filter {
            it.carryOnAt != null &&
                !it.pending &&
                it.itemId !in onHub
        }
        val filmIds = films.mapNotNullTo(HashSet()) { it.ratingKey.toLongOrNull() }
        val filmsLeft = leftHub(VOD_MOVIE).map { it.itemId }.filter { it !in filmIds }
            .chunked(ITEMS_PER_CALL).flatMap { ids -> client.items(ids.map { it.toString() }) }
        val listed = shows.associateBy { it.ratingKey.toLongOrNull() }
        (listed.keys.filterNotNull() + leftHub(VOD_EPISODE).mapNotNull { it.seriesId }).distinct()
            .forEach { offlineOk { seriesState(p, it, listed[it]) } } // one gone from the server doesn't hold up the rest
        syncPlayState(p, client, films + filmsLeft, hub)
    }

    /** "Carry on watching" from the server's Continue Watching ([hub], when already fetched). */
    private suspend fun carryOn(p: PlaylistEntity, client: PlexClient, hub: List<PlexItem>? = null) {
        val row = rowFor(client, p.id)
        val hub = (hub ?: offlineOk { client.continueWatching(CARRY_ON_LIMIT) })?.filter { it.vodKind != null } ?: return
        // One it suggests next, unplayed, is listed at its start.
        progress.carryOn(p.id, hub.mapNotNull { row(it) ?: row(it.copy(viewOffset = 0)) })
    }

    /**
     * Marks a film, an episode or a whole series ([ratingKey]; [series]: the series it is or is in, null for a film)
     * watched or unwatched on the server (unwatched also drops its resume point), then brings its play state back,
     * and Carry on watching, which changes with it. Its progress not yet reported (a series': all its episodes') is
     * dropped: the choice made now wins. Out of reach after the mark, the state follows at the next sync.
     */
    suspend fun setWatched(p: PlaylistEntity, ratingKey: Long, series: Long?, watched: Boolean) {
        val client = client(p)
        client.setWatched(ratingKey.toString(), watched)
        val vod = db.vod()
        vod.pendingProgress(p.id).filter { it.itemId == ratingKey || ratingKey == series && it.seriesId == series }
            .forEach { vod.reported(it.playlistId, it.kind, it.itemId, it.updatedAt) }
        offlineOk {
            if (series == null) movieState(p, ratingKey) else seriesState(p, series)
            carryOn(p, client)
        }
    }

    suspend fun removeFromContinueWatching(p: PlaylistEntity, ratingKey: Long) = client(p).removeFromContinueWatching(ratingKey.toString())

    /** Whether each server lets its media be deleted: asked once (one tiny call), again after each sync. */
    private val deletion = ConcurrentHashMap<Long, Boolean>()

    /** Whether server [p] lets its media be deleted; false while it can't be asked (asked again next time). */
    suspend fun allowsDeletion(p: PlaylistEntity): Boolean =
        deletion[p.id] ?: offlineOk { client(p).allowsDeletion() }?.also { deletion[p.id] = it } ?: false

    /** Asks again next time: the server's setting may have changed. */
    fun forgetDeletion(playlistId: Long) {
        deletion.remove(playlistId)
    }

    /** Deletes a film or an episode on the server, its file included. */
    suspend fun delete(p: PlaylistEntity, ratingKey: Long) = client(p).delete(ratingKey.toString())

    /** A title's cast, from its details on the server. */
    suspend fun cast(p: PlaylistEntity, ratingKey: Long): List<CastMember> = client(p).let { castOf(it, it.item(ratingKey.toString())) }

    suspend fun autoDeleteWatched(p: PlaylistEntity, show: Long): PlexAutoDelete? = client(p).autoDeleteWatched(show.toString())

    suspend fun setAutoDeleteWatched(p: PlaylistEntity, show: Long, policy: PlexAutoDelete) =
        client(p).setAutoDeleteWatched(show.toString(), policy)

    /** A series' unwatched count, again from the server (after one of its episodes played): for its badge. */
    private suspend fun refreshUnwatched(p: PlaylistEntity, client: PlexClient, seriesId: Long) {
        offlineOk { refreshCounts(p, client, seriesId) } // not answered: the next sync updates it
    }

    /**
     * Series [seriesId]'s counts again, after one of its episodes was deleted: true when the server no longer has the
     * series (Plex deletes a show with its last episode, docs/plex-api.md row 27). Out of reach, false: the next sync
     * brings the counts.
     */
    suspend fun seriesGone(p: PlaylistEntity, seriesId: Long): Boolean = try {
        refreshCounts(p, client(p), seriesId)
        false
    } catch (e: PlexException) {
        e.isNotFound
    } catch (ignored: IOException) {
        false
    }

    private suspend fun refreshCounts(p: PlaylistEntity, client: PlexClient, seriesId: Long) {
        val show = client.item(seriesId.toString())
        db.vod().setEpisodeCounts(p.id, seriesId, unwatched(show), show.leafCount)
    }

    /** A title's background picture, the same size wherever it shows (its page, Home), so the server sizes it once. */
    private fun backdrop(client: PlexClient, art: String?) = image(client, art, BACKDROP_WIDTH, BACKDROP_HEIGHT)

    /** A series' episodes not yet watched; null when the server doesn't say. */
    fun unwatched(show: PlexItem): Int? = show.leafCount?.let { (it - (show.viewedLeafCount ?: 0)).coerceAtLeast(0) }

    companion object {
        const val PRODUCT = "MyPlexPlayer"
        private const val PAGE = 200

        /** The guide is asked for this much at a time. */
        private const val GUIDE_CHUNK_MS = 12 * 3_600_000L

        /** Films asked for by id, this many to a call (their ids make up its address). */
        private const val ITEMS_PER_CALL = 100

        /** Plex times are whole seconds, and the check takes a moment: titles played this long before it count too. */
        private const val SINCE_MARGIN_S = 60L
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

        /** The kind of progress row a Plex item makes: a film or an episode, else none. */
        private val PlexItem.vodKind: String? get() = type.takeIf { it == VOD_MOVIE || it == VOD_EPISODE }

        /** A progress row as just enough of an item to report to the server. */
        private fun WatchProgressEntity.asItem() = PlexItem(ratingKey = itemId.toString(), duration = durationMs)
    }
}

/** The library type ("section") holding items of this type. */
private val PlexType.section: String get() = if (this == PlexType.SHOW) "show" else "movie"

/** Just what a film's play state needs, to hold for the length of a sync. */
fun PlexItem.playState() = PlexItem(
    ratingKey = ratingKey,
    type = type,
    title = title,
    thumb = thumb,
    duration = duration,
    viewOffset = viewOffset,
    viewCount = viewCount,
    lastViewedAt = lastViewedAt,
)

/** When it was last played, in ms (the server gives seconds). */
val PlexItem.lastViewedMs: Long? get() = lastViewedAt?.times(1000)

/** [item]'s cast, as the server lists them, with photos made small (loaded only where shown). */
fun castOf(client: PlexClient, item: PlexItem): List<CastMember> = item.roles.filter { it.tag.isNotBlank() }
    .map { r -> CastMember(r.tag, r.role, r.thumb?.let { client.imageUrl(it, CAST_PHOTO, CAST_PHOTO) }) }

private const val CAST_PHOTO = 120

/** [block]'s result, or null when the server didn't answer it (out of reach, or a failure). */
suspend fun <T> offlineOk(block: suspend () -> T): T? = try {
    block()
} catch (ignored: IOException) {
    null
}

/** How a report to the server went. */
enum class Sent {
    SENT,

    /** The server answered no (it no longer has the title, say): sending it again won't help. */
    REFUSED,

    /** Out of reach, or the server failing for now: worth sending again later. */
    NOT_YET,
}

/** Sends [block] to the server, saying how it went. */
suspend fun send(block: suspend () -> Unit): Sent = try {
    block()
    Sent.SENT
} catch (e: PlexException) {
    if (e.answered) Sent.REFUSED else Sent.NOT_YET
} catch (ignored: IOException) {
    Sent.NOT_YET
}

/** The server answered, and no (a 4xx other than a refused token, or a reply it can't make sense of). */
private val PlexException.answered: Boolean
    get() = reason == PlexException.Reason.UNEXPECTED_RESPONSE || reason == PlexException.Reason.SERVER_ERROR && code in 400..499

/**
 * A film or episode playing from a Plex server: where it plays from, and reports back of how far it has got (one
 * session per playback, one at a time), which keep the resume point and mark it watched. Its end is reported once
 * (later reports are dropped, and say how the end went), and then [ended] runs with the episode's series, so the
 * series' badge follows.
 */
class PlexPlayback(
    val stream: PlexStream,
    private val item: PlexItem,
    private val client: PlexClient,
    private val ended: suspend (Long) -> Unit
) {
    private val session = UUID.randomUUID().toString()
    private val mutex = Mutex()
    private var end: Sent? = null

    // Starting and closing the server's stream, one at a time: a close waits for a start under way, so the session
    // it opens is the one closed.
    private val streamLock = Mutex()

    /** Who's in it, as the server lists them (photos made small, loaded only where shown). */
    val cast: List<CastMember> by lazy { castOf(client, item) }

    /** The file's container, as Playback info names it ("MKV"), if the server says. */
    val container: String? by lazy { PlexMediaInfo.of(item)?.container }

    /** Whether the server has been asked to stream the file ([directStream]), until [close]. */
    @Volatile var streaming = false
        private set

    /** The newest progress saved for this playback, so an older save landing late is dropped. */
    private var newest = 0L

    /**
     * The file repackaged by the server, its picture and sound copied as they are, for a container the player can't
     * read. Throws [PlexException] when the server could only convert it: never asked for.
     */
    suspend fun directStream(): PlexStream = streamLock.withLock {
        streaming = true // first: a stream the server starts is stopped by [close] even if this is cut short
        try {
            client.directStream(item.ratingKey, session)
        } catch (e: PlexException) {
            streaming = false // refused (the client has closed the session): asking again may be tried
            throw e
        }
    }

    /** Its intro and credits, which the player offers to skip. */
    val markers: List<PlexMarker> get() = item.markers

    /** Ends the server's side of a [directStream] (its session would otherwise run on until Plex times it out). */
    suspend fun close() = streamLock.withLock {
        if (streaming) {
            streaming = false
            send { client.stopStream(session) }
        }
    }

    /**
     * Runs [save] (the local write) and reports [state] at [timeMs], one save at a time, in the order they were taken
     * ([takenAt]); a save older than one already done is dropped. Says how the report went.
     */
    suspend fun report(state: PlexPlayState, timeMs: Long, takenAt: Long, save: suspend () -> Unit): Sent? = mutex.withLock {
        if (takenAt < newest) return@withLock null
        newest = takenAt
        save()
        end?.let { return@withLock it }
        // Without its duration, the server would read every report as the end: none is sent.
        if (item.duration == null) return@withLock Sent.SENT
        val sent = send { client.timeline(item, state, timeMs, session) }
        if (state == PlexPlayState.STOPPED) {
            end = sent
            item.grandparentRatingKey?.toLongOrNull()?.let { ended(it) }
        }
        sent
    }
}
