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
 * have suggestions. The Long Harbour has a trailer and a teaser, and Coastline a status, counts, a next episode and words for two
 * episodes of series 2. Coastline's first episode has a score, people and its own cast and crew ([EPISODE_DIRECTOR]). [use] points [AppContainer.tmdb] at it, with a test key (never a real one). As MyTVPlayer's FakeTmdb,
 * with MyPlexPlayer's titles.
 */
class FakeTmdb : Dispatcher() {
    private val server = MockWebServer().also {
        it.dispatcher = this
        it.start()
    }

    /** The paths asked for, in order. */
    val requests: MutableList<String> = CopyOnWriteArrayList()

    /** While set, a season's episodes fail (TMDB out of reach for a moment). */
    @Volatile var seasonsFail = false

    /** Points [c]'s TMDB at this server; with [key], TMDB is on, as with a key in Settings. */
    fun use(c: AppContainer, key: String? = TEST_KEY) = runBlocking {
        c.tmdb.base = server.url("/3/").toString()
        if (key != null) c.settings.update { it.copy(tmdbKey = key) }
    }

    fun close() = server.shutdown()

    override fun dispatch(request: RecordedRequest): MockResponse {
        val path = request.requestUrl!!.encodedPath.removePrefix("/3/")
        requests += path
        if (seasonsFail && "/season/" in path) return MockResponse().setResponseCode(503)
        val body = when (path) {
            // Only the test key is accepted.
            "authentication" -> if (request.requestUrl!!.queryParameter("api_key") == TEST_KEY) "{}" else return refused()
            "movie/$FILM/recommendations" -> FILM_LIKE
            "tv/$SHOW/recommendations" -> SHOW_LIKE
            "movie/$PAPER_BOATS" -> PAPER_BOATS_PAGE
            "tv/$SIGNAL_HILL" -> SIGNAL_HILL_PAGE
            "tv/$SIGNAL_HILL/season/2" -> SIGNAL_HILL_2
            "tv/$SIGNAL_HILL/season/1" -> """{"episodes":[{"season_number":1,"episode_number":1,"name":"Episode One"}]}"""
            "tv/$SHOW" -> COASTLINE_PAGE
            "tv/$SHOW/season/2" -> COASTLINE_2
            "tv/$SHOW/season/1" -> COASTLINE_1
            "tv/$SHOW/season/1/episode/1/credits" -> PILOT_CREDITS
            "movie/$FILM" -> LONG_HARBOUR_PAGE
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

        /** The Long Harbour's trailer on TMDB, for a server that has none of its own. */
        const val FILM_TRAILER = "lhtrailer01"

        /** The Long Harbour's teaser, for the UK: second of TMDB's in Other trailers. */
        const val TEASER = "lhteaser001"

        /** Paper Boats' teaser, for the UK: second in its TMDB page's Other trailers. */
        const val PAPER_BOATS_TEASER = "pbteaser001"

        /** When Coastline's next episode is shown (series 4, episode 2), as TMDB says. */
        const val NEXT_EPISODE = "2099-11-22"

        /** TMDB's description of Coastline's "Low Water" (series 2, episode 3), which the server gives none. */
        const val LOW_WATER = "Rose waits for the tide to turn."

        /** TMDB's description of "The Lighthouse Keeper" (episode 4), which the server has its own of. */
        const val KEEPER = "TMDB's own words for the keeper."

        /** Who directed Coastline's first episode, and who wrote it, on TMDB. */
        const val EPISODE_DIRECTOR = "David Caffrey"
        const val EPISODE_WRITER = "Jed Mercurio"

        /** TMDB's description of Coastline's first episode, which the server gives none. */
        const val PILOT_WORDS = "Rose takes the case nobody wants."

        /** A guest star in Coastline's first episode, on TMDB. */
        const val GUEST_STAR = "Neil Morrissey"

        /** Coastline's series 1 on TMDB: its first episode's description, score, who made it and its guest star. */
        private const val COASTLINE_1 =
            """{"episodes":[{"season_number":1,"episode_number":1,"overview":"$PILOT_WORDS","vote_average":8.1,"vote_count":50,""" +
                """"crew":[{"id":501,"name":"$EPISODE_DIRECTOR","job":"Director"},{"id":502,"name":"$EPISODE_WRITER","job":"Writer"}],""" +
                """"guest_stars":[{"id":503,"name":"$GUEST_STAR","character":"Morton"}]}]}"""

        /** Coastline's first episode's own cast and crew on TMDB. */
        private const val PILOT_CREDITS =
            """{"cast":[{"id":504,"name":"Vicky McClure","character":"Kate Fleming"}],""" +
                """"guest_stars":[{"id":503,"name":"$GUEST_STAR","character":"Morton"}],""" +
                """"crew":[{"id":501,"name":"$EPISODE_DIRECTOR","job":"Director"},{"id":502,"name":"$EPISODE_WRITER","job":"Writer"}]}"""

        /** More like The Long Harbour: Sintel is on the server (FakePlex's film 12), Paper Boats is not. */
        private const val FILM_LIKE =
            """{"page":1,"results":[{"id":45745,"title":"Sintel","release_date":"2010-09-27"},""" +
                """{"id":$PAPER_BOATS,"title":"Paper Boats","release_date":"2018-03-02","vote_average":7.6,"vote_count":1900}]}"""

        /** More like Coastline: Signal Hill is not on the server. */
        private const val SHOW_LIKE =
            """{"page":1,"results":[{"id":$SIGNAL_HILL,"name":"Signal Hill","first_air_date":"2020-04-29",""" +
                """"vote_average":8.3,"vote_count":500}]}"""

        private const val PAPER_BOATS_PAGE =
            """{"id":$PAPER_BOATS,"title":"Paper Boats","original_title":"Bateaux de Papier",""" +
                """"overview":"Two sisters sail home.","release_date":"2018-03-02",""" +
                """"vote_average":7.6,"vote_count":1900,"genres":[{"name":"Drama"}],""" +
                """"credits":{"crew":[{"name":"Ana Ruiz","job":"Director"}]},""" +
                """"videos":{"results":[{"key":"$TRAILER","site":"YouTube","type":"Trailer","official":true},""" +
                """{"key":"$PAPER_BOATS_TEASER","site":"YouTube","type":"Teaser","name":"Teaser","iso_3166_1":"GB"}]}}"""

        private const val SIGNAL_HILL_PAGE =
            """{"id":$SIGNAL_HILL,"name":"Signal Hill","overview":"A radio station on a hill.","first_air_date":"2020-04-29",""" +
                """"vote_average":8.3,"vote_count":500,"genres":[{"name":"Crime"}],"created_by":[{"name":"Sally Moor"}],""" +
                """"status":"Ended","number_of_seasons":2,"number_of_episodes":12,""" +
                """"seasons":[{"season_number":0,"name":"Specials","episode_count":1},{"season_number":1,"episode_count":6},""" +
                """{"season_number":2,"name":"The Second Run","episode_count":6}]}"""

        private const val SIGNAL_HILL_2 =
            """{"episodes":[{"season_number":2,"episode_number":1,"name":"Static","air_date":"2023-01-01"},""" +
                """{"season_number":2,"episode_number":2,"name":"Dead Air"}]}"""

        /** Coastline on TMDB: cancelled (TMDB's finer word), 6 seasons, and its next episode. No trailer. */
        private const val COASTLINE_PAGE =
            """{"id":$SHOW,"name":"Coastline","status":"In Production","number_of_seasons":6,"number_of_episodes":40,""" +
                """"next_episode_to_air":{"season_number":4,"episode_number":2,"air_date":"$NEXT_EPISODE"}}"""

        /** Coastline's series 2 on TMDB: "Low Water" and "The Lighthouse Keeper", each with a description and a picture. */
        private const val COASTLINE_2 =
            """{"episodes":[{"season_number":2,"episode_number":3,"overview":"$LOW_WATER","still_path":"/lowwater.jpg"},""" +
                """{"season_number":2,"episode_number":4,"overview":"$KEEPER","still_path":"/keeper.jpg"}]}"""

        /** The Long Harbour on TMDB: only its trailer and its UK teaser. */
        private const val LONG_HARBOUR_PAGE =
            """{"id":$FILM,"title":"The Long Harbour",""" +
                """"videos":{"results":[{"key":"$FILM_TRAILER","site":"YouTube","type":"Trailer","official":true},""" +
                """{"key":"$TEASER","site":"YouTube","type":"Teaser","name":"Teaser","iso_3166_1":"GB"}]}}"""
    }
}
