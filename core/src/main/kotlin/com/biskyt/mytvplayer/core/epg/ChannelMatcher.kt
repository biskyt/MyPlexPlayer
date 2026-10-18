package com.biskyt.mytvplayer.core.epg

import kotlin.math.max
import kotlin.math.min

/** A channel from an EPG source that playlist channels can be matched to. */
data class EpgCandidate(val sourceId: Long, val xmlId: String, val displayName: String, val normalized: String)

data class EpgMatch(val candidate: EpgCandidate, val score: Double)

/**
 * Suggests EPG channels for playlist channels. Built once per refresh from all candidates, then queried per channel.
 * An inverted token index keeps lookups cheap even with tens of thousands of candidates.
 */
class ChannelMatcher(candidates: List<EpgCandidate>) {
    private val byXmlId = candidates.groupBy { it.xmlId.lowercase() }
    private val byNormalized = candidates.groupBy { it.normalized }
    private val byToken = HashMap<String, MutableList<EpgCandidate>>().also { index ->
        for (c in candidates) for (t in ChannelNames.tokens(c.normalized)) index.getOrPut(t) { ArrayList() }.add(c)
    }

    /** Best automatic match, or null when nothing reaches [AUTO_THRESHOLD]. */
    fun autoMatch(channelName: String, providerEpgId: String?): EpgMatch? =
        suggestions(channelName, providerEpgId, limit = 1).firstOrNull()?.takeIf { it.score >= AUTO_THRESHOLD }

    fun suggestions(channelName: String, providerEpgId: String?, limit: Int = 5): List<EpgMatch> {
        val results = LinkedHashMap<EpgCandidate, Double>()
        providerEpgId?.lowercase()?.let { id -> byXmlId[id]?.forEach { results[it] = 1.0 } }

        val norm = ChannelNames.normalize(channelName)
        if (norm.isEmpty()) return results.toMatches(limit)
        byNormalized[norm]?.forEach { results.putIfAbsent(it, EXACT_NAME_SCORE) }

        val tokens = ChannelNames.tokens(norm)
        val seen = HashSet<EpgCandidate>()
        for (t in tokens) {
            val bucket = byToken[t] ?: continue
            for (c in bucket) {
                if (c in results || !seen.add(c)) continue
                val s = similarity(norm, tokens, c.normalized)
                if (s >= MIN_SUGGESTION) results[c] = s
            }
        }
        return results.toMatches(limit)
    }

    private fun Map<EpgCandidate, Double>.toMatches(limit: Int) =
        entries.sortedByDescending { it.value }.take(limit).map { EpgMatch(it.key, it.value) }

    companion object {
        const val AUTO_THRESHOLD = 0.85
        private const val EXACT_NAME_SCORE = 0.97
        private const val MIN_SUGGESTION = 0.4

        /** Token overlap blended with Jaro-Winkler; channels whose numbers differ ("Sports 1" vs "Sports 10") are heavily penalised. */
        fun similarity(a: String, aTokens: Set<String>, b: String): Double {
            val bTokens = ChannelNames.tokens(b)
            val numbersA = aTokens.filter { t -> t.any(Char::isDigit) }.toSet()
            val numbersB = bTokens.filter { t -> t.any(Char::isDigit) }.toSet()
            val inter = aTokens.intersect(bTokens).size.toDouble()
            val dice = 2 * inter / (aTokens.size + bTokens.size).coerceAtLeast(1)
            var score = 0.6 * dice + 0.4 * jaroWinkler(a, b)
            if (numbersA != numbersB) score *= 0.6
            return score
        }

        fun jaroWinkler(s1: String, s2: String): Double {
            if (s1 == s2) return 1.0
            if (s1.isEmpty() || s2.isEmpty()) return 0.0
            val range = max(0, max(s1.length, s2.length) / 2 - 1)
            val m1 = BooleanArray(s1.length)
            val m2 = BooleanArray(s2.length)
            var matches = 0
            for (i in s1.indices) {
                for (j in max(0, i - range) until min(s2.length, i + range + 1)) {
                    if (!m2[j] && s1[i] == s2[j]) {
                        m1[i] = true
                        m2[j] = true
                        matches++
                        break
                    }
                }
            }
            if (matches == 0) return 0.0
            var t = 0
            var k = 0
            for (i in s1.indices) {
                if (!m1[i]) continue
                while (!m2[k]) k++
                if (s1[i] != s2[k]) t++
                k++
            }
            val m = matches.toDouble()
            val jaro = (m / s1.length + m / s2.length + (m - t / 2.0) / m) / 3
            var prefix = 0
            while (prefix < min(4, min(s1.length, s2.length)) && s1[prefix] == s2[prefix]) prefix++
            return jaro + prefix * 0.1 * (1 - jaro)
        }
    }
}
