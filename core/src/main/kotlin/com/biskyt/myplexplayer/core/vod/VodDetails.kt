package com.biskyt.myplexplayer.core.vod

/** What a details page shows for a movie or series. Any field may be missing: servers fill them unevenly. */
data class VodDetails(
    val plot: String?,
    val cast: String?,
    val director: String?,
    val genre: String?,
    val year: Int?,
    val durationSec: Int?,
    val backdropUrl: String?,
    val posterUrl: String?,
    val rating: Double?
)

/** A season; [name] is the server's, null when it gives none (the app then names it by [number]). */
data class SeasonInfo(val number: Int, val name: String?)

data class EpisodeInfo(
    val id: Long,
    val season: Int,
    val number: Int,
    /** The server's title; blank when it gave none (the app then names it, see episodeTitle). */
    val title: String,
    val extension: String,
    val plot: String?,
    val durationSec: Int?,
    val imageUrl: String?
)

data class SeriesDetails(val info: VodDetails, val seasons: List<SeasonInfo>, val episodes: List<EpisodeInfo>)
