package com.biskyt.myplexplayer.core.epg

/**
 * What a guide programme is, from its XMLTV `<category>` and `<episode-num>`: used to offer "Find series" or
 * "Find in Movies" for it. Worked out while the importer reads programmes it keeps anyway, so it is allocation-light:
 * each category is folded into one running value, and episode numbers are read with a plain digit scan.
 */
object ProgrammeKind {
    const val UNKNOWN = 0
    const val SERIES = 1
    const val MOVIE = 2

    /** News, sport and the like: neither a series nor a film. */
    const val OTHER = 3

    private val movies = setOf("movie", "movies", "film", "films", "feature film", "tvmovie", "tv movie", "cinema")
    private val series = setOf("series", "tv series", "sitcom", "soap", "soap opera", "drama series", "episode", "tvshow", "tv show")
    private val other = setOf(
        "news", "sport", "sports", "weather", "live", "news / current affairs", "current affairs", "sports event",
        "talk", "talk show"
    )

    /**
     * The kind so far after one more `<category>` text. A film wins over anything; a series over "other"
     * (a sports series is still a series).
     */
    fun fold(kind: Int, category: String): Int {
        // DVB-style genres name the main one first: "Movie / Drama", "Sports - Football".
        val full = category.trim().lowercase()
        val cut = full.indexOfAny(charArrayOf('/', '-', ','))
        val c = if (cut > 0 && full !in other) full.substring(0, cut).trim() else full
        return when {
            c in movies -> MOVIE
            kind == MOVIE -> MOVIE
            c in series -> SERIES
            kind == SERIES -> SERIES
            c in other -> OTHER
            else -> kind
        }
    }

    /** The final kind: an episode number makes anything that isn't a film a series. */
    fun finish(kind: Int, hasEpisode: Boolean) = if (hasEpisode && kind != MOVIE) SERIES else kind

    /**
     * Season and episode (both from 1, season 0 when unknown) packed as season * 10_000 + episode, so the importer
     * allocates nothing for it; null without an episode. [system] is the `episode-num` system: "xmltv_ns" ("1.4.0/1",
     * zero-based, the season may be missing) or "onscreen" ("S02E05", "S2 E5", "Season 2 Episode 5", "Episode 5").
     */
    fun parseEpisode(system: String?, value: String): Int? = when (system) {
        "xmltv_ns" -> xmltvNs(value)
        "onscreen" -> onscreen(value)
        else -> null
    }

    fun season(packed: Int) = (packed / PACK).takeIf { it > 0 }
    fun episode(packed: Int) = packed % PACK

    private fun xmltvNs(v: String): Int? {
        // "season.episode.part", each optionally "n/total"; numbers are zero-based.
        val dot = v.indexOf('.')
        if (dot < 0) return null
        val season = leadingNumber(v, 0)
        val episode = leadingNumber(v, dot + 1) ?: return null
        return pack(season?.plus(1) ?: 0, episode + 1)
    }

    private fun onscreen(v: String): Int? {
        // The first number after an "s" (or "season") and the first after an "e" (or "episode").
        var season: Int? = null
        var episode: Int? = null
        var i = 0
        while (i < v.length) {
            val ch = v[i].lowercaseChar()
            if ((ch == 's' || ch == 'e') && (i == 0 || !v[i - 1].isLetter())) {
                var j = i + 1
                while (j < v.length && (v[j].isLetter() || v[j] == ' ')) j++
                val n = leadingNumber(v, j)
                if (n != null) {
                    if (ch == 's' && season == null) {
                        season = n
                    } else if (ch == 'e' && episode == null) {
                        episode = n
                    }
                }
            }
            i++
        }
        return if (episode != null && episode > 0) pack(season ?: 0, episode) else null
    }

    /** The number starting at [from] (after spaces), or null. */
    private fun leadingNumber(v: String, from: Int): Int? {
        var i = from
        while (i < v.length && v[i] == ' ') i++
        var n = 0
        var digits = 0
        while (i < v.length && v[i] in '0'..'9' && digits < MAX_DIGITS) {
            n = n * 10 + (v[i] - '0')
            i++
            digits++
        }
        return if (digits == 0) null else n
    }

    private fun pack(season: Int, episode: Int) = if (season < PACK && episode < PACK) season * PACK + episode else null

    private const val PACK = 10_000
    private const val MAX_DIGITS = 4
}
