package com.biskyt.myplexplayer.core.epg

import java.text.Normalizer
import java.util.Locale

/**
 * Guesses which language/country group a provider category belongs to from its name, e.g.
 * `UK | SPORTS`, `FR: CINEMA`, `|EN| NEWS`, `DE - KIDS`, `[AR] MBC`. Xtream has no language field,
 * so this is a best guess the user can override per category. Returns a group code such as `UK`, `FR`, or null.
 */
object CategoryLanguageDetector {
    /**
     * The group codes with a label of their own (the app names them, e.g. UK → "English (UK)"). Codes are what
     * providers put in front of category names.
     */
    val groups: Set<String> = linkedSetOf(
        "UK", "US", "CA", "AU", "IE", "FR", "DE", "ES", "IT", "PT", "BR", "NL", "BE", "PL", "TR", "AR", "IN", "PK", "GR",
        "RO", "AL", "EXYU", "SE", "NO", "DK", "FI", "RU", "LAT", "MX", "AF", "CN", "KR", "JP"
    )

    private val aliases = mapOf(
        "GB" to "UK", "EN" to "UK", "ENG" to "UK", "USA" to "US", "FRA" to "FR", "GER" to "DE", "ESP" to "ES",
        "SP" to "ES", "ITA" to "IT", "POR" to "PT", "NLD" to "NL", "HOL" to "NL", "TUR" to "TR", "ARA" to "AR",
        "ARAB" to "AR", "IND" to "IN", "HINDI" to "IN", "YU" to "EXYU", "SWE" to "SE", "NOR" to "NO", "DEN" to "DK",
        "LATINO" to "LAT", "LATAM" to "LAT", "AFR" to "AF", "AFRICA" to "AF"
    )

    // A short code at the start, wrapped or followed by a separator: "UK |", "|UK|", "[UK]", "UK:", "UK -", "UK/".
    private val leading = Regex("""^\W*([A-Za-z]{2,6})\s*[|:\-\]/)]""")

    private val nonLetters = Regex("[^\\p{L}]+")
    private val marks = Regex("\\p{M}+")

    /** "Curaçao" → "Curacao". */
    private fun stripAccents(text: String): String = marks.replace(Normalizer.normalize(text, Normalizer.Form.NFKD), "")

    /**
     * " WORD WORD ": upper-case letters only, without accents, "&" as "and", single spaces, padded so whole words can
     * be found with contains(). So "Curaçao" and "Bosnia & Herzegovina" match iptv-org's "Curacao" and "... and ...".
     */
    private fun padded(text: String) = " " + stripAccents(text).replace("&", " and ").uppercase().replace(nonLetters, " ").trim() + " "

    /** The group for an ISO country code: "UK" for Great Britain, as providers write it. */
    private fun groupForCountry(iso: String): String = iso.uppercase().let { if (it == "GB") "UK" else it }

    // Whole words anywhere in the name: every country's English name (iptv-org groups channels by country), a few
    // alternative names, and language adjectives. Longest first, so the most specific name wins.
    private val words: List<Pair<String, String>> = (
        Locale.getISOCountries().flatMap { iso ->
            // Both the full name and the everyday one: "Myanmar (Burma)" → "Myanmar", "Hong Kong SAR China" → "Hong Kong".
            val name = Locale("", iso).getDisplayCountry(Locale.ENGLISH)
            val short = name.substringBefore(" (").substringBefore(" - ").removeSuffix(" SAR China")
            val code = groupForCountry(iso)
            listOf(name to code, short to code)
        } +
            listOf(
                "Turkey" to "TR", "Turkiye" to "TR", "Czech Republic" to "CZ", "Russia" to "RU",
                // iptv-org's names, where they differ from Java's.
                "Republic of the Congo" to "CG", "Democratic Republic of the Congo" to "CD", "English" to "UK", "French" to "FR",
                "German" to "DE", "Spanish" to "ES", "Italian" to "IT", "Dutch" to "NL", "Polish" to "PL",
                "Turkish" to "TR", "Arabic" to "AR", "Greek" to "GR",
            )
        ).map { (name, code) -> padded(name) to code }.filter { it.first.isNotBlank() }.sortedByDescending { it.first.length }

    /** Whether [code] is a language/country code providers put in front of names ("EN", "UK", "FRA"…). */
    fun isLanguageCode(code: String): Boolean = code.uppercase().let { it in aliases || it in groups }

    fun detect(categoryName: String): String? {
        val m = leading.find(categoryName)
        if (m != null) {
            val code = m.groupValues[1].uppercase()
            (aliases[code] ?: code.takeIf { it in groups })?.let { return it }
        }
        val name = padded(categoryName)
        return words.firstOrNull { name.contains(it.first) }?.second
    }
}
