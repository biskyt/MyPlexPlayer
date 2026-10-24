package com.biskyt.mytvplayer.core.epg

import java.text.Normalizer

/**
 * Reduces provider channel names like `UK: ARENA SPRTS 1 FHD *RAW*` or `UK| MOTOR TV ᴴᴰ` to a comparable
 * form (`arena sprts 1`, `motor tv`). Timeshift markers such as `+1` are kept because they are a different channel.
 * Words in brackets are kept (US stations put their call sign there: `NBC 4 (WNBC) NEW YORK`), US states become their
 * two-letter codes (`new york` → `ny`), and east/west feeds read the same however they're written.
 */
object ChannelNames {
    private val marks = Regex("\\p{M}+")
    private val separators = Regex("[^a-z0-9]+")

    // "UK: ", "[US] ", "UK - 5 Star", "UK-": not the dash in "HBO-2".
    private val prefix = Regex("""^\s*[|\[(]?\s*[A-Za-z]{2,3}\s*(?:[|:\])]|-(?!\d))\s*""")
    private val noise = setOf(
        "hd", "fhd", "uhd", "sd", "4k", "8k", "hevc", "h265", "h264", "raw", "backup", "vip", "50fps", "60fps", "hq", "lq",
        "1080p", "720p", "1080", "720", "multi", "feed"
    )
    private val geoBlocked = Regex("""geo\W*blocked""", RegexOption.IGNORE_CASE)

    // Numbers in brackets are notes ("(Backup 2)"), not the channel's number; the words stay (a call sign: "(WNBC)").
    private val bracketedNumbers = Regex("""[\[(][^\])]*\d[^\])]*[\])]""")
    private val digits = Regex("""\b\w*\d\w*\b""")
    private val plusN = Regex("""\+\s*(\d)""")
    private val feeds = mapOf("e" to "east", "w" to "west", "eastern" to "east", "western" to "west")
    private val states = mapOf(
        "alabama" to "al", "alaska" to "ak", "arizona" to "az", "arkansas" to "ar", "california" to "ca", "colorado" to "co",
        "connecticut" to "ct", "delaware" to "de", "florida" to "fl", "georgia" to "ga", "hawaii" to "hi", "idaho" to "id",
        "illinois" to "il", "indiana" to "in", "iowa" to "ia", "kansas" to "ks", "kentucky" to "ky", "louisiana" to "la",
        "maine" to "me", "maryland" to "md", "massachusetts" to "ma", "michigan" to "mi", "minnesota" to "mn",
        "mississippi" to "ms", "missouri" to "mo", "montana" to "mt", "nebraska" to "ne", "nevada" to "nv",
        "new hampshire" to "nh", "new jersey" to "nj", "new mexico" to "nm", "new york" to "ny", "north carolina" to "nc",
        "north dakota" to "nd", "ohio" to "oh", "oklahoma" to "ok", "oregon" to "or", "pennsylvania" to "pa",
        "rhode island" to "ri", "south carolina" to "sc", "south dakota" to "sd", "tennessee" to "tn", "texas" to "tx",
        "utah" to "ut", "vermont" to "vt", "virginia" to "va", "washington" to "wa", "west virginia" to "wv",
        "wisconsin" to "wi", "wyoming" to "wy", "district of columbia" to "dc"
    )

    /** "Curaçao" → "Curacao"; NFKD also folds superscript letters such as ᴴᴰ into plain HD. */
    fun stripAccents(text: String): String = marks.replace(Normalizer.normalize(text, Normalizer.Form.NFKD), "")

    fun normalize(name: String): String {
        var s = stripAccents(name)
        s = prefix.replace(s, "")
        s = geoBlocked.replace(s, " ")
        s = bracketedNumbers.replace(s) { digits.replace(it.value, " ") }
        s = plusN.replace(s) { " plus${it.groupValues[1]} " }
        s = s.replace("&", " and ").lowercase()
        val words = s.split(separators).filter { it.isNotEmpty() && it !in noise }
        val out = StringBuilder()
        var i = 0
        while (i < words.size) {
            // A state of up to three words ("district of columbia"), longest first so "west virginia" beats "virginia".
            val (word, used) = (3 downTo 1).firstNotNullOfOrNull { n ->
                if (i + n > words.size) null else states[words.subList(i, i + n).joinToString(" ")]?.let { it to n }
            } ?: ((if (i > 0) feeds[words[i]] else null) ?: words[i]) to 1
            // A name's first word stays as it is ("E!", "W"). Later single letters are a feed ("HBO (E)") or say
            // nothing about which channel it is (a provider's "(A)").
            if (i > 0 && word.length == 1 && word[0].isLetter()) {
                i += used
                continue
            }
            i += used
            if (out.isNotEmpty()) out.append(' ')
            out.append(word)
        }
        return out.toString()
    }

    fun tokens(normalized: String): Set<String> = normalized.split(' ').filter { it.isNotEmpty() }.toSet()
}
