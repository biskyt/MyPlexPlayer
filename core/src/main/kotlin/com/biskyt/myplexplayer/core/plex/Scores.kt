package com.biskyt.myplexplayer.core.plex

import kotlin.math.roundToInt
import kotlinx.serialization.Serializable

/** One of a title's review scores, as Plex lists them: [image] names the source ("imdb://image.rating"), [value] out of 10. */
@Serializable
data class PlexRating(val image: String? = null, val type: String? = null, val value: Double? = null)

/** Where a review score comes from; each is shown with its own logo. */
enum class ScoreSource { IMDB, ROTTEN_TOMATOES, ROTTEN_TOMATOES_AUDIENCE, TMDB }

/** A review score out of 10 from [source]. */
data class Score(val source: ScoreSource, val value: Double) {
    /** Rotten Tomatoes' own line: a tomato (or upright popcorn) from 60%, a splat (or spilled popcorn) below. */
    val fresh: Boolean get() = percent >= FRESH

    /** As a percentage, whole: what's shown for the sources that write it so. */
    val percent: Int get() = (value * MAX).roundToInt()
}

private const val FRESH = 60

/**
 * [item]'s review scores, one per source, in a fixed order (IMDb, Rotten Tomatoes' critics and audience, TMDB): from
 * Plex's list of them, or, from a server without one, its critic and audience scores, each named by its image.
 */
fun scores(item: PlexItem): List<Score> {
    val listed = item.ratings.ifEmpty {
        listOf(PlexRating(item.ratingImage, "critic", item.rating), PlexRating(item.audienceRatingImage, "audience", item.audienceRating))
    }
    return listed.mapNotNull { r ->
        val source = sourceOf(r.image, r.type) ?: return@mapNotNull null
        // Out of 10; a stray percentage is brought into line.
        val value = r.value?.takeIf { it > 0 }?.let { if (it > MAX) it / MAX else it } ?: return@mapNotNull null
        Score(source, value)
    }.distinctBy { it.source }.sortedBy { it.source }
}

private const val MAX = 10.0

/** The critics' icons; the audience's are popcorn ("upright", "spilled"). */
private val TOMATO = setOf("ripe", "rotten")

private fun sourceOf(image: String?, type: String?): ScoreSource? = when (image?.substringBefore("://")?.lowercase()) {
    "imdb" -> ScoreSource.IMDB
    // Its type, else its image: the tomato's (ripe, rotten) are the critics', the popcorn's (upright, spilled) the audience's.
    "rottentomatoes" -> {
        val critics = type?.let { it == "critic" } ?: (image.orEmpty().substringAfterLast('.') in TOMATO)
        if (critics) ScoreSource.ROTTEN_TOMATOES else ScoreSource.ROTTEN_TOMATOES_AUDIENCE
    }
    "themoviedb" -> ScoreSource.TMDB
    else -> null
}

/** An age rating as shown: Plex's "gb/15" or "de/16" without its country, "PG-13" as it is; null when there's none. */
fun ageRating(contentRating: String?): String? = contentRating?.substringAfter('/')?.trim()?.takeIf { it.isNotEmpty() }
