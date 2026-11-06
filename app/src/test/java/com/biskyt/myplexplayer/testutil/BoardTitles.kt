package com.biskyt.myplexplayer.testutil

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import java.io.ByteArrayOutputStream
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * The film and series that the design boards draw (2026-11-05), as a Plex server lists them: "The Long Harbour" (board
 * "PlexFilm") with every score, its picture and sound, its cast, and all that About and Technical info list; and
 * "Coastline" (board "PlexSeries"), its series 2 with episode pictures and a season poster.
 */
object BoardTitles {
    const val FILM = "51"
    const val SHOW = "61"

    /** The film, its title [title] (the board's, or a long one). */
    fun film(title: String = "The Long Harbour"): JsonObject = JsonObject(
        FakePlex.film(
            FILM,
            title,
            2021,
            duration = 112 * MIN,
            container = "mkv",
            summary = "A harbour master on a remote island finds a boat with no crew and starts to ask who sent it. " +
                "As the winter storms close the port, the islanders take sides.",
            thumb = "/library/metadata/$FILM/thumb/1",
            art = "/library/metadata/$FILM/art/1",
            addedAt = 1_767_528_000 // 4 January 2026
        ) + mapOf(
            "contentRating" to JsonPrimitive("gb/12A"),
            "studio" to JsonPrimitive("North Light Films"),
            "originalTitle" to JsonPrimitive("El puerto largo"),
            "originallyAvailableAt" to JsonPrimitive("2021-03-12"),
            "Genre" to tags("Drama", "Mystery"),
            "Director" to people("Ana Ruiz" to ANA),
            "Writer" to people("Ana Ruiz" to ANA, "Paul Okafor" to ANA + 1),
            "Producer" to tags("Mia Ortega", "Sam Reid"),
            "Country" to tags("Spain", "United Kingdom"),
            "Collection" to tags("Island stories"),
            "Role" to buildJsonArray {
                listOf(
                    "Tom Hale" to "Elias",
                    "Ines Moreau" to "Marta",
                    "Kofi Mensah" to "Sam",
                    "Ruth Lane" to "The pilot",
                    "Joe Park" to "Davy",
                    "Ana Ruiz" to "Director"
                ).forEachIndexed { i, (name, role) ->
                    add(
                        buildJsonObject {
                            put("tag", name)
                            put("role", role)
                            put("thumb", "/people/$i.jpg")
                            // Each one's ids, on the server and on Discover; the last as one with no id.
                            if (i < 5) {
                                put("id", CAST + i)
                                put("tagKey", tagKey(CAST + i))
                            }
                        }
                    )
                }
            },
            "Guid" to guids("imdb://tt0000051", "tmdb://51", "tvdb://51"),
            "Rating" to buildJsonArray {
                add(rating("imdb://image.rating", "audience", 7.4))
                add(rating("themoviedb://image.rating", "audience", 7.2))
                add(rating("rottentomatoes://image.rating.ripe", "critic", 8.8))
                add(rating("rottentomatoes://image.rating.upright", "audience", 9.1))
            },
            "Media" to filmMedia()
        )
    )

    /** The file: 4K Dolby Vision 8.1 over HDR10, TrueHD Atmos and two AC-3 soundtracks, and two English subtitles. */
    private fun filmMedia() = buildJsonArray {
        add(
            buildJsonObject {
                put("container", "mkv")
                put("videoResolution", "4k")
                put("bitrate", 22_100)
                putJsonArray("Part") {
                    add(
                        buildJsonObject {
                            put("id", 511)
                            put("key", "/library/parts/511/1/file.mkv")
                            put("container", "mkv")
                            put("size", 18_600_000_000)
                            putJsonArray("Stream") {
                                add(
                                    buildJsonObject {
                                        put("streamType", 1)
                                        put("codec", "hevc")
                                        put("profile", "main 10")
                                        put("width", 3840)
                                        put("height", 2160)
                                        put("frameRate", 23.976)
                                        put("colorTrc", "smpte2084")
                                        put("DOVIPresent", true)
                                        put("DOVIProfile", 8)
                                        put("DOVIBLCompatID", 1)
                                    }
                                )
                                add(audio("English", "truehd", 8, 4_200, "English (TrueHD Atmos 7.1)"))
                                add(audio("English", "ac3", 6, 640))
                                add(audio("French", "ac3", 6, 640))
                                add(subtitles("English", "srt", forced = true))
                                add(subtitles("English", "pgs", forced = false))
                            }
                        }
                    )
                }
            }
        )
    }

    /** The show, its title [title] (the board's, or a long one). */
    fun show(title: String = "Coastline"): JsonObject = JsonObject(
        FakePlex.show(
            SHOW,
            title,
            2021,
            summary = "Two detectives on the north coast work the cases nobody else wants, while a cold case from their past " +
                "comes back.",
            thumb = "/library/metadata/$SHOW/thumb/1",
            art = "/library/metadata/$SHOW/art/1"
        ) + mapOf(
            "contentRating" to JsonPrimitive("gb/15"),
            "Genre" to tags("Crime"),
            "Role" to buildJsonArray {
                add(
                    buildJsonObject {
                        put("tag", "Ines Moreau")
                        put("role", "Rose")
                        put("id", CAST + 1)
                        put("tagKey", tagKey(CAST + 1))
                    }
                )
            },
            "Guid" to guids("imdb://tt0000052", "tmdb://52", "tvdb://52"),
            "Rating" to buildJsonArray { add(rating("imdb://image.rating", "audience", 8.1)) }
        )
    )

    /**
     * Its episodes: one each in series 1 and 3, and series 2's episodes 2 to 5, each with a picture, a date and its picture and sound.
     * [long] gives episode 4 a title too long for its row.
     */
    fun episodes(title: String = "Coastline", long: Boolean = false): List<JsonObject> {
        // Episodes 2 to 5, as the board lists them.
        val names = listOf(
            "The Tide Table",
            "Low Water",
            if (long) "The Lighthouse Keeper Who Never Left the Island, and the Boat That Came Back" else "The Lighthouse Keeper",
            "Undertow"
        )
        val minutes = listOf(58, 57, 54, 55)
        val shown = listOf("2023-02-28", "2023-03-07", "2023-03-14", "2023-03-21")
        val series2 = names.indices.map { i ->
            val n = i + 2
            episode(title, "62$n", 2, n, names[i], minutes[i]) + mapOf(
                "summary" to JsonPrimitive(
                    if (n ==
                        4
                    ) {
                        "A body on the rocks below the lighthouse leads Rose to a keeper who has not left the island in ten years."
                    } else {
                        ""
                    }
                ),
                "originallyAvailableAt" to JsonPrimitive(shown[i]),
                "year" to JsonPrimitive(2023),
                "thumb" to JsonPrimitive("/library/metadata/62$n/thumb/1"),
                "parentThumb" to JsonPrimitive("/library/metadata/602/thumb/1"),
                "Media" to episodeMedia(620 + n)
            )
        }
        return listOf(episode(title, "611", 1, 1, "Pilot", 58)) + series2.map(::JsonObject) +
            episode(title, "631", 3, 1, "Return", 58)
    }

    @Suppress("LongParameterList") // an episode's fields, as FakePlex.episode takes them
    private fun episode(show: String, key: String, season: Int, index: Int, title: String, minutes: Int) =
        FakePlex.episode(SHOW, show, key, season, index, title, duration = minutes * MIN)

    /** An episode's file: 1080p HDR10, Dolby Digital Plus 5.1. */
    private fun episodeMedia(id: Int) = buildJsonArray {
        add(
            buildJsonObject {
                put("container", "mkv")
                put("videoResolution", "1080")
                putJsonArray("Part") {
                    add(
                        buildJsonObject {
                            put("id", id)
                            put("key", "/library/parts/$id/1/file.mkv")
                            put("container", "mkv")
                            putJsonArray("Stream") {
                                add(
                                    buildJsonObject {
                                        put("streamType", 1)
                                        put("codec", "hevc")
                                        put("colorTrc", "smpte2084")
                                    }
                                )
                                add(audio("English", "eac3", 6, 640))
                            }
                        }
                    )
                }
            }
        )
    }

    /**
     * Its play state, as the boards show it: series 2's episodes 2 and 3 watched, 5 part-watched (12 min left) and 4 last
     * (21 min left): Continue S2 E4.
     */
    fun watch(plex: FakePlex) {
        val now = System.currentTimeMillis() / 1000
        plex.watched += listOf("622", "623")
        listOf("622", "623").forEachIndexed { i, key -> plex.lastViewedAt[key] = now - 4_000 + i }
        plex.viewOffset["625"] = 43 * MIN
        plex.lastViewedAt["625"] = now - 2_000
        plex.viewOffset["624"] = 33 * MIN
        plex.lastViewedAt["624"] = now - 1_000
        // The film: 38 min left of 1 h 52 min.
        plex.viewOffset[FILM] = 74 * MIN
        plex.lastViewedAt[FILM] = now - 500
    }

    /** A plain picture for [path], in a gradient of two greys, as the boards draw their placeholders. */
    fun art(path: String): ByteArray {
        val (w, h) = when {
            "/art/" in path -> 640 to 360
            path.startsWith("/people/") -> 184 to 184
            "/62" in path -> 256 to 144
            else -> 200 to 300
        }
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val paint = Paint().apply {
            shader = LinearGradient(0f, 0f, w.toFloat(), h.toFloat(), 0xFF546A80.toInt(), 0xFF26323E.toInt(), Shader.TileMode.CLAMP)
        }
        Canvas(bitmap).drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)
        return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    }

    private fun tags(vararg names: String) = JsonArray(names.map { buildJsonObject { put("tag", it) } })

    /** The film's cast's ids on the server, from Tom Hale's (and Ines Moreau's, next, is the show's too). */
    const val CAST = 101L

    /** Ana Ruiz's id, the film's director (Paul Okafor's is the next). */
    const val ANA = 111L

    /** A person's id on Discover, from their [id] on the server. */
    fun tagKey(id: Long) = "5e00000000000000000$id"

    /** People of the crew, with their ids, as the server lists them. */
    private fun people(vararg named: Pair<String, Long>) = JsonArray(
        named.map { (name, id) ->
            buildJsonObject {
                put("tag", name)
                put("id", id)
                put("tagKey", tagKey(id))
            }
        }
    )

    private fun rating(image: String, type: String, value: Double) = buildJsonObject {
        put("image", image)
        put("type", type)
        put("value", value)
    }

    private fun audio(language: String, codec: String, channels: Int, kbps: Int, title: String? = null) = buildJsonObject {
        put("streamType", 2)
        put("language", language)
        put("codec", codec)
        put("channels", channels)
        put("bitrate", kbps)
        title?.let { put("extendedDisplayTitle", it) }
    }

    private fun subtitles(language: String, codec: String, forced: Boolean) = buildJsonObject {
        put("streamType", 3)
        put("language", language)
        put("codec", codec)
        if (forced) put("forced", true)
    }

    private const val MIN = 60_000L
}

/** The board's links: IMDb, TMDB and TVDB, as Plex lists a title's ids. */
private fun guids(vararg ids: String) = buildJsonArray { ids.forEach { id -> add(buildJsonObject { put("id", id) }) } }
