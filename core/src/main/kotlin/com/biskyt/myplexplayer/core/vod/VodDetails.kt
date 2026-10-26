package com.biskyt.myplexplayer.core.vod

import com.biskyt.myplexplayer.core.plex.PlexMediaInfo
import com.biskyt.myplexplayer.core.plex.TitleLink

/** What a details page shows for a movie or series. Any field may be missing: servers fill them unevenly. */
data class VodDetails(
    val plot: String?,
    val year: Int?,
    val durationSec: Int?,
    val backdropUrl: String?,
    val posterUrl: String?,
    /** Out of 10. */
    val rating: Double?,
    val contentRating: String? = null,
    val genres: List<String> = emptyList(),
    val directors: List<String> = emptyList(),
    val writers: List<String> = emptyList(),
    val producers: List<String> = emptyList(),
    val countries: List<String> = emptyList(),
    val collections: List<String> = emptyList(),
    val cast: List<CastMember> = emptyList(),
    val studio: String? = null,
    val originalTitle: String? = null,
    /** "2019-03-14". */
    val released: String? = null,
    /** When it came to the server, in seconds since 1970. */
    val addedAt: Long? = null,
    val links: List<TitleLink> = emptyList(),
    /** A film's file; null for a series (each episode has its own). */
    val media: PlexMediaInfo? = null,
)

/** An actor and the part played; [photoUrl] is loaded only where the cast is shown. */
data class CastMember(val name: String, val role: String?, val photoUrl: String?)

/** A season; [name] is the server's, null when it gives none (the app then names it by [number]). */
data class SeasonInfo(val number: Int, val name: String?, val year: Int? = null, val posterUrl: String? = null)

data class EpisodeInfo(
    val id: Long,
    val season: Int,
    val number: Int,
    /** The server's title; blank when it gave none (the app then names it, see episodeTitle). */
    val title: String,
    val extension: String,
    val plot: String?,
    val durationSec: Int?,
    val imageUrl: String?,
    /** What its file is ("1080p", "Dolby Digital 5.1"), shown while it has focus. */
    val badges: List<String> = emptyList(),
)

data class SeriesDetails(val info: VodDetails, val seasons: List<SeasonInfo>, val episodes: List<EpisodeInfo>)
