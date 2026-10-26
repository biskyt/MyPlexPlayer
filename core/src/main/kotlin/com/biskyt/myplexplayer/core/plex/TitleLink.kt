package com.biskyt.myplexplayer.core.plex

/** A page about a title elsewhere: [name] ("IMDb") and its address. */
data class TitleLink(val name: String, val url: String)

/** A film's or show's pages on IMDb, TMDB and TVDB, from its ids in Plex (`includeGuids=1`), in that order. */
fun titleLinks(item: PlexItem): List<TitleLink> {
    val show = item.type == "show"
    val ids = item.guids.mapNotNull { g -> g.id.split("://", limit = 2).takeIf { it.size == 2 && it[1].isNotBlank() } }
        .associate { (site, id) -> site to id }
    return listOfNotNull(
        ids["imdb"]?.let { TitleLink("IMDb", "https://www.imdb.com/title/$it/") },
        ids["tmdb"]?.let { TitleLink("TMDB", "https://www.themoviedb.org/${if (show) "tv" else "movie"}/$it") },
        ids["tvdb"]?.let { TitleLink("TVDB", "https://thetvdb.com/dereferrer/${if (show) "series" else "movie"}/$it") },
    )
}
