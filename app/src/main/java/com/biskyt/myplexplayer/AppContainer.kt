package com.biskyt.myplexplayer

import android.app.Application
import android.content.Context
import android.content.res.Configuration
import android.net.Uri
import android.os.LocaleList
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.disk.directory
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import com.biskyt.myplexplayer.core.net.IpLookup
import com.biskyt.myplexplayer.data.BackupFile
import com.biskyt.myplexplayer.data.BackupRepository
import com.biskyt.myplexplayer.data.CategoryStore
import com.biskyt.myplexplayer.data.ChannelRepository
import com.biskyt.myplexplayer.data.CrashLog
import com.biskyt.myplexplayer.data.EpgRepository
import com.biskyt.myplexplayer.data.ExternalIp
import com.biskyt.myplexplayer.data.Favourites
import com.biskyt.myplexplayer.data.HomeRowsReceiver
import com.biskyt.myplexplayer.data.LauncherSync
import com.biskyt.myplexplayer.data.LauncherTarget
import com.biskyt.myplexplayer.data.Notices
import com.biskyt.myplexplayer.data.PersonRepository
import com.biskyt.myplexplayer.data.PlaylistRepository
import com.biskyt.myplexplayer.data.PlexSources
import com.biskyt.myplexplayer.data.Recordings
import com.biskyt.myplexplayer.data.SearchRepository
import com.biskyt.myplexplayer.data.SettingsRepository
import com.biskyt.myplexplayer.data.SimilarRepository
import com.biskyt.myplexplayer.data.SyncManager
import com.biskyt.myplexplayer.data.TmdbRepository
import com.biskyt.myplexplayer.data.VodRepository
import com.biskyt.myplexplayer.data.VpnMonitor
import com.biskyt.myplexplayer.data.db.AppDatabase
import com.biskyt.myplexplayer.launcher.LauncherHost
import com.biskyt.myplexplayer.launcher.LauncherImages
import com.biskyt.myplexplayer.launcher.LauncherWriter
import com.biskyt.myplexplayer.launcher.TvLauncher
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * How the app names itself to servers. Some hosts (Wikimedia, archive.org) refuse or throttle clients that don't say
 * who they are and how to reach their maker. Sent with downloads, pictures and streams.
 */
val USER_AGENT = "MyPlexPlayer/${BuildConfig.VERSION_NAME} (+https://github.com/biskyt/MyPlexPlayer)"

/** Hand-wired dependencies: small enough that a DI framework would only add start-up time. */
/** How long a request to a server may wait for its answer: a Plex tune waits this long too (see PlayerViewModel). */
const val HTTP_READ_TIMEOUT_MS = 60_000L

/** [launcherWriter]: what writes the cards to the TV's home screen (see LauncherSync); none in the JVM tests. */
class AppContainer(context: Context, launcherWriter: LauncherWriter = TvLauncher(context)) {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val db: AppDatabase = AppDatabase.create(context)
    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(HTTP_READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .retryOnConnectionFailure(true)
        // Downloads and pictures (logos, posters) say who's asking, unless a request already does.
        .addInterceptor { chain ->
            val r = chain.request()
            chain.proceed(if (r.header("User-Agent") == null) r.newBuilder().header("User-Agent", USER_AGENT).build() else r)
        }
        .build()
    val settings = SettingsRepository(context)
    val tmdb = TmdbRepository(context.cacheDir, http, settings)
    val epg = EpgRepository(db)
    private val categoryStore = CategoryStore(db)
    val plex = PlexSources(db, http, settings)
    val playlists = PlaylistRepository(db, settings, epg, categoryStore, plex)

    /**
     * Whether the server in use has Live TV: the Live TV and DVR tabs, and the Guide button, are there only then (the
     * owner, 2026-11-11). True until known (also just after a switch), so that a server that has it never shows them late.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val liveTvShown: StateFlow<Boolean> = settings.activePlaylistId
        .flatMapLatest { playlists.observeHasLiveTv(it).onStart { emit(true) } }
        .stateIn(appScope, SharingStarted.Eagerly, true)

    /** Something that didn't work, said in the corner of the screen. */
    val notices = Notices()
    val channels = ChannelRepository(db, plex, notices)

    // When playback stops (or the user forgets something), Continue watching goes to the TV's home screen at once (the
    // owner, 2026-11-15), not once changes settle. While that is off, only the setting is read.
    val vod = VodRepository(db, playlists, categoryStore, plex, notices) { launcherSync.playbackStopped() }
    val people = PersonRepository(db, plex)
    val similar = SimilarRepository(vod, tmdb)
    val favourites = Favourites(appScope, settings, channels, vod)
    val recordings = Recordings(appScope, playlists, plex, notices)
    val search = SearchRepository(db)
    val sync = SyncManager(appScope, playlists, vod, epg, settings)
    val backup = BackupRepository(db, settings, sync, epg)
    val vpn = VpnMonitor(context, appScope)

    // No pooled connection: one opened before a VPN started would still show the address without it. A short
    // limit on the whole call: a stalled answer must not keep the address away for minutes (it tries again later).
    val externalIp =
        ExternalIp(
            vpn.network,
            IpLookup(
                http.newBuilder().connectionPool(ConnectionPool(0, 1, TimeUnit.SECONDS)).callTimeout(IP_TIMEOUT_S, TimeUnit.SECONDS).build()
            )::lookup,
            appScope
        )
    val backupFile = BackupFile(context)
    val crashLog = CrashLog(context.filesDir, BuildConfig.VERSION_NAME)

    /** A backup file another app (e.g. a file manager's "Open with") handed to MyPlexPlayer, until it's offered to restore. */
    val openedFile = MutableStateFlow<Uri?>(null)

    /** Continue watching and the favourite channels on the TV's home screen: nothing runs while the setting is off. */
    val launcherSync = LauncherSync(context, appScope, settings, db, launcherWriter)

    init {
        // A launcher's request starts the app only while there is something to refresh: the rows on the TV's home screen.
        HomeRowsReceiver.follow(context, appScope, settings.settings.map { it.launcherRows })
    }

    /** A card on the TV's home screen opened the app, until it's shown (see AppRoot). */
    val launcherTarget = MutableStateFlow<LauncherTarget?>(null)

    /** The TV guide, asked for by another app (see MainActivity): AppRoot opens it, then sets this back. */
    val openGuide = MutableStateFlow(false)
}

open class MyPlexApp : Application(), SingletonImageLoader.Factory, LauncherHost {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        // First, so a crash while setting up the rest is recorded too.
        installCrashLog()
        AppText.init(this)
        deviceLocales = resources.configuration.locales
        container = AppContainer(this, launcherWriter())
        scheduleDailyRefresh()
    }

    // The device's language changed: words from AppText follow it (unless a language is chosen in Settings).
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (newConfig.locales != deviceLocales) AppText.init(this)
        deviceLocales = newConfig.locales
    }

    private var deviceLocales: LocaleList? = null

    /** What writes the cards to the TV's home screen: the TV provider (the JVM tests have none). */
    protected open fun launcherWriter(): LauncherWriter = TvLauncher(this)

    protected open fun installCrashLog() = CrashLog(filesDir, BuildConfig.VERSION_NAME).install()

    protected open fun scheduleDailyRefresh() {
        val request = PeriodicWorkRequestBuilder<RefreshWorker>(1, TimeUnit.DAYS)
            .setConstraints(online)
            .build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork("daily-refresh", ExistingPeriodicWorkPolicy.KEEP, request)
    }

    /** A poster or logo for the TV's home screen, fetched as the app fetches its pictures (the server's token, the app's name). */
    override fun launcherImage(url: String): ByteArray? = runCatching {
        container.http.newCall(Request.Builder().url(url).build()).execute().use { r ->
            val source = r.body?.source()?.takeIf { r.isSuccessful } ?: return@use null
            // Not more than a picture can be: a server that sends something else is not read to the end.
            source.request(LauncherImages.MAX_BYTES + 1L)
            if (source.buffer.size > LauncherImages.MAX_BYTES) null else source.readByteArray()
        }
    }.getOrNull()

    override fun republishLauncher() = container.launcherSync.republish()

    /** Channel logos: decoded at row size, small memory cache, disk cache capped so storage stays low. */
    override fun newImageLoader(context: PlatformContext): ImageLoader = ImageLoader.Builder(context)
        .components { add(OkHttpNetworkFetcherFactory(callFactory = { container.http })) }
        .memoryCache { MemoryCache.Builder().maxSizePercent(context, 0.12).build() }
        .diskCache { DiskCache.Builder().directory(context.cacheDir.resolve("logos")).maxSizeBytes(LOGO_CACHE_BYTES).build() }
        .crossfade(false)
        .build()

    companion object {
        const val LOGO_CACHE_BYTES = 30L * 1024 * 1024
    }
}

val Context.container: AppContainer get() = (applicationContext as MyPlexApp).container

/**
 * A launcher's request to refresh the app's rows on the TV's home screen (see HomeRowsReceiver; MyTVLauncher sends it as
 * the TV wakes and as its Home shows, the owner 2026-11-15): what was played elsewhere and the titles added to the
 * server come in (the look the app makes while it is open, with the sync it starts for new titles), then Continue watching and
 * the app's rows are written again. With the rows off, nothing is done. At most once in [MIN_GAP_MS], as any app can ask.
 */
class HomeRowsWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    @Suppress("TooGenericExceptionCaught") // as a sync: the rows are written from what is here whatever went wrong
    override suspend fun doWork(): Result {
        val prefs = applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (System.currentTimeMillis() - prefs.getLong(LAST, 0) in 0 until MIN_GAP_MS) return Result.success()
        val c = applicationContext.container
        val s = c.settings.current()
        if (!s.launcherRows) return Result.success()
        try {
            // The quick look, never the full sync (its 8-day guide is slow on a small server; the daily refresh does
            // it). The films and series sync it starts for new titles is waited for: the rows then have them.
            c.sync.refreshPlayState(s.activePlaylistId, syncIfDue = false, whileOpen = true)?.join()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // (Written from what is here: the next request, or opening the app, looks again.)
            Log.w(TAG, "Could not bring in the server's changes for the home rows", e)
        }
        // Not written again if nothing changed since this process last wrote them.
        c.launcherSync.refresh(again = false)
        // Only once done: a run that WorkManager stopped (the TV went offline) is done in full when it runs again.
        prefs.edit().putLong(LAST, System.currentTimeMillis()).apply()
        return Result.success()
    }

    companion object {
        private const val TAG = "HomeRowsWorker"
        internal const val PREFS = "home-rows"
        private const val LAST = "last-refresh"

        /** MyTVLauncher asks at most once in 5 minutes: a little less, so that its requests are not skipped. */
        const val MIN_GAP_MS = 3 * 60_000L

        fun start(context: Context) {
            val request = OneTimeWorkRequestBuilder<HomeRowsWorker>().setConstraints(online).build()
            // One at a time: a request while one waits or runs is that one.
            WorkManager.getInstance(context).enqueueUniqueWork("home-rows", ExistingWorkPolicy.KEEP, request)
        }
    }
}

/** Work that needs the network: it waits until the TV is online. */
private val online = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

/** Daily refresh of the source in use: its channels, films and series, and its TV guide. */
class RefreshWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val c = applicationContext.container
        c.sync.refreshAllNow()
        // Storage housekeeping: everything else (logos, programmes, recents) is size- or age-limited as it is written.
        // Guide files downloaded until 0.5.2 (added guides, Xtream's), unused since.
        File(applicationContext.filesDir, "epg").deleteRecursively()
        c.db.compactIfWorthwhile()
        return Result.success()
    }
}

/** How long a lookup of the external IP address may take in all, before it is given up (and tried again later). */
private const val IP_TIMEOUT_S = 10L
