package com.biskyt.mytvplayer.data

import com.biskyt.mytvplayer.data.db.AppDatabase
import com.biskyt.mytvplayer.data.db.ChannelEntity
import com.biskyt.mytvplayer.data.db.ProgrammeHit
import com.biskyt.mytvplayer.data.db.VodCard

data class SearchResults(
    val channels: List<ChannelEntity> = emptyList(),
    val programmes: List<ProgrammeHit> = emptyList(),
    val movies: List<VodCard> = emptyList(),
    val series: List<VodCard> = emptyList()
) {
    val isEmpty: Boolean get() = channels.isEmpty() && programmes.isEmpty() && movies.isEmpty() && series.isEmpty()
}

/** One search across channels, upcoming TV programmes, movies and series of a playlist. */
class SearchRepository(private val db: AppDatabase, private val clock: () -> Long = System::currentTimeMillis) {
    suspend fun search(playlistId: Long, query: String): SearchResults {
        val q = query.trim()
        // Single letters would match nearly everything, but a single digit is a channel number.
        if (q.isEmpty() || (q.length < MIN_LENGTH && !q[0].isDigit())) return SearchResults()
        val channels = db.channels().search(playlistId, q, LIMIT)
        if (q.length < MIN_LENGTH) return SearchResults(channels = channels)
        return SearchResults(
            channels = channels,
            programmes = db.epg().searchProgrammes(playlistId, q, clock(), LIMIT),
            movies = db.vod().searchMovies(playlistId, q, LIMIT),
            series = db.vod().searchSeries(playlistId, q, LIMIT)
        )
    }

    private companion object {
        const val MIN_LENGTH = 2
        const val LIMIT = 30
    }
}
