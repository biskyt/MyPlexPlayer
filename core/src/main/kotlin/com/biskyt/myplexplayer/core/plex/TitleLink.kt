package com.biskyt.myplexplayer.core.plex

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** A page about a title elsewhere: [name] ("IMDb") and its address. */
data class TitleLink(val name: String, val url: String)

/** A film's or show's pages on IMDb, TMDB and TVDB, from its ids in Plex (`includeGuids=1`), in that order. */
fun titleLinks(item: PlexItem): List<TitleLink> {
    val show = item.type == "show"
    val ids = guidIds(item.guids)
    return listOfNotNull(
        ids["imdb"]?.let { TitleLink("IMDb", "https://www.imdb.com/title/$it/") },
        ids["tmdb"]?.let { TitleLink("TMDB", "https://www.themoviedb.org/${if (show) "tv" else "movie"}/$it") },
        ids["tvdb"]?.let { TitleLink("TVDB", "https://thetvdb.com/dereferrer/${if (show) "series" else "movie"}/$it") },
    )
}

/** A title's id on TMDB, from its `tmdb://` guid; null when it has none (or one that is not a number). */
fun tmdbIdOf(guids: List<PlexGuid>): Int? = guidIds(guids)["tmdb"]?.toIntOrNull()?.takeIf { it > 0 }

/** A title's ids elsewhere by site: "imdb" to "tt0118799", from guids such as `imdb://tt0118799`. */
private fun guidIds(guids: List<PlexGuid>): Map<String, String> =
    guids.mapNotNull { g -> g.id.split("://", limit = 2).takeIf { it.size == 2 && it[1].isNotBlank() } }
        .associate { (site, id) -> site to id }

/**
 * A title on Discover, of which only its ids elsewhere are read ([PlexAccount.tmdbId]). Discover's title does not read as
 * a [PlexItem]: some of its fields have other shapes.
 */
@Serializable
internal class PlexGuidsResponse(@SerialName("MediaContainer") val container: PlexGuidsContainer = PlexGuidsContainer())

@Serializable
internal class PlexGuidsContainer(
    @SerialName("Metadata") @Serializable(with = SkippingList::class) val items: List<PlexGuids> = emptyList()
)

@Serializable
internal class PlexGuids(@SerialName("Guid") @Serializable(with = SkippingList::class) val guids: List<PlexGuid> = emptyList())
