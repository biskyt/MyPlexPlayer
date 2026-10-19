package com.biskyt.mytvplayer.core.epg

import kotlin.math.ln
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
    private class Entry(val candidate: EpgCandidate, val tokens: Set<String>) {
        val shifts = tokens.shifts()
        val numbers = tokens.numbers()
        var weight = 0.0
    }

    private val entries = candidates.map { c -> Entry(c, ChannelNames.tokens(c.normalized)) }
    private val byXmlId = entries.groupBy { it.candidate.xmlId.lowercase() }
    private val byNormalized = entries.groupBy { it.candidate.normalized }
    private val byToken = HashMap<String, MutableList<Entry>>().also { index ->
        for (e in entries) for (t in e.tokens) index.getOrPut(t) { ArrayList() }.add(e)
    }
    private val commonTokenSize = max(COMMON_TOKEN_MIN, entries.size / COMMON_TOKEN_DIVISOR)

    init {
        for (e in entries) e.weight = e.tokens.sumOf(::weight)
    }

    /** Rare words say more: a call sign found in one guide channel outweighs "tv" found in thousands. */
    private fun weight(token: String) = ln(1.0 + entries.size.toDouble() / (byToken[token]?.size ?: 1))

    /** A US or Canadian station's call sign ("wnbc", "kabc", "cfto"): it names one station, wherever it is in the name. */
    private fun isCallSign(token: String) =
        callSign.matches(token) && token !in NOT_CALL_SIGNS && (byToken[token]?.size ?: 0) <= CALL_SIGN_MAX_GUIDES

    /** Best automatic match, or null when nothing reaches [threshold]. */
    fun autoMatch(channelName: String, providerEpgId: String?, threshold: Double = AUTO_THRESHOLD): EpgMatch? =
        suggestions(channelName, providerEpgId, limit = 1).firstOrNull()?.takeIf { it.score >= threshold }

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
        val weights = tokens.associateWith(::weight)
        val own = weights.values.sum()
        val shifts = tokens.shifts()
        val numbers = tokens.numbers()
        // Words like "tv" or "sport" appear in thousands of guide channels; only use them if nothing rarer matches.
        val buckets = tokens.mapNotNull { t -> byToken[t]?.let { t to it } }
        val useful = buckets.filter { it.second.size <= commonTokenSize }.ifEmpty { buckets }
        val shared = HashMap<Entry, Double>()
        for ((t, bucket) in useful) for (e in bucket) shared.merge(e, weights.getValue(t), Double::plus)

        // Rank by the weight of the rarer shared words, then score the best few on all their words.
        shared.entries
            .sortedByDescending { (e, w) -> w / (own + e.weight) }
            .take(FUZZY_CANDIDATES)
            .forEach { (e, _) ->
                if (e.candidate in results) return@forEach
                val common = e.tokens.filter { it in tokens }
                val overlap = 2 * common.sumOf { weights.getValue(it) } / (own + e.weight)
                var score = 0.6 * overlap + 0.4 * jaroWinkler(norm, e.candidate.normalized)
                // "Sports 1" is not "Sports 10"; a number on one side only ("NBC 4" vs "NBC") is a smaller doubt.
                val oneSided = numbers.isEmpty() || e.numbers.isEmpty()
                val sameStation = shifts == e.shifts && common.any(::isCallSign)
                if (shifts != e.shifts) score *= 0.5 // "+1" is an hour later
                if (numbers != e.numbers) {
                    score *= if (!oneSided) {
                        0.6
                    } else if (sameStation) {
                        1.0
                    } else {
                        0.85
                    }
                }
                if (sameStation && (oneSided || numbers == e.numbers)) score = max(score, CALL_SIGN_SCORE)
                if (score >= MIN_SUGGESTION) results[e.candidate] = score
            }
    }

    companion object {
        /** Matching after a guide or playlist is first loaded. */
        const val AUTO_THRESHOLD = 0.85

        /** "Match channels without a guide": only near-certain matches. */
        const val MISSING_THRESHOLD = 0.90
        private const val EXACT_NAME_SCORE = 0.97
        private const val CALL_SIGN_SCORE = 0.96
        private const val CALL_SIGN_MAX_GUIDES = 3
        private const val MIN_SUGGESTION = 0.4
        private const val FUZZY_CANDIDATES = 40
        private const val COMMON_TOKEN_MIN = 200
        private const val COMMON_TOKEN_DIVISOR = 20

        private val callSign = Regex("[kw][a-z]{2,3}|c[bfhijk][a-z]{2}")

        /** Words shaped like call signs. */
        private val NOT_CALL_SIGNS = setOf(
            "kids", "kino", "kiss", "king", "kick", "keep", "know", "west", "wild", "word", "work", "wine", "wave", "wiki",
            "wwe", "web", "war", "way", "wow", "win", "wok", "key", "kix", "wind", "wing", "wise", "wolf", "wood", "wars",
            "chef", "chic", "city", "chat", "cine", "cbbc"
        )

        private fun Set<String>.shifts() = filterTo(HashSet()) { it.startsWith("plus") }
        private fun Set<String>.numbers() = filterTo(HashSet()) { t -> !t.startsWith("plus") && t.any(Char::isDigit) }

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
