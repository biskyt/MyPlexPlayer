package com.biskyt.myplexplayer.core.vod

import com.biskyt.myplexplayer.core.epg.CategoryLanguageDetector

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

    /** A few providers stack tags ("|EN| [4K] Title"): up to two are removed. */
    private fun withoutTags(t: String) = withoutTag(t)?.let { withoutTag(it) ?: it } ?: t

    private val yearWord = Regex("""(?:19|20)\d{2}""")
    private val nonWord = Regex("""[^\p{L}\p{N}]+""")

    /**
     * A title reduced for comparing a TV programme with a provider's movie or series: no tags or year, lower case,
     * no punctuation and no leading "the" ("The Simpsons (1989)", "Simpsons" → "simpsons").
     */
    fun key(title: String): String {
        // Apostrophes are dropped rather than split on, so "Grey's" and "Greys" agree.
        // The series' rules, for a series' name and a programme's alike, so that both sides lose the same brackets.
        val words = clean(title, series = true).title.lowercase().replace("'", "").replace("\u2019", "").split(nonWord).filter {
            it.isNotEmpty()
        }
        return (if (words.size > 1 && words[0] == "the") words.drop(1) else words).joinToString(" ")
    }

    /**
     * [t] without what a provider adds after a name (the owner, 2026-11-25): anything in square brackets ("[4K]",
     * "[MULTI SUB]") and, for a [series], in round ones too ("(US)"), with the year found in them ("[2021]"). A bracket
     * before the name starts is kept ("[REC]"). Null when it removes nothing, as for most names, which are not copied.
     * One pass over the letters, with no patterns: a playlist can have tens of thousands of names.
     */
    private fun withoutExtras(t: String, series: Boolean): CleanTitle? {
        if (t.indexOf('[') < 0 && !(series && t.indexOf('(') >= 0)) return null
        var out: StringBuilder? = null
        var year: Int? = null
        var started = false
        var i = 0
        while (i < t.length) {
            val c = t[i]
            val end = if (started) groupEnd(t, i, series) else -1
            if (end < 0) {
                out?.append(c)
                if (!c.isWhitespace()) started = true
                i++
                continue
            }
            // The first group removed: the name so far, copied once.
            val kept = out ?: StringBuilder(t.length).append(t, 0, i).also { out = it }
            // The last year in them counts, as a year at the end of a name does.
            year = t.substring(i + 1, end).trim().takeIf(yearWord::matches)?.toInt() ?: year
            // One space in place of the group and the spaces around it; none before punctuation ("Dune [4K]: Part Two").
            while (kept.isNotEmpty() && kept.last().isWhitespace()) kept.setLength(kept.length - 1)
            i = end + 1
            while (i < t.length && t[i].isWhitespace()) i++
            if (i < t.length && t[i] !in CLOSING_PUNCTUATION) kept.append(' ')
        }
        return out?.let { CleanTitle(it.toString(), year) }
    }

    /**
     * Where a group that goes ends, when one starts at [from]: a square bracket's, or with [series] a round one's. Else
     * -1, as when another of its kind opens first or none closes.
     */
    private fun groupEnd(t: String, from: Int, series: Boolean): Int {
        val open = t[from]
        val close = when {
            open == '[' -> ']'
            open == '(' && series -> ')'
            else -> return -1
        }
        for (k in from + 1 until t.length) {
            when (t[k]) {
                close -> return if (open == '(' && isCountryCode(t, from + 1, k)) -1 else k
                open -> return -1
            }
        }
        return -1
    }

    /**
     * Whether [t] from [start] to [end] is a country's code in capitals ("US", "UK", "GB"): it tells apart two series of
     * one name ("The Office (US)" and "The Office (UK)"), in the lists, on TMDB and in media servers, so it stays.
     */
    private fun isCountryCode(t: String, start: Int, end: Int): Boolean =
        end - start == COUNTRY_CODE && (start until end).all { t[it] in 'A'..'Z' }

    /** Letters of a country's code (ISO 3166-1, and "UK"). */
    private const val COUNTRY_CODE = 2

    /** Where no space goes before, after a group taken out of a name. */
    private const val CLOSING_PUNCTUATION = ":;,.!?)]"

    /**
     * A movie's or (with [series]) a series' name without the provider's decorations, and its year: a language or
     * quality tag at the start, a year at the end, anything in square brackets after the name and, for a series,
     * anything in round brackets too (the owner, 2026-11-25). A film keeps its round brackets ("(500) Days of Summer").
     * Run once as a playlist loads: the lists show the names as they are saved.
     */
    fun clean(name: String, series: Boolean): CleanTitle {
        val tagless = withoutTags(name.trim())
        val extras = withoutExtras(tagless, series)
        val t = extras?.title ?: tagless
        val y = year.find(t)
        val yearValue = y?.groupValues?.drop(1)?.firstOrNull { it.isNotEmpty() }?.toInt() ?: extras?.year
        val kept = if (y != null && y.range.first > 0) t.substring(0, y.range.first) else t
        return CleanTitle(kept.trim().trimEnd('-', ':', '|').trim().ifEmpty { name.trim() }, yearValue)
    }
}
