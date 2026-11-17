package com.biskyt.myplexplayer.data

import android.util.Log
import androidx.annotation.VisibleForTesting
import com.biskyt.myplexplayer.AppText
import com.biskyt.myplexplayer.core.tmdb.TmdbClient
import com.biskyt.myplexplayer.core.tmdb.TmdbEpisode
import com.biskyt.myplexplayer.core.tmdb.TmdbException
import com.biskyt.myplexplayer.core.tmdb.TmdbSuggestion
import com.biskyt.myplexplayer.core.tmdb.TmdbTitle
import java.io.File
import java.util.Locale
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import okhttp3.Cache
import okhttp3.OkHttpClient

/**
 * "More like this" from TMDB, with the user's own key (Settings › TMDB), and what the server and Discover do not have (a
 * series' finer status and next episode code, a trailer, an episode's missing description or picture). Without a key
 * it does nothing: no requests,
 * and not even its cache is made. Answers are kept for [KEEP_DAYS] days, so a page opened again costs no request. A
 * title is found by its TMDB id, which the server gives (its `tmdb://` guid). A failure gives null. MyTVPlayer's copy
 * also finds a title by its name, for its own film and series pages: MyPlexPlayer does not need that part.
 */
class TmdbRepository(
    private val cacheDir: File,
    private val http: OkHttpClient,
    private val settings: SettingsRepository,
    /** TMDB's address: a test sets a server of its own. */
    @set:VisibleForTesting var base: String = "https://api.themoviedb.org/3/"
) {
    private val cached: OkHttpClient by lazy {
        http.newBuilder()
            .cache(Cache(File(cacheDir, "tmdb"), CACHE_BYTES))
            // TMDB's own answers are kept for a short time only: titles change little, so they are kept for days.
            .addNetworkInterceptor { chain ->
                chain.proceed(chain.request()).newBuilder()
                    .header("Cache-Control", "public, max-age=${KEEP_DAYS * SECONDS_A_DAY}")
                    .removeHeader("Pragma")
                    .build()
            }
            .build()
    }

    /** Whether the user has added a TMDB key: "More like this" is offered only then. */
    val on: Flow<Boolean> = settings.settings.map { it.tmdbKey.isNotBlank() }.distinctUntilChanged()

    private suspend fun client(): TmdbClient? = settings.current().tmdbKey.trim().ifEmpty { null }?.let { client(it) }

    /**
     * A client for [key] in the app's language, putting the TV's country's trailers first. It holds no state: the HTTP
     * client and its cache are shared.
     */
    private fun client(key: String) =
        TmdbClient(cached, key, AppText.locale.toLanguageTag(), base, Locale.getDefault().country.ifEmpty { null })

    /** Film (or, when [series], series) [id]'s page on TMDB; null when TMDB cannot give it (no key, or out of reach). */
    suspend fun title(series: Boolean, id: Int): TmdbTitle? = quietly { if (series) it.series(id) else it.movie(id) }

    /** The episodes of [season] of the TMDB series [seriesId]; null when TMDB cannot give them (no key, or out of reach). */
    suspend fun season(seriesId: Int, season: Int): List<TmdbEpisode>? = quietly { it.season(seriesId, season) }

    /** The films (or, when [series], the series) that TMDB suggests for its title [id]; null when TMDB cannot give them. */
    suspend fun recommendations(series: Boolean, id: Int): List<TmdbSuggestion>? = quietly { it.recommendations(series, id) }

    /** Whether TMDB accepts [key]; null when TMDB cannot be reached. */
    suspend fun keyWorks(key: String): Boolean? = try {
        client(key.trim()).keyWorks()
    } catch (e: TmdbException) {
        Log.w(TAG, "TMDB key check failed: ${e.message}")
        null
    }

    private suspend fun <T> quietly(block: suspend (TmdbClient) -> T?): T? {
        val c = client() ?: return null
        return try {
            block(c)
        } catch (e: TmdbException) {
            Log.w(TAG, "TMDB: ${e.message}")
            null
        }
    }

    private companion object {
        const val TAG = "Tmdb"
        const val KEEP_DAYS = 7
        const val SECONDS_A_DAY = 86_400
        const val CACHE_BYTES = 10L * 1024 * 1024
    }
}
