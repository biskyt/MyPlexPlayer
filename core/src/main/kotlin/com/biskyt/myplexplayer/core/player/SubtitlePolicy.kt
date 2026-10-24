package com.biskyt.myplexplayer.core.player

import com.biskyt.myplexplayer.core.Languages

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
        fun inLanguage(t: TextTrack) = sameLanguage(t.language, language)
        return when (mode) {
            SubtitleMode.OFF -> null
            SubtitleMode.FORCED_ONLY -> tracks.firstOrNull { it.forced && inLanguage(it) }
                ?: tracks.firstOrNull { it.forced && it.language.isNullOrBlank() }
            SubtitleMode.ALWAYS -> tracks.firstOrNull { !it.forced && inLanguage(it) } ?: tracks.firstOrNull { inLanguage(it) }
        }
    }

    fun sameLanguage(a: String?, b: String?): Boolean {
        val x = primary(a) ?: return false
        return x == primary(b)
    }

    private fun primary(tag: String?): String? = tag?.trim()?.takeIf { it.isNotEmpty() }?.let(Languages::primary)
}
