package com.biskyt.mytvplayer.testutil

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.zip.GZIPOutputStream
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer

/** A fake Xtream Codes provider (with an XMLTV guide) for tests. Channel lists can be changed between requests. */
class FakeProvider(private val now: Long = System.currentTimeMillis()) : Dispatcher() {
    val server = MockWebServer().apply { dispatcher = this@FakeProvider }
    val url: String get() = server.url("/").toString()

    var categories = listOf("1" to "UK | ENTERTAINMENT", "2" to "UK | SPORT", "3" to "FR: CINEMA", "4" to "|AR| NEWS")

    /** stream id, number, name, category id, provider epg id */
    var streams = listOf(
        Stream(101, 1, "UK: CHANNEL ONE HD", "1", "one.uk"),
        Stream(102, 2, "UK: ARENA SPRTS 1 FHD", "2", null),
        Stream(103, 3, "UK: KITE KIDZ", "1", null),
        Stream(201, 201, "FR: CINE PLUS", "3", null),
        Stream(301, 301, "AR: NEWS 24", "4", null),
    )

    data class Stream(val id: Long, val num: Int, val name: String, val cat: String, val epgId: String?)

    var movieCategories = listOf("10" to "UK | MOVIES", "11" to "FR | FILMS", "12" to "ACTION")

    /** Movies: id to (raw name, category id, added epoch seconds). */
    var movies = listOf(
        Triple(501L, "EN - Dune (2021)", "10"),
        Triple(502L, "|UK| Paddington 2 (2017)", "10"),
        Triple(601L, "FR - Amélie (2001)", "11"),
        Triple(701L, "Heat (1995)", "12"),
    )

    var seriesCategories = listOf("20" to "UK | DRAMA", "21" to "FR | SERIES")

    var series = listOf(Triple(77L, "UK - Line of Duty", "20"), Triple(78L, "FR - Lupin", "21"))

    val requests = mutableListOf<String>()

    /** An iptv-org style playlist, grouped by country. */
    var m3u = """
        #EXTM3U
        #EXTINF:-1 tvg-id="ChannelOne.uk@SD" tvg-logo="http://logo/one.png" group-title="United Kingdom",Channel One
        http://stream.example/one.m3u8
        #EXTINF:-1 tvg-id="KiteKids.uk@HD" group-title="United Kingdom" http-user-agent="Agent/2",Kite Kids (720p)
        http://stream.example/kite.m3u8
        #EXTINF:-1 tvg-id="CinePlus.fr@SD" group-title="France",Ciné+
        http://stream.example/cine.m3u8
        #EXTINF:-1 group-title="International",World News
        http://stream.example/world.m3u8
    """.trimIndent()

    override fun dispatch(request: RecordedRequest): MockResponse {
        val url = request.requestUrl!!
        requests += url.encodedPath + "?" + (url.queryParameter("action") ?: "")
        if (url.encodedPath == "/free.m3u") return MockResponse().setBody(m3u)
        if (url.encodedPath == "/not-a-playlist") return MockResponse().setBody("<html>hello</html>")
        if (url.queryParameter("password") != "secret") return MockResponse().setBody("""{"user_info":{"auth":0}}""")
        return when {
            url.encodedPath.endsWith("xmltv.php") -> MockResponse().setBody(Buffer().write(gzip(xmltv())))
            url.queryParameter("action") == null -> MockResponse().setBody(
                """{"user_info":{"auth":1,"status":"Active","exp_date":"1900000000","max_connections":"1"}}""",
            )
            url.queryParameter("action") == "get_live_categories" ->
                MockResponse().setBody(
                    categories.joinToString(",", "[", "]") { (id, name) ->
                        """{"category_id":"$id","category_name":"$name"}"""
                    }
                )
            url.queryParameter("action") == "get_live_streams" -> {
                val cat = url.queryParameter("category_id")
                val list = streams.filter { cat == null || it.cat == cat }
                MockResponse().setBody(
                    list.joinToString(",", "[", "]") {
                        """{"num":${it.num},"name":"${it.name}","stream_id":${it.id},"category_id":"${it.cat}",""" +
                            """"stream_icon":"","epg_channel_id":${it.epgId?.let { e -> "\"$e\"" } ?: "null"},"tv_archive":0}"""
                    },
                )
            }
            else -> vodResponse(url) ?: MockResponse().setResponseCode(404)
        }
    }

    /** Movie and series endpoints. */
    private fun vodResponse(url: okhttp3.HttpUrl): MockResponse? = when (url.queryParameter("action")) {
        "get_vod_categories" -> categoryList(movieCategories)
        "get_series_categories" -> categoryList(seriesCategories)
        "get_vod_streams" -> {
            val cat = url.queryParameter("category_id")
            MockResponse().setBody(
                movies.filter { cat == null || it.third == cat }.withIndex().joinToString(",", "[", "]") { (i, m) ->
                    """{"num":${i + 1},"name":"${m.second}","stream_id":${m.first},"category_id":"${m.third}",""" +
                        """"stream_icon":"http://img/${m.first}.jpg","rating":"7.5","added":"${1_700_000_000 + m.first}","container_extension":"mkv"}"""
                }
            )
        }
        "get_series" -> {
            val cat = url.queryParameter("category_id")
            MockResponse().setBody(
                series.filter { cat == null || it.third == cat }.withIndex().joinToString(",", "[", "]") { (i, s) ->
                    """{"num":${i + 1},"name":"${s.second}","series_id":${s.first},"category_id":"${s.third}",""" +
                        """"cover":"http://img/s${s.first}.jpg","releaseDate":"2012-06-26","last_modified":"${1_690_000_000 + s.first}"}"""
                }
            )
        }
        "get_vod_info" -> MockResponse().setBody(
            """{"info":{"plot":"A story.","cast":"Someone","genre":"Drama","releasedate":"2021-09-15","duration_secs":6000,""" +
                """"backdrop_path":["http://img/back.jpg"]},"movie_data":{"stream_id":${url.queryParameter(
                    "vod_id"
                )},"container_extension":"mkv"}}"""
        )
        "get_series_info" -> MockResponse().setBody(
            """{"seasons":[{"season_number":1,"name":"Series 1"},{"season_number":2,"name":"Series 2"}],
               "info":{"plot":"Anti-corruption.","genre":"Crime","episode_run_time":"60"},
               "episodes":{"1":[{"id":"9001","episode_num":1,"title":"Episode 1","container_extension":"mkv","season":1,"info":{"plot":"First.","duration_secs":3600}},
                                {"id":"9002","episode_num":2,"title":"Episode 2","container_extension":"mkv","season":1,"info":{"duration_secs":3500}}],
                           "2":[{"id":"9003","episode_num":1,"title":"Episode 1","container_extension":"mp4","season":2}]}}"""
        )
        else -> null
    }

    private fun categoryList(list: List<Pair<String, String>>) =
        MockResponse().setBody(list.joinToString(",", "[", "]") { (id, name) -> """{"category_id":"$id","category_name":"$name"}""" })

    private val fmt = SimpleDateFormat("yyyyMMddHHmmss Z", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
    private fun t(offsetMin: Int) = fmt.format(Date(now + offsetMin * 60_000L))

    /** Guide channels (id to name); each has a programme on now and one next. */
    var guideChannels = listOf(
        "one.uk" to "Channel One",
        "arena1.uk" to "Arena Sports 1 HD",
        "kite.uk" to "Kite Kids",
        "cine.fr" to "Ciné+"
    )

    fun xmltv(): String = buildString {
        append("<?xml version=\"1.0\"?><tv>")
        for ((id, name) in guideChannels) {
            append("<channel id=\"$id\"><display-name>$name</display-name></channel>")
        }
        for ((id, _) in guideChannels) {
            append(
                "<programme start=\"${t(
                    -30
                )}\" stop=\"${t(30)}\" channel=\"$id\"><title>Now on $id</title><desc>About $id</desc></programme>"
            )
            append("<programme start=\"${t(30)}\" stop=\"${t(90)}\" channel=\"$id\"><title>Next on $id</title></programme>")
        }
        append("</tv>")
    }

    private fun gzip(text: String): ByteArray = java.io.ByteArrayOutputStream().also { out ->
        GZIPOutputStream(out).use { it.write(text.toByteArray()) }
    }.toByteArray()

    fun close() = server.shutdown()
}
