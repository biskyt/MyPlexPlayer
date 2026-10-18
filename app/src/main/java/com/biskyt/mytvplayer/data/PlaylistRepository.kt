package com.biskyt.mytvplayer.data

import androidx.room.withTransaction
import com.biskyt.mytvplayer.core.epg.CategoryLanguageDetector
import com.biskyt.mytvplayer.core.xtream.XtreamCategory
import com.biskyt.mytvplayer.core.xtream.XtreamClient
import com.biskyt.mytvplayer.core.xtream.XtreamCredentials
import com.biskyt.mytvplayer.core.xtream.XtreamException
import com.biskyt.mytvplayer.core.xtream.XtreamLiveStream
import com.biskyt.mytvplayer.data.db.AppDatabase
import com.biskyt.mytvplayer.data.db.CategoryEntity
import com.biskyt.mytvplayer.data.db.ChannelEntity
import com.biskyt.mytvplayer.data.db.PlaylistEntity
import java.util.Locale
import kotlinx.coroutines.flow.Flow
import okhttp3.OkHttpClient

/** Playlists (Xtream accounts) and downloading their channel lists. */
class PlaylistRepository(
    private val db: AppDatabase,
    private val http: OkHttpClient,
    private val settings: SettingsRepository,
    private val epg: EpgRepository,
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

    suspend fun update(p: PlaylistEntity) {
        val old = playlists.get(p.id)
        if (old != null && (old.server != p.server || old.username != p.username || old.password != p.password)) {
            testLogin(p.server, p.username, p.password)
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
        storeCategories(playlistId, client(p).liveCategories())
    }

    private suspend fun storeCategories(playlistId: Long, remote: List<XtreamCategory>) {
        val existing = categories.all(playlistId).associateBy { it.categoryId }
        val wanted = if (existing.isEmpty()) {
            defaultGroups(remote.mapNotNullTo(HashSet()) { CategoryLanguageDetector.detect(it.name) })
        } else {
            existing.values.filter { it.included }.mapNotNull { it.lang }.toSet()
        }
        val items = remote.mapIndexed { i, c ->
            val old = existing[c.id]
            val lang = CategoryLanguageDetector.detect(c.name)
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
        return languageGroups[l.language].orEmpty().intersect(available)
    }

    /**
     * Downloads the channels of included categories. Only a few categories → one request each;
     * otherwise one full request, dropping excluded rows as they stream in. Returns the channel count.
     */
    suspend fun syncChannels(playlistId: Long, onProgress: (Int) -> Unit = {}): Int {
        val p = playlists.get(playlistId) ?: return 0
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
        return count
    }

    private companion object {
        /** Category groups that share a language, for the default selection. */
        val languageGroups = mapOf(
            "en" to setOf("UK", "US", "CA", "AU", "IE"), "fr" to setOf("FR", "BE"), "de" to setOf("DE"),
            "es" to setOf("ES", "MX", "LAT"), "it" to setOf("IT"), "pt" to setOf("PT", "BR"), "nl" to setOf("NL", "BE"),
            "pl" to setOf("PL"), "tr" to setOf("TR"), "ar" to setOf("AR"), "el" to setOf("GR"), "ro" to setOf("RO"),
            "sv" to setOf("SE"), "nb" to setOf("NO"), "no" to setOf("NO"), "da" to setOf("DK"), "fi" to setOf("FI"),
            "ru" to setOf("RU"), "hi" to setOf("IN"), "sq" to setOf("AL"), "zh" to setOf("CN"), "ko" to setOf("KR"), "ja" to setOf("JP"),
        )
        const val BATCH = 500
        const val PER_CATEGORY_LIMIT = 20
    }
}
