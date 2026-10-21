package com.biskyt.mytvplayer.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.biskyt.mytvplayer.data.db.AppDatabase
import com.biskyt.mytvplayer.data.db.CAT_LIVE
import com.biskyt.mytvplayer.data.db.CAT_MOVIE
import com.biskyt.mytvplayer.data.db.CAT_SERIES
import com.biskyt.mytvplayer.testutil.FakeProvider
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** M3U playlists' movies and series, and how their categories are filed. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class M3uPlaylistTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val provider = FakeProvider()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var db: AppDatabase
    private lateinit var settings: SettingsRepository
    private lateinit var epg: EpgRepository
    private lateinit var playlists: PlaylistRepository
    private lateinit var channels: ChannelRepository
    private lateinit var vod: VodRepository

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        val http = OkHttpClient()
        settings = SettingsRepository(context)
        epg = EpgRepository(db, http, context.cacheDir.resolve("test-epg"), scope)
        val categoryStore = CategoryStore(db) { Locale.UK }
        playlists = PlaylistRepository(db, http, settings, epg, context.cacheDir.resolve("test-playlists"), categoryStore)
        channels = ChannelRepository(db)
        vod = VodRepository(db, playlists, categoryStore)
    }

    @After
    fun tearDown() {
        scope.cancel()
        db.close()
        provider.close()
    }

    private val vodPlaylist = """
        #EXTM3U
        #EXTINF:-1 tvg-logo="http://img/bbb.jpg" group-title="Animation",Big Buck Bunny (2008)
        http://vod.example/movies/bbb.mp4
        #EXTINF:-1 group-title="FR | Films",Amélie (2001)
        http://vod.example/movies/amelie.mkv
        #EXTINF:-1 tvg-logo="http://img/caminandes.jpg" group-title="Animated Series",Caminandes S01E02 - Gran Dillama
        http://vod.example/series/cam2.mp4
        #EXTINF:-1 group-title="Animated Series",Caminandes S01E01 - Llama Drama
        http://vod.example/series/cam1.mp4
    """.trimIndent()

    @Test
    fun `an M3U movie playlist with episodes in it gives movies and series, in the categories wanted`() = runBlocking {
        provider.vodM3u = vodPlaylist
        val id = playlists.addM3u("Free", provider.url + "free.m3u", moviesUrl = provider.url + "vod.m3u")
        val p = playlists.get(id)!!
        assertTrue(p.hasVod)
        vod.sync(p)

        // A film named in French isn't wanted on a UK TV; untagged categories are.
        val movies = db.vod().searchMovies(id, "%", 10)
        assertEquals(listOf("Big Buck Bunny"), movies.map { it.title })
        val bunny = vod.movie(id, movies[0].id)!!
        assertEquals(2008, bunny.year)
        assertEquals("http://vod.example/movies/bbb.mp4", vod.movieUrl(p, bunny))
        assertTrue(db.categories().all(id, CAT_MOVIE).any { it.categoryId == "FR | Films" && !it.included })

        // Episodes make one series, in order, with their titles; each plays from its own address.
        val show = db.vod().searchSeries(id, "%", 10).single()
        assertEquals("Caminandes", show.title)
        val details = vod.seriesDetails(p, show.id)
        assertEquals(listOf("Season 1"), details.seasons.map { it.name })
        assertEquals(listOf("Llama Drama", "Gran Dillama"), details.episodes.map { it.title })
        assertEquals("http://vod.example/series/cam1.mp4", vod.episodeUrl(p, details.episodes[0].id, ""))
        assertEquals(null, vod.movieDetails(p, bunny.streamId).plot)
    }

    @Test
    fun `an unchanged M3U movie playlist isn't loaded again, and removing it removes its titles`() = runBlocking {
        provider.vodM3u = vodPlaylist
        val id = playlists.addM3u("Free", provider.url + "free.m3u", moviesUrl = provider.url + "vod.m3u")
        vod.sync(playlists.get(id)!!)
        val bunny = db.vod().searchMovies(id, "%", 10).single()
        db.vod().deleteStaleMovies(id, Long.MAX_VALUE) // so a reload would show

        vod.sync(playlists.get(id)!!)
        assertTrue(db.vod().searchMovies(id, "%", 10).isEmpty())

        // Changed: loaded again, with the same ids, so favourites and progress still match.
        provider.vodM3u = vodPlaylist + "\n"
        vod.sync(playlists.get(id)!!)
        assertEquals(bunny.id, db.vod().searchMovies(id, "%", 10).single().id)

        playlists.update(playlists.get(id)!!.copy(moviesUrl = null))
        vod.sync(playlists.get(id)!!)
        assertEquals(0, db.vod().titleCount(id))
        assertTrue(db.categories().all(id, CAT_MOVIE).isEmpty())
        assertFalse(playlists.get(id)!!.hasVod)
    }

    @Test
    fun `everything in a series playlist is a series, even unnumbered`() = runBlocking {
        provider.seriesM3u = """
            #EXTM3U
            #EXTINF:-1 group-title="Shorts",Llama Drama
            http://vod.example/series/cam1.mp4
            #EXTINF:-1 group-title="Shorts",Gran Dillama
            http://vod.example/series/cam2.mp4
        """.trimIndent()
        val id = playlists.addM3u("Free", provider.url + "free.m3u", seriesUrl = provider.url + "series.m3u")
        val p = playlists.get(id)!!
        vod.sync(p)
        assertEquals(0, db.vod().searchMovies(id, "%", 10).size)
        assertEquals(listOf("Gran Dillama", "Llama Drama"), db.vod().searchSeries(id, "%", 10).map { it.title }.sorted())
    }

    @Test
    fun `M3U categories split by their channels' country or language, unless their name has one`() = runBlocking {
        provider.m3u = """
            #EXTM3U
            #EXTINF:-1 tvg-id="One.uk" tvg-country="GB" group-title="Live: News",UK News
            http://stream.example/uk.m3u8
            #EXTINF:-1 tvg-id="Two.us" tvg-country="US" group-title="Live: News",US News
            http://stream.example/us.m3u8
            #EXTINF:-1 tvg-id="Three.se" tvg-country="SE" tvg-language="Swedish" group-title="Live: News",SE News
            http://stream.example/se.m3u8
            #EXTINF:-1 tvg-language="English" group-title="Live: Music",Music Everywhere
            http://stream.example/music.m3u8
            #EXTINF:-1 tvg-country="US" group-title="UK | Sports",Provider Sports
            http://stream.example/sports.m3u8
            #EXTINF:-1 group-title="Live: Weather",Plain Weather
            http://stream.example/weather.m3u8
        """.trimIndent()
        provider.vodM3u = """
            #EXTM3U
            #EXTINF:6000 tvg-type="movie" tvg-language="French" group-title="Movies: Drama",Les Misérables (1934)
            https://archive.org/download/lesmis.mp4
            #EXTINF:6000 tvg-type="movie" tvg-language="English" group-title="Movies: Drama",Detour (1945)
            https://archive.org/download/detour.mp4
        """.trimIndent()
        val id = playlists.addM3u("Free", provider.url + "free.m3u", moviesUrl = provider.url + "vod.m3u")
        playlists.syncChannels(id)
        vod.sync(playlists.get(id)!!)

        val live = db.categories().all(id, com.biskyt.mytvplayer.data.db.CAT_LIVE)
        // One "Live: News" per country, named as the playlist names it; the TV's own country (a UK TV here) is chosen.
        assertEquals(listOf("SE", "UK", "US"), live.filter { it.name == "Live: News" }.mapNotNull { it.lang }.sorted())
        val loaded = db.channels().idsAll(id).mapNotNull { db.channels().get(id, it)?.name }
        assertEquals(listOf("UK News"), loaded.filter { it.endsWith("News") })
        // By language when there's no country; a name that says its own group, or nothing to go by, stays as it is.
        assertEquals("UK", live.single { it.name == "Live: Music" }.lang)
        assertEquals("UK | Sports", live.single { it.name == "UK | Sports" }.categoryId)
        assertEquals("Live: Weather", live.single { it.name == "Live: Weather" }.categoryId)
        // Films too: the French one isn't chosen on a UK TV.
        val films = db.categories().all(id, CAT_MOVIE)
        assertEquals(listOf("FR" to false, "UK" to true), films.map { it.lang to it.included }.sortedBy { it.first })
        assertEquals(listOf("Detour"), db.vod().searchMovies(id, "%", 10).map { it.title })
    }

    @Test
    fun `a playlist whose categories name no country or language has them all chosen to start with`() = runBlocking {
        // As the free TV playlist is now: genres only, nothing to choose by country.
        provider.m3u = """
            #EXTM3U
            #EXTINF:-1 group-title="Live: News",World News
            http://stream.example/news.m3u8
            #EXTINF:-1 group-title="FAST: Plex",Plex Movies
            http://stream.example/plex.m3u8
        """.trimIndent()
        provider.vodM3u = """
            #EXTM3U
            #EXTINF:6000 tvg-type="movie" group-title="Movies: Drama",Detour (1945)
            https://archive.org/download/detour.mp4
        """.trimIndent()
        val id = playlists.addM3u("Free", provider.url + "free.m3u", moviesUrl = provider.url + "vod.m3u")
        playlists.syncChannels(id)
        vod.sync(playlists.get(id)!!)

        assertTrue(db.categories().all(id, com.biskyt.mytvplayer.data.db.CAT_LIVE).all { it.included })
        assertEquals(2, db.channels().idsAll(id).size)
        assertEquals(listOf("Detour"), db.vod().searchMovies(id, "%", 10).map { it.title })
    }

    @Test
    fun `a playlist that says what each title is (tvg-type) is taken at its word`() = runBlocking {
        provider.vodM3u = """
            #EXTM3U
            #EXTINF:4020 tvg-type="movie" year="1945" group-title="Movies: Noir",Detour
            https://archive.org/download/Detour/Detour.mp4
            #EXTINF:600 tvg-type="movie" group-title="Movies: Serials",Flash Gordon S01E02
            https://archive.org/download/flash2.mp4
            #EXTINF:1530 tvg-type="series" series-name="The Beverly Hillbillies" season="1" episode="2" group-title="Series: The Beverly Hillbillies",Getting Settled
            https://archive.org/download/bh2.mp4
            #EXTINF:1530 tvg-type="series" series-name="The Beverly Hillbillies" season="1" episode="1" group-title="Series: The Beverly Hillbillies",The Beverly Hillbillies S01E01 - The Clampetts Strike Oil
            https://archive.org/download/bh1.mp4
        """.trimIndent()
        val id = playlists.addM3u("Free", provider.url + "free.m3u", moviesUrl = provider.url + "vod.m3u")
        db.categories().setIncluded(id, CAT_MOVIE, listOf("Movies: Noir", "Movies: Serials"), true)
        db.categories().setIncluded(id, CAT_SERIES, listOf("Series: The Beverly Hillbillies"), true)
        val p = playlists.get(id)!!
        vod.sync(p)

        // A film named like an episode stays a film; the year comes from the playlist.
        val movies = db.vod().searchMovies(id, "%", 10).sortedBy { it.title }
        assertEquals(listOf("Detour", "Flash Gordon S01E02"), movies.map { it.title })
        assertEquals(1945, vod.movie(id, movies[0].id)!!.year)

        // Episodes by series-name, season and episode, whatever their names say.
        val show = db.vod().searchSeries(id, "%", 10).single()
        assertEquals("The Beverly Hillbillies", show.title)
        val details = vod.seriesDetails(p, show.id)
        assertEquals(listOf("The Clampetts Strike Oil", "Episode 2"), details.episodes.map { it.title })
    }

    @Test
    fun `M3U titles keep their ids when categories change, and moving a playlist to Series reloads it`() = runBlocking {
        provider.vodM3u = """
            #EXTM3U
            #EXTINF:-1 group-title="FR | Films",Big Buck Bunny (2008)
            http://vod.example/fr/bbb.mp4
            #EXTINF:-1 group-title="Animation",Big Buck Bunny (2008)
            http://vod.example/bbb.mp4
        """.trimIndent()
        val id = playlists.addM3u("Free", provider.url + "free.m3u", moviesUrl = provider.url + "vod.m3u")
        vod.sync(playlists.get(id)!!)
        val before = db.vod().searchMovies(id, "%", 10).single().id
        db.categories().setIncluded(id, CAT_MOVIE, listOf("FR | Films"), true)
        vod.sync(playlists.get(id)!!)
        assertTrue(db.vod().searchMovies(id, "%", 10).map { it.id }.contains(before))
        assertEquals("http://vod.example/bbb.mp4", vod.movieUrl(playlists.get(id)!!, vod.movie(id, before)!!))

        // The same address moved from Movies to Series: everything in it becomes a series.
        playlists.update(playlists.get(id)!!.copy(moviesUrl = null, seriesUrl = provider.url + "vod.m3u"))
        vod.sync(playlists.get(id)!!)
        db.categories().setIncluded(id, CAT_SERIES, listOf("FR | Films"), true)
        vod.sync(playlists.get(id)!!)
        assertEquals(0, db.vod().searchMovies(id, "%", 10).size)
        assertEquals(2, db.vod().searchSeries(id, "%", 10).size)
    }

    @Test
    fun `category ids carry their group, names stay the playlist's own`() {
        fun entry(group: String?, country: String? = null) =
            com.biskyt.mytvplayer.core.m3u.M3uEntry("x", "http://x", null, null, group, null, null, null, country = country)
        val news = M3uCategories.idOf(entry("Live: News", country = "SE"))
        assertEquals("SE", M3uCategories.groupOf(news))
        assertEquals("Live: News", M3uCategories.category(news).name)
        assertEquals("UK | News", M3uCategories.idOf(entry("UK | News", country = "SE"))) // the name says already
        assertEquals("Other", M3uCategories.idOf(entry(null)))
        assertEquals(null, M3uCategories.groupOf("UK | News"))
    }
}
