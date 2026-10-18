package com.biskyt.mytvplayer.data

import androidx.room.withTransaction
import com.biskyt.mytvplayer.core.epg.CategoryLanguageDetector
import com.biskyt.mytvplayer.core.xtream.XtreamClient
import com.biskyt.mytvplayer.core.xtream.XtreamCredentials
import com.biskyt.mytvplayer.core.xtream.XtreamLiveStream
import com.biskyt.mytvplayer.data.db.AppDatabase
import com.biskyt.mytvplayer.data.db.CategoryEntity
import com.biskyt.mytvplayer.data.db.ChannelEntity
import com.biskyt.mytvplayer.data.db.PlaylistEntity
import kotlinx.coroutines.flow.Flow
import okhttp3.OkHttpClient
import java.util.Locale

/** Playlists (Xtream accounts) and downloading their channel lists. */
class PlaylistRepository(
    private val db: AppDatabase,
    private val http: OkHttpClient,
    private val settings: SettingsRepository,
    private val epg: EpgRepository,
    private val locale: () -> Locale = Locale::getDefault,
) {
    private val playlists = db.playlists()
    private val categories = db.categories()
    private val channels = db.channels()

    fun observePlaylists(): Flow<List<PlaylistEntity>> = playlists.observeAll()

    suspend fun get(id: Long): PlaylistEntity? = playlists.get(id)

    suspend fun allIds(): List<Long> = playlists.all().map { it.id }

    fun client(p: PlaylistEntity) = XtreamClient(http, XtreamCredentials(p.server, p.username, p.password))

    /** Checks the login works; throws XtreamException with a readable message otherwise. */
    suspend fun testLogin(server: String, username: String, password: String) {
        val account = XtreamClient(http, XtreamCredentials(server, username, password)).account()
        if (!account.active) throw com.biskyt.mytvplayer.core.xtream.XtreamException("This account is not active")
    }

    /** Saves a new playlist (after testing the login), downloads its categories and makes it the active one. */
    suspend fun add(name: String, server: String, username: String, password: String): Long {
        testLogin(server, username, password)
        val id = playlists.insert(PlaylistEntity(name = name.ifBlank { "My TV" }, server = server.trim(), username = username.trim(), password = password))
        try {
            refreshCategories(id)
        } catch (e: java.io.IOException) {
            playlists.delete(id) // don't leave a half-added playlist behind; the user can simply try again
            throw e
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
        val remote = client(p).liveCategories()
        val existing = categories.all(playlistId).associateBy { it.categoryId }
        val wanted = if (existing.isEmpty()) defaultGroups() else existing.values.filter { it.included }.mapNotNull { it.lang }.toSet()
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
                channelCount = old?.channelCount ?: 0,
            )
        }
        db.withTransaction {
            categories.upsert(items)
            if (items.isNotEmpty()) categories.deleteOthers(playlistId, items.map { it.categoryId })
        }
    }

    /** Language groups included by default, from the TV's locale (e.g. en-GB → UK). */
    fun defaultGroups(): Set<String> {
        val l = locale()
        val country = when (l.country.uppercase()) {
            "GB" -> "UK"
            "" -> null
            else -> l.country.uppercase()
        }
        val byLanguage = when (l.language) {
            "en" -> "UK"
            "fr" -> "FR"; "de" -> "DE"; "es" -> "ES"; "it" -> "IT"; "pt" -> "PT"; "nl" -> "NL"; "pl" -> "PL"
            "tr" -> "TR"; "ar" -> "AR"; "el" -> "GR"; "ro" -> "RO"; "sv" -> "SE"; "da" -> "DK"; "fi" -> "FI"
            "ru" -> "RU"; "hi" -> "IN"
            else -> null
        }
        return setOfNotNull(country?.takeIf { it in CategoryLanguageDetector.groups }, if (country == null || country !in CategoryLanguageDetector.groups) byLanguage else null)
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
                    hidden = pref?.hidden ?: false, sortOrder = pref?.sortOrder, syncStamp = stamp,
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
        const val BATCH = 500
        const val PER_CATEGORY_LIMIT = 20
    }
}
