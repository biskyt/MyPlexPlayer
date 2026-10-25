package com.biskyt.myplexplayer.data

import androidx.room.withTransaction
import com.biskyt.myplexplayer.R
import com.biskyt.myplexplayer.appString
import com.biskyt.myplexplayer.core.xtream.XtreamCategory
import com.biskyt.myplexplayer.core.xtream.XtreamClient
import com.biskyt.myplexplayer.core.xtream.XtreamCredentials
import com.biskyt.myplexplayer.core.xtream.XtreamException
import com.biskyt.myplexplayer.core.xtream.XtreamLiveStream
import com.biskyt.myplexplayer.data.db.AppDatabase
import com.biskyt.myplexplayer.data.db.CAT_LIVE
import com.biskyt.myplexplayer.data.db.CAT_MOVIE
import com.biskyt.myplexplayer.data.db.CAT_SERIES
import com.biskyt.myplexplayer.data.db.CategoryEntity
import com.biskyt.myplexplayer.data.db.ChannelEntity
import com.biskyt.myplexplayer.data.db.ChannelPrefs
import com.biskyt.myplexplayer.data.db.PlaylistEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

/** Sources (Plex servers and Xtream accounts) and downloading Xtream channel lists. */
class PlaylistRepository(
    private val db: AppDatabase,
    private val http: OkHttpClient,
    private val settings: SettingsRepository,
    private val epg: EpgRepository,
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

    /**
     * Reads the Xtream account's expiry date and connection limit. Optional: if it fails, what was known is kept.
     */
    @Suppress("TooGenericExceptionCaught", "SwallowedException") // any failure (network, odd answers) leaves it as it was
    private suspend fun refreshAccount(p: PlaylistEntity) {
        val account = try {
            client(p).account()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return
        }
        playlists.setAccount(p.id, account.expiresAtEpochSec, account.maxConnections)
    }

    /** Checks the login works; throws XtreamException with a readable message otherwise. */
    suspend fun testLogin(server: String, username: String, password: String) {
        val active = withUserXtreamErrors { client(server, username, password).account().active }
        if (!active) throw XtreamException(appString(R.string.data_account_inactive))
    }

    /**
     * Saves a new playlist and makes it the active one. Everything that can fail (login, category download) happens
     * before anything is written, so a failed attempt leaves nothing behind.
     */
    suspend fun add(name: String, server: String, username: String, password: String): Long {
        val c = client(server.trim(), username.trim(), password)
        val active = withUserXtreamErrors { c.account().active }
        if (!active) throw XtreamException(appString(R.string.data_account_inactive))
        val remote = withUserXtreamErrors { c.liveCategories() }
        val id =
            insertAndActivate(PlaylistEntity(name = name, server = server.trim(), username = username.trim(), password = password), remote)
        // Movie and series categories too, so they can be chosen before the first download. Providers without them
        // (or with a broken list) get them on a later sync.
        runCatching { categoryStore.store(id, CAT_MOVIE, c.movieCategories()) }
        runCatching { categoryStore.store(id, CAT_SERIES, c.seriesCategories()) }
        return id
    }

    private suspend fun insertAndActivate(p: PlaylistEntity, remote: List<XtreamCategory>): Long {
        val id = db.withTransaction {
            playlists.insert(
                p.copy(
                    name = p.name.ifBlank { appString(R.string.data_playlist_default_name) }
                )
            ).also { categoryStore.store(it, CAT_LIVE, remote) }
        }
        settings.update { it.copy(activePlaylistId = id) }
        return id
    }

    suspend fun update(p: PlaylistEntity) {
        val old = playlists.get(p.id)
        if (old != null && (old.server != p.server || old.username != p.username || old.password != p.password)) {
            testLogin(p.server, p.username, p.password)
            // Perhaps another provider now: what the old one fell back to may not apply.
            settings.forgetStreamFormat(p.id)
        }
        playlists.upsert(p)
    }

    /**
     * Hides a category, as unticking it in Settings › Languages & categories does: its channels (or titles) go now,
     * without downloading anything, and updates leave it out.
     */
    suspend fun hideCategory(cat: CategoryEntity) {
        val categories = db.categories()
        db.withTransaction {
            categories.setIncluded(cat.playlistId, cat.type, listOf(cat.categoryId), false)
            when (cat.type) {
                CAT_LIVE -> db.channels().deleteCategory(cat.playlistId, cat.categoryId)
                CAT_MOVIE -> db.vod().deleteMovieCategory(cat.playlistId, cat.categoryId)
                else -> db.vod().deleteSeriesCategory(cat.playlistId, cat.categoryId)
            }
            categories.refreshCounts(cat.playlistId, cat.type)
        }
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
            playlists.deleteEpisodes(id)
            playlists.deleteProgress(id)
            playlists.deleteVodFavourites(id)
            playlists.delete(id)
        }
        db.epg().providerSource(id)?.let { epg.removeSource(it.id) }
        settings.forgetStreamFormat(id)
        val next = playlists.all().firstOrNull()?.id ?: 0
        if (settings.current().activePlaylistId == id) settings.update { it.copy(activePlaylistId = next) }
    }

    /**
     * Downloads the category list. Existing choices are kept; new categories are included when their detected
     * language group matches one the user already includes (or, the first time, the TV's own country/language).
     */
    suspend fun refreshCategories(playlistId: Long) {
        val p = playlists.get(playlistId) ?: return
        categoryStore.store(playlistId, CAT_LIVE, client(p).liveCategories())
    }

    /**
     * Downloads the channels of included categories. Only a few categories → one request each;
     * otherwise one full request, dropping excluded rows as they stream in. Returns the channel count.
     */
    suspend fun syncChannels(playlistId: Long, onProgress: (Int) -> Unit = {}): Int {
        val p = playlists.get(playlistId) ?: return 0
        // Live TV from Plex is roadmap step 6: until then, its films and series are all there is (see VodRepository).
        if (p.isPlex) return 0
        // Alongside the download: a slow account answer mustn't hold up the channels.
        return coroutineScope {
            launch { refreshAccount(p) }
            syncXtream(p, playlistId, onProgress)
        }
    }

    private suspend fun syncXtream(p: PlaylistEntity, playlistId: Long, onProgress: (Int) -> Unit): Int {
        val client = client(p)
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
            playlists.setLastSync(playlistId, stamp)
            return batch.count
        }
    }

    companion object {
        private const val BATCH = 500
    }
}
