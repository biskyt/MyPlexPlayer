package com.biskyt.myplexplayer.data

import com.biskyt.myplexplayer.core.plex.Score
import com.biskyt.myplexplayer.core.plex.ScoreSource
import com.biskyt.myplexplayer.core.tmdb.TmdbCredits
import com.biskyt.myplexplayer.core.vod.CastMember
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** An episode's scores and people: the server's first, TMDB's only where the server has none (the owner, 2026-11-24). */
class EpisodeFactsTest {
    private fun person(name: String, id: Long? = null) = CastMember(name, null, null, id)

    private val tmdb = TmdbCredits(
        cast = listOf(person("Regular", 1)),
        guestStars = listOf(person("Guest", 2)),
        directors = listOf(person("TMDB Director", 3)),
        writers = listOf(person("TMDB Writer", 4)),
    )

    @Test
    fun `the server's people and score win, and TMDB fills only the empty parts`() {
        val server = EpisodeFacts(
            scores = listOf(Score(ScoreSource.IMDB, 7.4)),
            cast = listOf(person("Server Guest", 10)),
            directors = listOf(person("Server Director", 11)),
        )
        val x = episodeExtras(server, tmdbScore = 8.1, tmdb = tmdb)
        assertEquals(listOf(Score(ScoreSource.IMDB, 7.4)), x.scores)
        val c = x.credits!!
        assertEquals(listOf("Server Guest"), c.cast.map { it.name })
        assertEquals(emptyList<CastMember>(), c.guestStars)
        assertEquals(listOf("Server Director"), c.directors.map { it.name })
        // The server has no writer: TMDB's, with no id (TMDB's ids are not the server's: no page opens).
        assertEquals(listOf(person("TMDB Writer")), c.writers)
    }

    @Test
    fun `with nothing from the server, TMDB's score and people, without their ids`() {
        val x = episodeExtras(null, tmdbScore = 8.1, tmdb = tmdb)
        assertEquals(listOf(Score(ScoreSource.TMDB, 8.1)), x.scores)
        val c = x.credits!!
        assertEquals(listOf(person("Regular")), c.cast)
        assertEquals(listOf(person("Guest")), c.guestStars)
        assertEquals(listOf(null, null), (c.directors + c.writers).map { it.id })
    }

    @Test
    fun `nobody known gives no credits`() {
        val x = episodeExtras(EpisodeFacts(), tmdbScore = null, tmdb = null)
        assertNull(x.credits)
        assertEquals(emptyList<Score>(), x.scores)
    }
}
