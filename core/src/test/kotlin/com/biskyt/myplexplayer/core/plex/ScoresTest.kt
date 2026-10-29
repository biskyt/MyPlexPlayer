package com.biskyt.myplexplayer.core.plex

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScoresTest {
    private fun item(json: String) = plexJson.decodeFromString(PlexItem.serializer(), json)

    @Test
    fun `each source's score, from Plex's list, in the order shown`() {
        val scores = scores(
            item(
                """{"ratingKey":"1","title":"t","Rating":[
                {"image":"themoviedb://image.rating","type":"audience","value":7.5},
                {"image":"rottentomatoes://image.rating.upright","type":"audience","value":8.7},
                {"image":"imdb://image.rating","type":"audience","value":7.6},
                {"image":"rottentomatoes://image.rating.ripe","type":"critic","value":9.4},
                {"image":"imdb://image.rating","type":"audience","value":7.6},
                {"image":"somewhere://else","type":"critic","value":5.0}]}"""
            )
        )
        assertEquals(
            listOf(
                Score(ScoreSource.IMDB, 7.6),
                Score(ScoreSource.ROTTEN_TOMATOES, 9.4),
                Score(ScoreSource.ROTTEN_TOMATOES_AUDIENCE, 8.7),
                Score(ScoreSource.TMDB, 7.5)
            ),
            scores
        )
    }

    @Test
    fun `without a list, the critic and audience scores named by their images`() {
        val scores = scores(
            item(
                """{"ratingKey":"1","title":"t","rating":5.5,"ratingImage":"rottentomatoes://image.rating.rotten",
                "audienceRating":62,"audienceRatingImage":"rottentomatoes://image.rating.upright"}"""
            )
        )
        assertEquals(listOf(Score(ScoreSource.ROTTEN_TOMATOES, 5.5), Score(ScoreSource.ROTTEN_TOMATOES_AUDIENCE, 6.2)), scores)
        assertTrue("none at all", scores(item("""{"ratingKey":"1","title":"t","rating":7.0}""")).isEmpty())
    }

    @Test
    fun `fresh from 60 percent, as shown`() {
        assertTrue(Score(ScoreSource.ROTTEN_TOMATOES, 6.0).fresh)
        assertTrue("shown as 60%", Score(ScoreSource.ROTTEN_TOMATOES, 5.96).fresh)
        assertFalse(Score(ScoreSource.ROTTEN_TOMATOES, 5.9).fresh)
    }

    @Test
    fun `without its type, a Rotten Tomatoes score is the critics' or the audience's by its icon`() {
        val scores = scores(
            item(
                """{"ratingKey":"1","title":"t","Rating":[
                {"image":"rottentomatoes://image.rating.rotten","value":4.0},
                {"image":"rottentomatoes://image.rating.upright","value":8.0}]}"""
            )
        )
        assertEquals(listOf(ScoreSource.ROTTEN_TOMATOES, ScoreSource.ROTTEN_TOMATOES_AUDIENCE), scores.map { it.source })
    }

    @Test
    fun `an age rating without its country`() {
        assertEquals("15", ageRating("gb/15"))
        assertEquals("12A", ageRating("gb/12A"))
        assertEquals("PG-13", ageRating("PG-13"))
        assertNull(ageRating(""))
        assertNull(ageRating(null))
    }
}
