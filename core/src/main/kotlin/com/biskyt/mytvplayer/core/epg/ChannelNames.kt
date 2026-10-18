package com.biskyt.mytvplayer.core.epg

import java.text.Normalizer

/**
 * Reduces provider channel names like `UK: ARENA SPRTS 1 FHD *RAW*` or `UK| MOTOR TV ᴴᴰ` to a comparable
 * form (`arena sprts 1`, `motor tv`). Timeshift markers such as `+1` are kept because they are a different channel.
 */
object ChannelNames {
    private val marks = Regex("\\p{M}+")
    private val separators = Regex("[^a-z0-9]+")
    private val prefix = Regex("""^\s*[|\[(]?\s*[A-Za-z]{2,3}\s*[|:\-\])]\s*""")
    private val bracketed = Regex("""[\[(*][^\])*]*[\])*]""")
    private val noise = setOf(
        "hd", "fhd", "uhd", "sd", "4k", "8k", "hevc", "h265", "h264", "raw", "backup", "vip", "50fps", "60fps", "hq", "lq",
        "1080p", "720p", "1080", "720", "multi"
    )
    private val plusN = Regex("""\+\s*(\d)""")

    fun normalize(name: String): String {
        // NFKD also folds superscript letters such as ᴴᴰ into plain HD.
        var s = marks.replace(Normalizer.normalize(name, Normalizer.Form.NFKD), "")
        s = prefix.replace(s, "")
        s = bracketed.replace(s, " ")
        s = plusN.replace(s) { " plus${it.groupValues[1]} " }
        s = s.replace("&", " and ").lowercase()
        val tokens = s.split(separators).filter { it.isNotEmpty() && it !in noise }
        return tokens.joinToString(" ")
    }

    fun tokens(normalized: String): Set<String> = normalized.split(' ').filter { it.isNotEmpty() }.toSet()
}
