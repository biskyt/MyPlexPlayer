package com.biskyt.mytvplayer.data

import androidx.room.withTransaction
import com.biskyt.mytvplayer.core.m3u.M3uParser
import com.biskyt.mytvplayer.core.xtream.XtreamCategory
import com.biskyt.mytvplayer.core.xtream.XtreamClient
import com.biskyt.mytvplayer.core.xtream.XtreamCredentials
import com.biskyt.mytvplayer.core.xtream.XtreamException
import com.biskyt.mytvplayer.core.xtream.XtreamLiveStream
import com.biskyt.mytvplayer.data.db.AppDatabase
import com.biskyt.mytvplayer.data.db.CAT_LIVE
import com.biskyt.mytvplayer.data.db.CAT_MOVIE
import com.biskyt.mytvplayer.data.db.CAT_SERIES
import com.biskyt.mytvplayer.data.db.ChannelEntity
import com.biskyt.mytvplayer.data.db.ChannelPrefs
import com.biskyt.mytvplayer.data.db.KIND_M3U
import com.biskyt.mytvplayer.data.db.PlaylistEntity
import java.io.BufferedReader
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.ResponseBody

/** Playlists (Xtream accounts and M3U playlists) and downloading their channel lists. */
class PlaylistRepository(
    private val db: AppDatabase,
    private val http: OkHttpClient,
    private val settings: SettingsRepository,
    private val epg: EpgRepository,
    /** Where M3U playlists are downloaded to while being read (deleted afterwards). */
    private val workDir: File,
    private val categoryStore: CategoryStore
) {
    private val playlists = db.playlists()
    private val categories = db.categories()
    private val channels = db.channels()

    fun observePlaylists(): Flow<List<PlaylistEntity>> = playlists.observeAll()

    suspend fun get(id: Long): PlaylistEntity? = playlists.get(id)

    suspend fun allIds(): List<Long> = playlists.all().map { it.id }

    fun client(p: PlaylistEntity) = client(p.server, p.username, p.password)

    private fun client(server: String, username: String, password: String) =
        XtreamClient(http, XtreamCredentials(server, username, password))

    /** Checks the login works; throws XtreamException with a readable message otherwise. */
    suspend fun testLogin(server: String, username: String, password: String) {
        if (!client(server, username, password).account().active) throw XtreamException("This account is not active")
    }

    /**
     * Saves a new playlist and makes it the active one. Everything that can fail (login, category download) happens
     * before anything is written, so a failed attempt leaves nothing behind.
     */
    suspend fun add(name: String, server: String, username: String, password: String): Long {
        val c = client(server.trim(), username.trim(), password)
        if (!c.account().active) throw XtreamException("This account is not active")
        val remote = c.liveCategories()
        val id =
            insertAndActivate(PlaylistEntity(name = name, server = server.trim(), username = username.trim(), password = password), remote)
        // Movie and series categories too, so they can be chosen before the first download. Providers without them
        // (or with a broken list) get them on a later sync.
        runCatching { categoryStore.store(id, CAT_MOVIE, c.movieCategories()) }
        runCatching { categoryStore.store(id, CAT_SERIES, c.seriesCategories()) }
        return id
    }

    /** Saves a new M3U playlist (after checking it can be downloaded and read) and makes it the active one. */
    /**
     * Sets up the free iptv-org channels; the new playlist's id, or a message for the user. Never throws (but for
     * cancellation): any failure (network, full storage…) must leave a way forward, on first launch above all.
     */
    @Suppress("TooGenericExceptionCaught")
    suspend fun addFree(): Result<Long> = try {
        Result.success(addM3u(FREE_PLAYLIST_NAME, FREE_PLAYLIST_URL))
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(Exception((e as? IOException)?.message ?: "Couldn't set up the free channels", e))
    }

    suspend fun addM3u(name: String, url: String): Long {
        val remote = withM3u(url.trim()) { file -> m3uGroups(file) }
        return insertAndActivate(PlaylistEntity(name = name, kind = KIND_M3U, server = url.trim(), username = "", password = ""), remote)
    }

    private suspend fun insertAndActivate(p: PlaylistEntity, remote: List<XtreamCategory>): Long {
        val id = db.withTransaction {
            playlists.insert(
                p.copy(
                    name = p.name.ifBlank {
                        "My TV"
                    }
                )
            ).also { categoryStore.store(it, CAT_LIVE, remote, groupByName = p.isM3u) }
        }
        settings.update { it.copy(activePlaylistId = id) }
        return id
    }

    /** Checks an M3U URL can be downloaded and looks like a playlist, reading only its first line. */
    suspend fun testM3u(url: String) = withContext(Dispatchers.IO) {
        fetch(url.trim()) { body -> checkPlaylist(body.charStream().buffered()) }
    }

    /** Checks a playlist's address (M3U) or login (Xtream) works; throws XtreamException with a readable message. */
    suspend fun testConnection(m3u: Boolean, server: String, username: String, password: String) =
        if (m3u) testM3u(server) else testLogin(server, username, password)

    suspend fun update(p: PlaylistEntity) {
        val old = playlists.get(p.id)
        if (old != null && (old.server != p.server || old.username != p.username || old.password != p.password)) {
            testConnection(p.isM3u, p.server, p.username, p.password)
        }
        playlists.upsert(p)
    }

    suspend fun delete(id: Long) {
        db.withTransaction {
            playlists.deleteChannels(id)
            playlists.deleteCategories(id)
            playlists.deleteFavourites(id)
            playlists.deleteRecents(id)
            playlists.deleteMappings(id)
            playlists.deleteMovies(id)
            playlists.deleteSeries(id)
            playlists.deleteProgress(id)
            playlists.deleteVodFavourites(id)
            playlists.delete(id)
        }
        db.epg().providerSource(id)?.let { epg.removeSource(it.id) }
        val next = playlists.all().firstOrNull()?.id ?: 0
        if (settings.current().activePlaylistId == id) settings.update { it.copy(activePlaylistId = next) }
    }

    /**
     * Downloads the category list. Existing choices are kept; new categories are included when their detected
     * language group matches one the user already includes (or, the first time, the TV's own country/language).
     */
    suspend fun refreshCategories(playlistId: Long) {
        val p = playlists.get(playlistId) ?: return
        val remote = if (p.isM3u) withM3u(p.server) { m3uGroups(it) } else client(p).liveCategories()
        categoryStore.store(playlistId, CAT_LIVE, remote, groupByName = p.isM3u)
    }

    /**
     * Downloads the channels of included categories. Only a few categories → one request each;
     * otherwise one full request, dropping excluded rows as they stream in. Returns the channel count.
     */
    suspend fun syncChannels(playlistId: Long, onProgress: (Int) -> Unit = {}): Int {
        val p = playlists.get(playlistId) ?: return 0
        if (p.isM3u) return syncM3u(p, onProgress)
        val client = client(p)
        runCatching { client.account() }.getOrNull()?.let {
            playlists.upsert(p.copy(expiresAt = it.expiresAtEpochSec, maxConnections = it.maxConnections))
        }
        refreshCategories(playlistId)
        val out = ChannelWriter(playlistId, onProgress)
        categoryStore.forIncluded(playlistId, CAT_LIVE, { s: XtreamLiveStream ->
            s.categoryId
        }, { cat, emit -> client.liveStreams(cat, emit) }) { s ->
            out.add(
                ChannelEntity(
                    playlistId = playlistId, streamId = s.streamId, number = s.number, name = s.name, categoryId = s.categoryId,
                    logoUrl = s.logoUrl, providerEpgId = s.epgChannelId, archiveDays = s.archiveDays,
                    hidden = out.hidden(s.streamId), sortOrder = out.sortOrder(s.streamId), syncStamp = out.stamp
                )
            )
        }
        val count = out.finish()
        epg.ensureProviderSource(p, client.xmltvUrl())
        return count
    }

    /**
     * M3U: the whole playlist comes in one file, read in a single pass that collects both its groups (stored as
     * categories) and the channels of included groups. Channel ids are derived from the guide id and name so
     * favourites and the user's choices survive providers rewriting stream URLs.
     */
    private suspend fun syncM3u(p: PlaylistEntity, onProgress: (Int) -> Unit): Int = withM3u(p.server, reuse = p.lastSyncAt == 0L) { file ->
        var known = categories.all(p.id, CAT_LIVE)
        if (known.isEmpty()) {
            // Not normally reached (adding a playlist stores its groups), but the defaults need every group up front.
            categoryStore.store(p.id, CAT_LIVE, m3uGroups(file), groupByName = true)
            known = categories.all(p.id, CAT_LIVE)
        }
        // Included: existing groups as the user chose; new groups when their language is one already included
        // (the same rule categoryStore.store applies when it records them below).
        val wanted = categoryStore.includedLangs(known, groupByName = true)
        val include = known.associateTo(HashMap()) { it.categoryId to it.included }
        val groups = LinkedHashSet<String>()
        val out = ChannelWriter(p.id, onProgress)
        val ids = HashSet<Long>()
        var index = 0
        val guides = file.bufferedReader().use { reader ->
            M3uParser.parse(reader) { e ->
                index++
                // Ids are given to every entry, so a duplicate's id doesn't depend on which groups are included.
                var id = stableId("${e.tvgId.orEmpty()}|${e.name}")
                while (!ids.add(id)) id = stableId("$id#") // same channel listed twice
                val group = e.group ?: OTHER_GROUP
                groups += group
                if (!include.getOrPut(group) { categoryStore.langOf(group, groupByName = true) in wanted }) return@parse
                out.add(
                    ChannelEntity(
                        playlistId = p.id, streamId = id, number = e.number ?: index, name = e.name, categoryId = group,
                        logoUrl = e.logo, providerEpgId = e.tvgId?.substringBefore('@'), archiveDays = 0,
                        hidden = out.hidden(id), sortOrder = out.sortOrder(id), syncStamp = out.stamp,
                        streamUrl = e.url, userAgent = e.userAgent, referrer = e.referrer
                    )
                )
            }
        }
        categoryStore.store(p.id, CAT_LIVE, groups.map { XtreamCategory(it, it) }, groupByName = true)
        val count = out.finish()
        guides.firstOrNull()?.let { epg.ensureProviderSource(p, it) }
        count
    }

    /**
     * Writes one sync's channels in batches (keeping the user's hidden/order choices), then removes channels the
     * provider no longer lists and updates category counts.
     */
    private inner class ChannelWriter(private val playlistId: Long, onProgress: (Int) -> Unit) {
        val stamp = System.currentTimeMillis()
        private var prefs: Map<Long, ChannelPrefs>? = null
        private val batch = Batch(BATCH, channels::upsert, onProgress)

        private suspend fun prefs() = prefs ?: channels.prefs(playlistId).associateBy { it.streamId }.also { prefs = it }

        suspend fun hidden(id: Long) = prefs()[id]?.hidden ?: false

        suspend fun sortOrder(id: Long) = prefs()[id]?.sortOrder

        suspend fun add(ch: ChannelEntity) = batch.add(ch)

        suspend fun finish(): Int {
            batch.flush()
            db.withTransaction {
                channels.deleteStale(playlistId, stamp)
                categories.refreshCounts(playlistId, CAT_LIVE)
            }
            playlists.get(playlistId)?.let { playlists.upsert(it.copy(lastSyncAt = stamp)) }
            return batch.count
        }
    }

    /**
     * Runs [block] on a downloaded copy of an M3U playlist. With [reuse], a copy from the last few minutes is used
     * instead, so adding a playlist and then loading its channels downloads it once. Each download goes to its own
     * temp file and is then swapped in whole, so concurrent readers never see a half-written file.
     */
    private suspend fun <T> withM3u(url: String, reuse: Boolean = false, block: suspend (File) -> T): T = withContext(Dispatchers.IO) {
        workDir.mkdirs()
        val now = System.currentTimeMillis()
        // Old copies are only deleted well after they stop being reused, so a slow reader never loses its file.
        workDir.listFiles()?.forEach { if (now - it.lastModified() > KEEP_DOWNLOAD_MS) it.delete() }
        val cached = File(workDir, "playlist-${stableId(url)}.m3u")
        if (!reuse || !cached.exists() || now - cached.lastModified() > REUSE_DOWNLOAD_MS) {
            val temp = File.createTempFile("download", ".tmp", workDir)
            try {
                fetch(url) { body -> temp.outputStream().use { body.byteStream().copyTo(it) } }
                temp.bufferedReader().use { checkPlaylist(it) }
                if (!temp.renameTo(cached)) throw XtreamException("Couldn't save the playlist")
            } finally {
                temp.delete()
            }
        }
        block(cached)
    }

    /** Runs [block] on a successful download's body; failures become readable XtreamExceptions. */
    private fun <T> fetch(url: String, block: (ResponseBody) -> T): T {
        val request = runCatching { Request.Builder().url(url).build() }.getOrNull()
            ?: throw XtreamException("Invalid playlist address")
        return try {
            http.newCall(request).execute().use {
                if (!it.isSuccessful) throw XtreamException("The server refused the playlist download (error ${it.code})")
                block(it.body!!)
            }
        } catch (e: XtreamException) {
            throw e
        } catch (e: IOException) {
            throw XtreamException("Couldn't download the playlist: ${e.message}", e)
        }
    }

    private fun checkPlaylist(reader: BufferedReader) {
        val first = reader.lineSequence().firstOrNull { it.isNotBlank() }
        if (first == null || !M3uParser.looksLikePlaylist(first)) throw XtreamException("That address doesn't return an M3U playlist")
    }

    /** Distinct group titles in playlist order; channels without one go under "Other". */
    private suspend fun m3uGroups(file: File): List<XtreamCategory> {
        val groups = LinkedHashSet<String>()
        file.bufferedReader().use { reader -> M3uParser.parse(reader) { groups += it.group ?: OTHER_GROUP } }
        return groups.map { XtreamCategory(it, it) }
    }

    /** 64-bit FNV-1a hash, kept positive: a stable channel id from text. */
    private fun stableId(key: String): Long {
        var h = -0x340d631b7bdddcdbL
        for (c in key) {
            h = h xor c.code.toLong()
            h *= 0x100000001b3L
        }
        return h and Long.MAX_VALUE
    }

    companion object {
        /**
         * Free, legal, no-sign-up channels collected by iptv-org (github.com/iptv-org/iptv), grouped by country so the
         * language filter can pick the TV's own country. Set up on first launch; users can add their own providers.
         */
        const val FREE_PLAYLIST_URL = "https://iptv-org.github.io/iptv/index.country.m3u"
        const val FREE_PLAYLIST_NAME = "Free TV (iptv-org)"
        private const val REUSE_DOWNLOAD_MS = 10 * 60_000L
        private const val KEEP_DOWNLOAD_MS = 60 * 60_000L

        private const val BATCH = 500
        private const val OTHER_GROUP = "Other"
    }
}
