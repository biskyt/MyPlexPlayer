package com.biskyt.myplexplayer.testutil

import com.biskyt.myplexplayer.AppContainer
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest

/**
 * TMDB as the app reads it for "More like this", for the board's titles ([BoardTitles]): The Long Harbour is film
 * [FILM], Coastline is series [SHOW] (each a TMDB id that [FakePlex.tmdbIds] gives the server's title). Only those two
 * have suggestions. [use] points [AppContainer.tmdb] at it, with a test key (never a real one). As MyTVPlayer's FakeTmdb,
 * with MyPlexPlayer's titles.
 */
class FakeTmdb : Dispatcher() {
    private val server = MockWebServer().also {
        it.dispatcher = this
        it.start()
    }

    /** The paths asked for, in order. */
    val requests: MutableList<String> = CopyOnWriteArrayList()

    /** Points [c]'s TMDB at this server; with [key], TMDB is on, as with a key in Settings. */
    fun use(c: AppContainer, key: String? = TEST_KEY) = runBlocking {
        c.tmdb.base = server.url("/3/").toString()
        if (key != null) c.settings.update { it.copy(tmdbKey = key) }
    }

    fun close() = server.shutdown()

    override fun dispatch(request: RecordedRequest): MockResponse {
        val path = request.requestUrl!!.encodedPath.removePrefix("/3/")
        requests += path
        val body = when (path) {
            // Only the test key is accepted.
            "authentication" -> if (request.requestUrl!!.queryParameter("api_key") == TEST_KEY) "{}" else return refused()
            "movie/$FILM/recommendations" -> FILM_LIKE
            "tv/$SHOW/recommendations" -> SHOW_LIKE
            "movie/$PAPER_BOATS" -> PAPER_BOATS_PAGE
            "tv/$SIGNAL_HILL" -> SIGNAL_HILL_PAGE
            else -> return MockResponse().setResponseCode(404).setBody("""{"status_code":34}""")
        }
        return MockResponse().setBody(body)
    }

    private fun refused() = MockResponse().setResponseCode(401).setBody("""{"status_code":7}""")

    companion object {
        /** 32 characters, as a v3 key has: not a real key. */
        const val TEST_KEY = "00000000000000000000000000000000"

        /** The Long Harbour's TMDB id. */
        const val FILM = 5100

        /** Coastline's TMDB id. */
        const val SHOW = 6100

        /** Paper Boats' TMDB id: suggested for The Long Harbour, not on the server, with a TMDB page and trailer. */
        const val PAPER_BOATS = 5200

        /** Signal Hill's TMDB id: suggested for Coastline, not on the server, with a TMDB page. */
        const val SIGNAL_HILL = 6200

        const val TRAILER = "pbtrailer01"

        /** More like The Long Harbour: Sintel is on the server (FakePlex's film 12), Paper Boats is not. */
        private const val FILM_LIKE =
            """{"page":1,"results":[{"id":45745,"title":"Sintel","release_date":"2010-09-27"},""" +
                """{"id":$PAPER_BOATS,"title":"Paper Boats","release_date":"2018-03-02","vote_average":7.6,"vote_count":1900}]}"""

        /** More like Coastline: Signal Hill is not on the server. */
        private const val SHOW_LIKE =
            """{"page":1,"results":[{"id":$SIGNAL_HILL,"name":"Signal Hill","first_air_date":"2020-04-29",""" +
                """"vote_average":8.3,"vote_count":500}]}"""

        private const val PAPER_BOATS_PAGE =
            """{"id":$PAPER_BOATS,"title":"Paper Boats","overview":"Two sisters sail home.","release_date":"2018-03-02",""" +
                """"vote_average":7.6,"vote_count":1900,"genres":[{"name":"Drama"}],""" +
                """"credits":{"crew":[{"name":"Ana Ruiz","job":"Director"}]},""" +
                """"videos":{"results":[{"key":"$TRAILER","site":"YouTube","type":"Trailer","official":true}]}}"""

        private const val SIGNAL_HILL_PAGE =
            """{"id":$SIGNAL_HILL,"name":"Signal Hill","overview":"A radio station on a hill.","first_air_date":"2020-04-29",""" +
                """"vote_average":8.3,"vote_count":500,"genres":[{"name":"Crime"}],"created_by":[{"name":"Sally Moor"}]}"""
    }
}
