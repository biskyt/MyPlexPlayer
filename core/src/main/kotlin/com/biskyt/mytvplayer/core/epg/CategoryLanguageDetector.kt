package com.biskyt.mytvplayer.core.epg

import com.biskyt.mytvplayer.core.Languages
import java.util.Locale

/**
 * Guesses which language/country group a provider category belongs to from its name, e.g.
 * `UK | SPORTS`, `FR: CINEMA`, `|EN| NEWS`, `DE - KIDS`, `[AR] MBC`. Xtream has no language field,
 * so this is a best guess the user can override per category. Returns a group code such as `UK`, `FR`, or null.
 */
object CategoryLanguageDetector {
    /** Group code → display label. Codes are what providers put in front of category names. */
    val groups: Map<String, String> = linkedMapOf(
        "UK" to "English (UK)", "US" to "English (US)", "CA" to "English (Canada)", "AU" to "English (Australia)",
        "IE" to "English (Ireland)", "FR" to "French", "DE" to "German", "ES" to "Spanish", "IT" to "Italian",
        "PT" to "Portuguese", "BR" to "Portuguese (Brazil)", "NL" to "Dutch", "BE" to "Belgian", "PL" to "Polish",
        "TR" to "Turkish", "AR" to "Arabic", "IN" to "Indian", "PK" to "Pakistani", "GR" to "Greek",
        "RO" to "Romanian", "AL" to "Albanian", "EXYU" to "Balkan", "SE" to "Swedish", "NO" to "Norwegian",
        "DK" to "Danish", "FI" to "Finnish", "RU" to "Russian", "LAT" to "Latino", "MX" to "Mexican",
        "AF" to "African", "CN" to "Chinese", "KR" to "Korean", "JP" to "Japanese"
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

    /**
     * " WORD WORD ": upper-case letters only, without accents, "&" as "and", single spaces, padded so whole words can
     * be found with contains(). So "Curaçao" and "Bosnia & Herzegovina" match iptv-org's "Curacao" and "... and ...".
     */
    private fun padded(text: String) =
        " " + ChannelNames.stripAccents(text).replace("&", " and ").uppercase().replace(nonLetters, " ").trim() + " "

    /** The group for an ISO country code: "UK" for Great Britain, as providers write it. */
    fun groupForCountry(iso: String): String = iso.uppercase().let { if (it == "GB") "UK" else it }

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

    /** Category groups that share a language, the first being where it's mostly watched (for defaults, and below). */
    val languageGroups: Map<String, List<String>> = mapOf(
        "en" to listOf("UK", "US", "CA", "AU", "IE"), "fr" to listOf("FR", "BE"), "de" to listOf("DE"),
        "es" to listOf("ES", "MX", "LAT"), "it" to listOf("IT"), "pt" to listOf("PT", "BR"), "nl" to listOf("NL", "BE"),
        "pl" to listOf("PL"), "tr" to listOf("TR"), "ar" to listOf("AR"), "el" to listOf("GR"), "ro" to listOf("RO"),
        "sv" to listOf("SE"), "nb" to listOf("NO"), "no" to listOf("NO"), "da" to listOf("DK"), "fi" to listOf("FI"),
        "ru" to listOf("RU"), "hi" to listOf("IN"), "sq" to listOf("AL"), "zh" to listOf("CN"), "ko" to listOf("KR"), "ja" to listOf("JP"),
    )

    private val isoCountries = Locale.getISOCountries().toHashSet()

    /**
     * The group for a playlist entry's `tvg-country` (an ISO code, or one providers use: "UK", "USA", "LAT"…), or else
     * its `tvg-language` (an English name or an ISO 639 code): the country's own group, or the language's main one
     * ("English" → UK); for other languages, the language's English name. Null when neither says anything usable.
     */
    fun groupFor(country: String?, language: String?): String? {
        val c = country?.uppercase()?.let { aliases[it] ?: it }
        if (c != null && (c in groups || c in isoCountries)) return groupForCountry(c)
        val code = language?.let(Languages::code) ?: return null
        return languageGroups[code]?.first() ?: Locale(code).getDisplayLanguage(Locale.ENGLISH).ifEmpty { null }
    }

    fun label(code: String?): String = when {
        code == null -> "Other"
        code in groups -> groups.getValue(code)
        code.length == 2 -> Locale("", code).getDisplayCountry(Locale.ENGLISH).ifEmpty { code }
        else -> code
    }
}
