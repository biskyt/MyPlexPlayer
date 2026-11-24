package com.biskyt.myplexplayer.data

import com.biskyt.myplexplayer.core.plex.Score
import com.biskyt.myplexplayer.core.plex.ScoreSource
import com.biskyt.myplexplayer.core.tmdb.TmdbCredits
import com.biskyt.myplexplayer.core.vod.CastMember

// An episode's own scores and people (MyTVPlayer 1.35.0). MyTVPlayer takes them from TMDB. Here the server comes first,
// and TMDB fills only what the server does not have (the owner, 2026-11-24: "Plex provided data still supersedes TMDB
// data").

/**
 * What the server says of an episode that its listing does not: its review [scores], its own [cast] (the guest stars, in
 * Plex's `Role[]`) and who directed and wrote it. The full episode has these (docs/plex-api.md row 20).
 */
data class EpisodeFacts(
    val scores: List<Score> = emptyList(),
    val cast: List<CastMember> = emptyList(),
    val directors: List<CastMember> = emptyList(),
    val writers: List<CastMember> = emptyList(),
) {
    /** Whether the server has no cast, or no director, or no writer: TMDB's credits can fill the gap. */
    val missesPeople: Boolean get() = cast.isEmpty() || directors.isEmpty() || writers.isEmpty()
}

/**
 * An episode's scores and its Cast and crew as its options and its description show them. [credits] is null when
 * nobody is known. A TMDB person has no id here, so their page does not open (a person's page needs a server id).
 */
data class EpisodeExtras(val scores: List<Score>, val credits: TmdbCredits?)

/**
 * [server]'s facts (null: the server did not answer), with TMDB's in each part the server leaves empty: its score
 * ([tmdbScore], out of 10) and its credits ([tmdb]).
 */
fun episodeExtras(server: EpisodeFacts?, tmdbScore: Double?, tmdb: TmdbCredits?): EpisodeExtras {
    val s = server ?: EpisodeFacts()
    val t = tmdb?.withoutIds()
    // The server's cast is the episode's own people (its guest stars): with it, TMDB's cast is not added.
    val ownCast = s.cast.isNotEmpty()
    val credits = TmdbCredits(
        cast = if (ownCast) s.cast else t?.cast.orEmpty(),
        guestStars = if (ownCast) emptyList() else t?.guestStars.orEmpty(),
        directors = s.directors.ifEmpty { t?.directors.orEmpty() },
        writers = s.writers.ifEmpty { t?.writers.orEmpty() },
    )
    val scores = s.scores.ifEmpty { listOfNotNull(tmdbScore?.let { Score(ScoreSource.TMDB, it) }) }
    return EpisodeExtras(scores, credits.takeUnless { it.isEmpty })
}

/** These credits with no person ids: TMDB's ids are not the server's. */
private fun TmdbCredits.withoutIds() = TmdbCredits(cast.noIds(), guestStars.noIds(), directors.noIds(), writers.noIds())

private fun List<CastMember>.noIds() = map { it.copy(id = null, tagKey = null) }
