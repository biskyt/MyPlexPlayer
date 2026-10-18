package com.biskyt.mytvplayer.data

import com.biskyt.mytvplayer.data.db.AppDatabase
import com.biskyt.mytvplayer.data.db.ChannelEntity
import com.biskyt.mytvplayer.data.db.ProgrammeHit
import com.biskyt.mytvplayer.data.db.VodCard
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

data class SearchResults(
    val channels: List<ChannelEntity> = emptyList(),
    val programmes: List<ProgrammeHit> = emptyList(),
    val movies: List<VodCard> = emptyList(),
    val series: List<VodCard> = emptyList()
) {
    val isEmpty: Boolean get() = channels.isEmpty() && programmes.isEmpty() && movies.isEmpty() && series.isEmpty()
}

/** One search across channels, upcoming TV programmes, movies and series of a playlist. [query] is already trimmed. */
class SearchRepository(private val db: AppDatabase, private val clock: () -> Long = System::currentTimeMillis) {
    suspend fun search(playlistId: Long, query: String): SearchResults = coroutineScope {
        // A single digit is a channel number; otherwise single letters would match nearly everything.
        if (query.length < MIN_LENGTH) {
            val number = query.singleOrNull()?.isDigit() == true
            return@coroutineScope if (number) SearchResults(channels = db.channels().search(playlistId, query, LIMIT)) else SearchResults()
        }
        // The lists are independent; the database reads them side by side.
        val channels = async { db.channels().search(playlistId, query, LIMIT) }
        val movies = async { db.vod().searchMovies(playlistId, query, LIMIT) }
        val series = async { db.vod().searchSeries(playlistId, query, LIMIT) }
        // The guide is the slowest to scan (every stored programme), so it waits for a more specific query.
        val programmes = async {
            if (query.length >= PROGRAMME_MIN_LENGTH) db.epg().searchProgrammes(playlistId, query, clock(), LIMIT) else emptyList()
        }
        SearchResults(channels.await(), programmes.await(), movies.await(), series.await())
    }

    private companion object {
        const val MIN_LENGTH = 2
        const val PROGRAMME_MIN_LENGTH = 3
        const val LIMIT = 30
    }
}
