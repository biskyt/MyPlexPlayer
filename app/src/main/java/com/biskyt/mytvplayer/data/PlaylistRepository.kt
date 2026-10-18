package com.biskyt.mytvplayer.data

import androidx.room.withTransaction
import com.biskyt.mytvplayer.core.epg.CategoryLanguageDetector
import com.biskyt.mytvplayer.core.m3u.M3uParser
import com.biskyt.mytvplayer.core.xtream.XtreamCategory
import com.biskyt.mytvplayer.core.xtream.XtreamClient
import com.biskyt.mytvplayer.core.xtream.XtreamCredentials
import com.biskyt.mytvplayer.core.xtream.XtreamException
import com.biskyt.mytvplayer.core.xtream.XtreamLiveStream
import com.biskyt.mytvplayer.data.db.AppDatabase
import com.biskyt.mytvplayer.data.db.CategoryEntity
import com.biskyt.mytvplayer.data.db.ChannelEntity
import com.biskyt.mytvplayer.data.db.KIND_M3U
import com.biskyt.mytvplayer.data.db.PlaylistEntity
import java.io.File
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/** Playlists (Xtream accounts and M3U playlists) and downloading their channel lists. */
class PlaylistRepository(
    private val db: AppDatabase,
    private val http: OkHttpClient,
    private val settings: SettingsRepository,
    private val epg: EpgRepository,
    /** Where M3U playlists are downloaded to while being read (deleted afterwards). */
    private val workDir: File,
    private val locale: () -> Locale = Locale::getDefault
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
        val id = db.withTransaction {
            val newId = playlists.insert(
                PlaylistEntity(
                    name = name.ifBlank {
                        "My TV"
                    },
                    server = server.trim(),
                    username = username.trim(),
                    password = password
                )
            )
            storeCategories(newId, remote)
            newId
        }
        settings.update { it.copy(activePlaylistId = id) }
        return id
    }

    /** Saves a new M3U playlist (after checking it can be downloaded and read) and makes it the active one. */
    suspend fun addM3u(name: String, url: String): Long {
        val remote = withM3u(url.trim()) { file -> m3uGroups(file) }
        val id = db.withTransaction {
            val newId = playlists.insert(
                PlaylistEntity(
                    name = name.ifBlank {
                        "My TV"
                    },
                    kind = KIND_M3U,
                    server = url.trim(),
                    username = "",
                    password = ""
                )
            )
            storeCategories(newId, remote, groupByName = true)
            newId
        }
        settings.update { it.copy(activePlaylistId = id) }
        return id
    }

    /** Checks an M3U URL can be downloaded and looks like a playlist. */
    suspend fun testM3u(url: String) {
        withM3u(url.trim()) { }
    }

    suspend fun update(p: PlaylistEntity) {
        val old = playlists.get(p.id)
        if (old != null && (old.server != p.server || old.username != p.username || old.password != p.password)) {
            if (p.isM3u) testM3u(p.server) else testLogin(p.server, p.username, p.password)
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
        storeCategories(playlistId, remote, groupByName = p.isM3u)
    }

    /**
     * [groupByName]: M3U playlists often group by country or genre ("Austria", "News"); when no language is detected
     * the group's own name is used, so each still appears in the language list.
     */
    private suspend fun storeCategories(playlistId: Long, remote: List<XtreamCategory>, groupByName: Boolean = false) {
        val existing = categories.all(playlistId).associateBy { it.categoryId }
        val wanted = if (existing.isEmpty()) {
            defaultGroups(remote.mapNotNullTo(HashSet()) { CategoryLanguageDetector.detect(it.name) ?: it.name.takeIf { groupByName } })
        } else {
            existing.values.filter { it.included }.mapNotNull { it.lang }.toSet()
        }
        val items = remote.mapIndexed { i, c ->
            val old = existing[c.id]
            val lang = CategoryLanguageDetector.detect(c.name) ?: c.name.takeIf { groupByName }
            CategoryEntity(
                playlistId = playlistId,
                categoryId = c.id,
                name = c.name,
                lang = lang,
                included = old?.included ?: (lang != null && lang in wanted),
                sortOrder = old?.sortOrder ?: (existing.size + i),
                channelCount = old?.channelCount ?: 0
            )
        }
        db.withTransaction {
            categories.upsert(items)
            if (items.isNotEmpty()) categories.deleteOthers(playlistId, items.map { it.categoryId })
        }
    }

    /**
     * Language groups included by default: the TV's own country (e.g. en-GB → UK) when the provider has it,
     * otherwise every group of the TV's language (e.g. en-US with a UK-only provider → UK). The user can change this.
     */
    fun defaultGroups(available: Set<String>): Set<String> {
        val l = locale()
        val country = if (l.country.equals("GB", ignoreCase = true)) "UK" else l.country.uppercase()
        if (country in available) return setOf(country)
        // M3U playlists grouped by country name ("Austria") rather than code.
        val countryName = Locale("", l.country).getDisplayCountry(Locale.ENGLISH)
        if (countryName.isNotEmpty() && countryName in available) return setOf(countryName)
        return languageGroups[l.language].orEmpty().intersect(available)
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
        val all = categories.all(playlistId)
        val included = all.filter { it.included }.map { it.categoryId }.toSet()
        val stamp = System.currentTimeMillis()
        val prefs = channels.prefs(playlistId).associateBy { it.streamId }
        val batch = ArrayList<ChannelEntity>(BATCH)
        var count = 0

        suspend fun flush() {
            if (batch.isEmpty()) return
            channels.upsert(batch.toList())
            batch.clear()
            onProgress(count)
        }

        val collect: suspend (XtreamLiveStream) -> Unit = { s ->
            if (s.categoryId in included) {
                val pref = prefs[s.streamId]
                batch += ChannelEntity(
                    playlistId = playlistId, streamId = s.streamId, number = s.number, name = s.name, categoryId = s.categoryId,
                    logoUrl = s.logoUrl, providerEpgId = s.epgChannelId, archiveDays = s.archiveDays,
                    hidden = pref?.hidden ?: false, sortOrder = pref?.sortOrder, syncStamp = stamp
                )
                count++
                if (batch.size >= BATCH) flush()
            }
        }

        if (included.size in 1..PER_CATEGORY_LIMIT && included.size * 2 < all.size) {
            for (cat in included) client.liveStreams(cat, collect)
        } else if (included.isNotEmpty()) {
            client.liveStreams(null, collect)
        }
        flush()
        db.withTransaction {
            channels.deleteStale(playlistId, stamp)
            categories.refreshCounts(playlistId)
        }
        playlists.get(playlistId)?.let { playlists.upsert(it.copy(lastSyncAt = stamp)) }
        epg.ensureProviderSource(p, client.xmltvUrl())
        return count
    }

    /**
     * M3U: the whole playlist comes in one file, so it is downloaded once and read twice (categories, then the
     * channels of included categories). Channel ids are derived from the guide id and name so favourites and the
     * user's choices survive providers rewriting stream URLs.
     */
    private suspend fun syncM3u(p: PlaylistEntity, onProgress: (Int) -> Unit): Int = withM3u(p.server, reuse = p.lastSyncAt == 0L) { file ->
        storeCategories(p.id, m3uGroups(file), groupByName = true)
        val included = categories.all(p.id).filter { it.included }.map { it.categoryId }.toHashSet()
        val stamp = System.currentTimeMillis()
        val prefs = channels.prefs(p.id).associateBy { it.streamId }
        val ids = HashSet<Long>()
        val batch = ArrayList<ChannelEntity>(BATCH)
        var index = 0
        var count = 0
        val guides = file.bufferedReader().use { reader ->
            M3uParser.parse(reader) { e ->
                index++
                // Ids are given to every entry, so a duplicate's id doesn't depend on which groups are included.
                var id = stableId("${e.tvgId.orEmpty()}|${e.name}")
                while (!ids.add(id)) id = stableId("$id#") // same channel listed twice
                val group = e.group ?: OTHER_GROUP
                if (group !in included) return@parse
                val pref = prefs[id]
                batch += ChannelEntity(
                    playlistId = p.id, streamId = id, number = e.number ?: index, name = e.name, categoryId = group,
                    logoUrl = e.logo, providerEpgId = e.tvgId?.substringBefore('@'), archiveDays = 0,
                    hidden = pref?.hidden ?: false, sortOrder = pref?.sortOrder, syncStamp = stamp,
                    streamUrl = e.url, userAgent = e.userAgent, referrer = e.referrer
                )
                count++
                if (batch.size >= BATCH) {
                    channels.upsert(batch.toList())
                    batch.clear()
                    onProgress(count)
                }
            }
        }
        if (batch.isNotEmpty()) channels.upsert(batch)
        db.withTransaction {
            channels.deleteStale(p.id, stamp)
            categories.refreshCounts(p.id)
        }
        playlists.get(p.id)?.let { playlists.upsert(it.copy(lastSyncAt = stamp)) }
        guides.firstOrNull()?.let { epg.ensureProviderSource(p, it) }
        count
    }

    /**
     * Runs [block] on a downloaded copy of an M3U playlist. With [reuse], a copy from the last few minutes is used
     * instead, so adding a playlist and then loading its channels downloads it once. Each download goes to its own temp file and is then
     * swapped in whole, so concurrent readers never see a half-written file.
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
                download(url, temp)
                temp.renameTo(cached)
            } finally {
                temp.delete()
            }
        }
        block(cached)
    }

    private fun download(url: String, file: File) {
        val request = runCatching { Request.Builder().url(url).build() }.getOrNull()
            ?: throw XtreamException("Invalid playlist address")
        val ok = try {
            http.newCall(request).execute().use { response ->
                response.isSuccessful.also { if (it) file.outputStream().use { out -> response.body!!.byteStream().copyTo(out) } }
            }
        } catch (e: java.io.IOException) {
            throw XtreamException("Couldn't download the playlist: ${e.message}", e)
        }
        if (!ok) throw XtreamException("The server refused the playlist download")
        val first = file.bufferedReader().use { r -> r.lineSequence().firstOrNull { it.isNotBlank() } }?.trim()?.removePrefix("\uFEFF")
        if (first == null || !(first.startsWith("#EXTM3U") || first.startsWith("#EXTINF"))) {
            throw XtreamException("That address doesn't return an M3U playlist")
        }
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

        /** Category groups that share a language, for the default selection. */
        private val languageGroups = mapOf(
            "en" to setOf("UK", "US", "CA", "AU", "IE"), "fr" to setOf("FR", "BE"), "de" to setOf("DE"),
            "es" to setOf("ES", "MX", "LAT"), "it" to setOf("IT"), "pt" to setOf("PT", "BR"), "nl" to setOf("NL", "BE"),
            "pl" to setOf("PL"), "tr" to setOf("TR"), "ar" to setOf("AR"), "el" to setOf("GR"), "ro" to setOf("RO"),
            "sv" to setOf("SE"), "nb" to setOf("NO"), "no" to setOf("NO"), "da" to setOf("DK"), "fi" to setOf("FI"),
            "ru" to setOf("RU"), "hi" to setOf("IN"), "sq" to setOf("AL"), "zh" to setOf("CN"), "ko" to setOf("KR"), "ja" to setOf("JP"),
        )
        private const val BATCH = 500
        private const val OTHER_GROUP = "Other"
        private const val PER_CATEGORY_LIMIT = 20
    }
}
