package com.biskyt.mytvplayer

import android.app.Application
import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
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
import com.biskyt.mytvplayer.data.BackupFile
import com.biskyt.mytvplayer.data.BackupRepository
import com.biskyt.mytvplayer.data.CatchupRepository
import com.biskyt.mytvplayer.data.CategoryStore
import com.biskyt.mytvplayer.data.ChannelRepository
import com.biskyt.mytvplayer.data.CrashLog
import com.biskyt.mytvplayer.data.EpgRepository
import com.biskyt.mytvplayer.data.PlaylistRepository
import com.biskyt.mytvplayer.data.SearchRepository
import com.biskyt.mytvplayer.data.SettingsRepository
import com.biskyt.mytvplayer.data.SyncManager
import com.biskyt.mytvplayer.data.VodRepository
import com.biskyt.mytvplayer.data.db.AppDatabase
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient

/** Hand-wired dependencies: small enough that a DI framework would only add start-up time. */
class AppContainer(context: Context) {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val db: AppDatabase = AppDatabase.create(context)
    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()
    val settings = SettingsRepository(context)
    val epg = EpgRepository(db, http, context.filesDir, appScope)
    private val categoryStore = CategoryStore(db)
    val playlists = PlaylistRepository(db, http, settings, epg, context.cacheDir.resolve("playlists"), categoryStore)
    val channels = ChannelRepository(db)
    val vod = VodRepository(db, playlists, categoryStore)
    val search = SearchRepository(db)
    val catchup = CatchupRepository(playlists)
    val sync = SyncManager(appScope, playlists, vod, epg)
    val backup = BackupRepository(db, settings, epg, sync)
    val backupFile = BackupFile(context)
    val crashLog = CrashLog(context.filesDir, BuildConfig.VERSION_NAME)
}

open class MyTvApp : Application(), SingletonImageLoader.Factory {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        // First, so a crash while setting up the rest is recorded too.
        installCrashLog()
        container = AppContainer(this)
        scheduleDailyRefresh()
    }

    protected open fun installCrashLog() = CrashLog(filesDir, BuildConfig.VERSION_NAME).install()

    protected open fun scheduleDailyRefresh() {
        val request = PeriodicWorkRequestBuilder<RefreshWorker>(1, TimeUnit.DAYS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork("daily-refresh", ExistingPeriodicWorkPolicy.KEEP, request)
    }

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

val Context.container: AppContainer get() = (applicationContext as MyTvApp).container

/** Daily refresh of every playlist's channels and the TV guide. */
class RefreshWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val c = applicationContext.container
        c.sync.refreshAllNow()
        // Storage housekeeping: everything else (logos, programmes, recents) is size- or age-limited as it is written.
        c.epg.deleteOrphanFiles()
        c.db.compactIfWorthwhile()
        return Result.success()
    }
}
