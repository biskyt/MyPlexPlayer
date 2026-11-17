package com.biskyt.myplexplayer.data

import com.biskyt.myplexplayer.R
import com.biskyt.myplexplayer.appString
import com.biskyt.myplexplayer.core.tmdb.TmdbSuggestion
import com.biskyt.myplexplayer.core.vod.VodTitles
import com.biskyt.myplexplayer.data.db.CAT_MOVIE
import com.biskyt.myplexplayer.data.db.CAT_SERIES
import com.biskyt.myplexplayer.data.db.VodCard
import java.io.IOException
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A title that TMDB suggests, with the server's [card] for it; null when the server does not have it. */
data class SimilarTitle(val suggestion: TmdbSuggestion, val card: VodCard?)

/** A "More like this" page: the titles TMDB suggests, in TMDB's order. Empty when TMDB has no suggestions. */
data class SimilarPage(val titles: List<SimilarTitle>)

/** "More like this" pages, from TMDB. Nothing is saved: TMDB's answers are kept for days (see [TmdbRepository]). */
class SimilarRepository(private val vod: VodRepository, private val tmdb: TmdbRepository) {
    /**
     * The titles that TMDB suggests for film (or, when [series], series) [tmdbId], found in the films or series of
     * server [playlistId] that the app has (no request to the server). Fails when TMDB cannot give them (no key, or out
     * of reach).
     */
    suspend fun page(playlistId: Long, series: Boolean, tmdbId: Int): SimilarPage {
        val suggestions = tmdb.recommendations(series, tmdbId) ?: throw IOException(appString(R.string.vod_similar_failed))
        if (suggestions.isEmpty()) return SimilarPage(emptyList())
        val cards = vod.cards(playlistId, if (series) CAT_SERIES else CAT_MOVIE)
        // A server can have tens of thousands of titles: they are matched away from the main thread.
        return withContext(Dispatchers.Default) { similarPage(suggestions, cards, series) }
    }
}

/**
 * Finds each of [suggestions] in [cards] (the server's films, or its [series]). A card goes with the first suggestion
 * that finds it only: two TMDB titles of the same name do not both open it, and each gets a card of its own when the
 * server has two.
 */
fun similarPage(suggestions: List<TmdbSuggestion>, cards: List<VodCard>, series: Boolean): SimilarPage {
    val playlist = PlaylistMatcher(cards, series)
    val used = HashSet<Long>()
    return SimilarPage(
        suggestions.map { s -> SimilarTitle(s, playlist.find(s.title, s.originalTitle, s.year, used)?.also { used.add(it.id) }) }
    )
}

/**
 * Finds TMDB's titles in the server's films or series ([cards]), as MyTVPlayer finds them in a playlist (its
 * PersonRepository.kt). A title matches a title there with the same key (see VodTitles.key) as its title, or its
 * original title (TMDB's title is in the app's language, the server's often the original). A film's year must be the
 * same, a year either way. A [series]' year is not compared: sources give the first season's year, the newest season's,
 * or the year it was added.
 */
class PlaylistMatcher(cards: List<VodCard>, private val series: Boolean) {
    private val byKey = cards.groupBy { VodTitles.key(it.title) }

    /**
     * The server's card for TMDB's [title] or [originalTitle] from [year], other than those in [taken] (two series of
     * one name each get their own card); null when it has none.
     */
    fun find(title: String, originalTitle: String?, year: Int?, taken: Set<Long> = emptySet()): VodCard? =
        listOfNotNull(title, originalTitle).asSequence()
            .flatMap { byKey[VodTitles.key(it)].orEmpty() }
            .filter { it.id !in taken }
            .firstOrNull { card -> series || card.year == null || year == null || abs(card.year - year) <= YEAR_SLACK }
}

/** How many years a server's title may be from TMDB's. */
private const val YEAR_SLACK = 1
