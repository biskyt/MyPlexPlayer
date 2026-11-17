package com.biskyt.myplexplayer.ui.vod

import com.biskyt.myplexplayer.core.tmdb.TmdbEpisode
import com.biskyt.myplexplayer.core.vod.EpisodeInfo
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test

/** An episode's description and picture come from TMDB only where the server gave none (as MyTVPlayer 1.24.0 fills them). */
class EpisodeFillTest {
    private val tmdb = TmdbEpisode(2, 3, "TMDB's name", "TMDB's words.", "https://image.tmdb.org/still.jpg", LocalDate.of(2020, 1, 1))

    private fun episode(plot: String?, image: String?) =
        EpisodeInfo(23, 2, 3, "Low Water", "mkv", plot, 3_420, image, airDate = LocalDate.of(2023, 3, 7))

    @Test
    fun `a blank description and a missing picture are TMDB's`() {
        val filled = episode(plot = " ", image = null).filledFrom(tmdb)
        assertEquals("TMDB's words.", filled.plot)
        assertEquals("https://image.tmdb.org/still.jpg", filled.imageUrl)
    }

    @Test
    fun `the server's description, picture, name and date stay`() {
        val server = episode(plot = "The server's words.", image = "http://server/thumb")
        assertEquals(server, server.filledFrom(tmdb))
        assertEquals(episode(null, null).copy(plot = "TMDB's words.", imageUrl = tmdb.stillUrl), episode(null, null).filledFrom(tmdb))
    }

    @Test
    fun `an episode TMDB does not have stays as it is`() {
        val bare = episode(plot = null, image = null)
        assertEquals(bare, bare.filledFrom(null))
    }
}
