package com.biskyt.mytvplayer.core.vod

import com.biskyt.mytvplayer.core.epg.CategoryLanguageDetector

/** A movie or series name as people would write it, and its year when the provider's name includes one. */
data class CleanTitle(val title: String, val year: Int?)

/**
 * Providers decorate names: "EN - The Matrix (1999)", "|UK| Line of Duty", "[4K] Dune [2021]". This strips a leading
 * language/quality tag and pulls out a trailing year, so lists read cleanly and titles can be matched later.
 */
object VodTitles {
    // A short tag at the start: "EN - ", "EN: ", "|EN| ", "[EN] ", "(EN) ".
    private val tag = Regex("""^\s*(?:[|\[(]\s*([A-Za-z0-9+]{2,6})\s*[|\])]\s*[-:|]?|([A-Za-z0-9+]{2,6})\s*(?:-|:|\|)\s)\s*""")
    private val qualityTags = setOf("4K", "UHD", "FHD", "HD", "SD", "HEVC", "MULTI", "VOD", "NF", "AMZ", "DSNP", "3D")

    // A year at the end: "(1999)", "[1999]" or "- 1999". A bare number is left alone ("Blade Runner 2049").
    private val year = Regex("""\s*(?:[(\[]((?:19|20)\d{2})[)\]]|-\s*((?:19|20)\d{2}))\s*$""")

    /** [t] without its leading language or quality tag, or null if it has none. */
    private fun withoutTag(t: String): String? {
        val m = tag.find(t) ?: return null
        val bracketed = m.groupValues[1].isNotEmpty()
        val code = (m.groupValues[1].ifEmpty { m.groupValues[2] }).uppercase()
        // Unbracketed codes that are also words ("IT: Chapter Two") are left alone.
        val known = code in qualityTags || (CategoryLanguageDetector.isLanguageCode(code) && (bracketed || code !in wordCodes))
        return if (known) t.substring(m.range.last + 1) else null
    }

    private val wordCodes = setOf("IT", "IN", "NO", "AL", "AR", "AF", "US")
    private val nonWord = Regex("""[^\p{L}\p{N}]+""")

    /**
     * A title reduced for comparing a TV programme with a provider's movie or series: no tags or year, lower case,
     * no punctuation and no leading "the" ("The Simpsons (1989)", "Simpsons" → "simpsons").
     */
    fun key(title: String): String {
        // Apostrophes are dropped rather than split on, so "Grey's" and "Greys" agree.
        val words = clean(title).title.lowercase().replace("'", "").replace("\u2019", "").split(nonWord).filter { it.isNotEmpty() }
        return (if (words.size > 1 && words[0] == "the") words.drop(1) else words).joinToString(" ")
    }

    fun clean(name: String): CleanTitle {
        // A few providers stack tags ("|EN| [4K] Title"); up to two are removed.
        var t = name.trim().let { once -> withoutTag(once)?.let { withoutTag(it) ?: it } ?: once }
        val y = year.find(t)
        val yearValue = y?.groupValues?.drop(1)?.firstOrNull { it.isNotEmpty() }?.toInt()
        if (y != null && y.range.first > 0) t = t.substring(0, y.range.first)
        return CleanTitle(t.trim().trimEnd('-', ':', '|').trim().ifEmpty { name.trim() }, yearValue)
    }
}
