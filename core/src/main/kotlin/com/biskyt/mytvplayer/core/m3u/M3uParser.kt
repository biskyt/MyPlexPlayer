package com.biskyt.mytvplayer.core.m3u

import java.io.BufferedReader

/** One channel from an M3U playlist. */
data class M3uEntry(
    val name: String,
    val url: String,
    val tvgId: String?,
    val logo: String?,
    val group: String?,
    val number: Int?,
    val userAgent: String?,
    val referrer: String?,
)

/**
 * Streaming M3U/M3U8 playlist reader (the `#EXTINF` format used by IPTV providers and iptv-org).
 * Reads line by line, so playlists with tens of thousands of channels never sit in memory at once.
 */
object M3uParser {
    /** Whether the first non-blank line of a file looks like an M3U playlist. */
    fun looksLikePlaylist(firstLine: String): Boolean = clean(firstLine).let { it.startsWith("#EXTM3U") || it.startsWith("#EXTINF") }

    /** Guide (XMLTV) URLs announced in the `#EXTM3U` header (`x-tvg-url` / `url-tvg`), if any. */
    fun headerGuideUrls(firstLine: String): List<String> {
        if (!firstLine.startsWith("#EXTM3U")) return emptyList()
        val attrs = attributes(firstLine, firstLine.length)
        return listOfNotNull(attrs["x-tvg-url"], attrs["url-tvg"])
            .flatMap { it.split(',') }
            .map { it.trim() }
            .filter { it.isNotEmpty() }
    }

    private fun clean(line: String) = line.trim().removePrefix("\uFEFF")

    /**
     * `key="value"` pairs before [end], keys lower-cased. A hand-written scan: this runs for every channel of
     * playlists with tens of thousands of entries, where a regex's per-match allocations add up on slow boxes.
     */
    private fun attributes(line: String, end: Int): Map<String, String> {
        val out = HashMap<String, String>(8)
        var eq = line.indexOf("=\"")
        var close = if (eq >= 0) line.indexOf('"', eq + 2) else -1
        while (eq in 0 until end && close >= 0) {
            var k = eq
            while (k > 0 && (line[k - 1].isLetterOrDigit() || line[k - 1] == '-')) k--
            if (k < eq) out[line.substring(k, eq).lowercase()] = line.substring(eq + 2, close).trim()
            eq = line.indexOf("=\"", close + 1)
            close = if (eq >= 0) line.indexOf('"', eq + 2) else -1
        }
        return out
    }

    /** Calls [onEntry] for every channel; returns the guide URLs from the header. */
    suspend fun parse(reader: BufferedReader, onEntry: suspend (M3uEntry) -> Unit): List<String> {
        var guides = emptyList<String>()
        var info: String? = null
        var vlcUserAgent: String? = null
        var vlcReferrer: String? = null
        for (raw in reader.lineSequence()) {
            val line = clean(raw)
            when {
                line.isEmpty() -> Unit
                line.startsWith("#EXTM3U") -> guides = guides + headerGuideUrls(line)
                line.startsWith("#EXTINF") -> {
                    info = line
                    vlcUserAgent = null
                    vlcReferrer = null
                }
                line.startsWith("#EXTVLCOPT:http-user-agent=") -> vlcUserAgent = line.substringAfter('=')
                line.startsWith("#EXTVLCOPT:http-referrer=") -> vlcReferrer = line.substringAfter('=')
                line.startsWith("#") -> Unit
                info != null -> {
                    onEntry(entry(info, line, vlcUserAgent, vlcReferrer))
                    info = null
                }
            }
        }
        return guides
    }

    private fun entry(info: String, url: String, vlcUserAgent: String?, vlcReferrer: String?): M3uEntry {
        // The display name follows the first comma outside quoted attribute values (names may contain commas).
        var inQuotes = false
        var comma = -1
        for (i in info.indices) {
            val ch = info[i]
            if (ch == '"') inQuotes = !inQuotes
            if (ch == ',' && !inQuotes) {
                comma = i
                break
            }
        }
        val attrs = attributes(info, if (comma >= 0) comma else info.length)
        val name = (if (comma >= 0) info.substring(comma + 1).trim() else "").ifEmpty { attrs["tvg-name"].orEmpty() }.ifEmpty { url }
        return M3uEntry(
            name = name,
            url = url,
            tvgId = attrs["tvg-id"]?.ifEmpty { null },
            logo = attrs["tvg-logo"]?.ifEmpty { null },
            group = attrs["group-title"]?.ifEmpty { null },
            number = attrs["tvg-chno"]?.toIntOrNull(),
            userAgent = attrs["http-user-agent"]?.ifEmpty { null } ?: vlcUserAgent,
            referrer = attrs["http-referrer"]?.ifEmpty { null } ?: vlcReferrer,
        )
    }
}
