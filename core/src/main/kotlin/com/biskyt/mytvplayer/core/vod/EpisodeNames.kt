package com.biskyt.mytvplayer.core.vod

/** An episode named in a playlist: its show, where it falls in the show, and its own title if given. */
data class EpisodeName(val show: String, val season: Int, val episode: Int, val title: String?)

/**
 * Recognises series episodes in M3U entry names, which providers write as "Show S01E02", "Show S01 E02 - Pilot",
 * "Show.Name.S01E02.1080p", "Show 1x02" or "Show Season 1 Episode 2". Anything else is taken as a film.
 */
object EpisodeNames {
    private val number = Regex(
        """(?i)(?:^|[\s._\-|(\[])(?:S(\d{1,3})\s*[ ._-]?\s*E(\d{1,4})(?:-?E\d{1,4})?|(\d{1,2})x(\d{2,3})|Season\s*(\d{1,3})\s*[,.-]?\s*Episode\s*(\d{1,4}))(?=$|[\s._\-|:)\]])"""
    )

    // What's left after the number of a file-style name, which isn't a title ("1080p", "WEB-DL", "x264").
    private val releaseJunk = Regex("""(?i)^(?:\d{3,4}p|4k|uhd|hdr|web(?:-?dl|rip)?|bluray|hdtv|x26[45]|h\.?26[45]|hevc|aac|multi)\b.*""")

    fun parse(name: String): EpisodeName? {
        if (name.none(Char::isDigit)) return null // most films: no need for the pattern
        val m = number.find(name) ?: return null
        val g = m.groupValues
        val season = g[1].ifEmpty { g[3] }.ifEmpty { g[5] }.toInt()
        val episode = g[2].ifEmpty { g[4] }.ifEmpty { g[6] }.toInt()
        // A bracket opened just before the number, or closed just after it, goes with it: "Show (S01E02)".
        val show = tidy(name.substring(0, m.range.first).trimEnd().trimEnd('(', '[')).ifEmpty { return null }
        val title = tidy(name.substring(m.range.last + 1).trimStart().trimStart(')', ']')).takeIf {
            it.isNotEmpty() &&
                !releaseJunk.matches(it)
        }
        return EpisodeName(show, season, episode, title)
    }

    /** Separators trimmed, and a file-style name ("Show.Name") given its spaces back. */
    private fun tidy(part: String): String {
        val t = part.trim().trim('-', ':', '|', '.', '_', ' ').trim()
        return if (' ' !in t && ('.' in t || '_' in t)) t.replace('.', ' ').replace('_', ' ') else t
    }
}
