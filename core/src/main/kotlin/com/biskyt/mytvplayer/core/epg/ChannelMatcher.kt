package com.biskyt.mytvplayer.core.epg

import kotlin.math.max
import kotlin.math.min

/** A channel from an EPG source that playlist channels can be matched to. */
data class EpgCandidate(val sourceId: Long, val xmlId: String, val displayName: String, val normalized: String)

data class EpgMatch(val candidate: EpgCandidate, val score: Double)

/**
 * Suggests EPG channels for playlist channels. Built once from all candidates, then queried per channel.
 *
 * Matching runs for thousands of channels against tens of thousands of guide channels on a slow CPU, so: candidate
 * tokens are computed once here, shared tokens are counted while walking an inverted index, very common words are
 * ignored when rarer ones exist, and the string-distance check only runs on the best few candidates.
 */
class ChannelMatcher(candidates: List<EpgCandidate>) {
    private class Entry(val candidate: EpgCandidate, val tokens: Set<String>, val numbers: Set<String>)

    private val entries = candidates.map { c -> ChannelNames.tokens(c.normalized).let { t -> Entry(c, t, t.numberTokens()) } }
    private val byXmlId = entries.groupBy { it.candidate.xmlId.lowercase() }
    private val byNormalized = entries.groupBy { it.candidate.normalized }
    private val byToken = HashMap<String, MutableList<Entry>>().also { index ->
        for (e in entries) for (t in e.tokens) index.getOrPut(t) { ArrayList() }.add(e)
    }
    private val commonTokenSize = max(COMMON_TOKEN_MIN, entries.size / COMMON_TOKEN_DIVISOR)

    /** Best automatic match, or null when nothing reaches [AUTO_THRESHOLD]. */
    fun autoMatch(channelName: String, providerEpgId: String?): EpgMatch? =
        suggestions(channelName, providerEpgId, limit = 1).firstOrNull()?.takeIf { it.score >= AUTO_THRESHOLD }

    fun suggestions(channelName: String, providerEpgId: String?, limit: Int = 5): List<EpgMatch> {
        val results = LinkedHashMap<EpgCandidate, Double>()
        providerEpgId?.lowercase()?.let { id -> byXmlId[id]?.forEach { results[it.candidate] = 1.0 } }

        val norm = ChannelNames.normalize(channelName)
        if (norm.isNotEmpty()) {
            byNormalized[norm]?.forEach { results.putIfAbsent(it.candidate, EXACT_NAME_SCORE) }
            fuzzy(norm, results)
        }
        return results.entries.sortedByDescending { it.value }.take(limit).map { EpgMatch(it.key, it.value) }
    }

    private fun fuzzy(norm: String, results: MutableMap<EpgCandidate, Double>) {
        val tokens = ChannelNames.tokens(norm)
        val numbers = tokens.numberTokens()
        val buckets = tokens.mapNotNull { byToken[it] }
        // Words like "tv" or "sport" appear in thousands of guide channels; only use them if nothing rarer matches.
        val useful = buckets.filter { it.size <= commonTokenSize }.ifEmpty { buckets }
        val shared = HashMap<Entry, Int>()
        for (bucket in useful) for (e in bucket) shared.merge(e, 1, Int::plus)

        shared.entries
            .map { (e, n) -> e to 2.0 * n / (tokens.size + e.tokens.size) }
            .sortedByDescending { it.second }
            .take(FUZZY_CANDIDATES)
            .forEach { (e, dice) ->
                if (e.candidate in results) return@forEach
                var score = 0.6 * dice + 0.4 * jaroWinkler(norm, e.candidate.normalized)
                if (numbers != e.numbers) score *= 0.6 // "Sports 1" is not "Sports 10"
                if (score >= MIN_SUGGESTION) results[e.candidate] = score
            }
    }

    companion object {
        const val AUTO_THRESHOLD = 0.85
        private const val EXACT_NAME_SCORE = 0.97
        private const val MIN_SUGGESTION = 0.4
        private const val FUZZY_CANDIDATES = 40
        private const val COMMON_TOKEN_MIN = 200
        private const val COMMON_TOKEN_DIVISOR = 20

        private fun Set<String>.numberTokens() = filterTo(HashSet()) { t -> t.any(Char::isDigit) }

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
