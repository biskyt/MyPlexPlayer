package com.biskyt.mytvplayer.core.player

enum class SubtitleMode { OFF, FORCED_ONLY, ALWAYS }

/** A text track as reported by the player, reduced to what the policy needs. */
data class TextTrack(val id: String, val language: String?, val forced: Boolean)

/**
 * Chooses the default subtitle track when playback starts.
 * - OFF: none.
 * - FORCED_ONLY: a forced track in [language], else any forced track with no language tag.
 * - ALWAYS: a full (non-forced) track in [language], else a forced one in [language].
 * Languages are compared on their primary subtag ("en" matches "en-GB" and "eng").
 */
object SubtitlePolicy {
    fun choose(tracks: List<TextTrack>, mode: SubtitleMode, language: String): TextTrack? {
        if (mode == SubtitleMode.OFF) return null
        val inLanguage = tracks.filter { sameLanguage(it.language, language) }
        return when (mode) {
            SubtitleMode.FORCED_ONLY -> inLanguage.firstOrNull { it.forced } ?: tracks.firstOrNull { it.forced && it.language.isNullOrBlank() }
            SubtitleMode.ALWAYS -> inLanguage.firstOrNull { !it.forced } ?: inLanguage.firstOrNull()
            SubtitleMode.OFF -> null
        }
    }

    fun sameLanguage(a: String?, b: String?): Boolean {
        val x = primary(a) ?: return false
        return x == primary(b)
    }

    private fun primary(tag: String?): String? {
        val p = tag?.trim()?.lowercase()?.substringBefore('-')?.substringBefore('_')?.takeIf { it.isNotEmpty() } ?: return null
        return iso3to2[p] ?: p
    }

    // Streams often tag tracks with ISO 639-2 codes.
    private val iso3to2 = mapOf(
        "eng" to "en", "fra" to "fr", "fre" to "fr", "deu" to "de", "ger" to "de", "spa" to "es", "ita" to "it",
        "por" to "pt", "nld" to "nl", "dut" to "nl", "pol" to "pl", "tur" to "tr", "ara" to "ar", "hin" to "hi",
        "ell" to "el", "gre" to "el", "ron" to "ro", "rum" to "ro", "swe" to "sv", "nor" to "no", "dan" to "da",
        "fin" to "fi", "rus" to "ru", "zho" to "zh", "chi" to "zh", "kor" to "ko", "jpn" to "ja",
    )
}
