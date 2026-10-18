package com.biskyt.mytvplayer.core.epg

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
        "AF" to "African", "CN" to "Chinese", "KR" to "Korean", "JP" to "Japanese",
    )

    private val aliases = mapOf(
        "GB" to "UK", "EN" to "UK", "ENG" to "UK", "USA" to "US", "FRA" to "FR", "GER" to "DE", "ESP" to "ES",
        "SP" to "ES", "ITA" to "IT", "POR" to "PT", "NLD" to "NL", "HOL" to "NL", "TUR" to "TR", "ARA" to "AR",
        "ARAB" to "AR", "IND" to "IN", "HINDI" to "IN", "YU" to "EXYU", "SWE" to "SE", "NOR" to "NO", "DEN" to "DK",
        "LATINO" to "LAT", "LATAM" to "LAT", "AFR" to "AF", "AFRICA" to "AF",
    )

    private val words = mapOf(
        "ENGLISH" to "UK", "FRENCH" to "FR", "GERMAN" to "DE", "SPANISH" to "ES", "ITALIAN" to "IT", "ARABIC" to "AR",
        "TURKISH" to "TR", "POLISH" to "PL", "PORTUGAL" to "PT", "BRAZIL" to "BR", "GREEK" to "GR", "DUTCH" to "NL",
    )

    // A short code at the start, wrapped or followed by a separator: "UK |", "|UK|", "[UK]", "UK:", "UK -", "UK/".
    private val leading = Regex("""^\W*([A-Za-z]{2,6})\s*[|:\-\]/)]""")

    fun detect(categoryName: String): String? {
        val m = leading.find(categoryName)
        if (m != null) {
            val code = m.groupValues[1].uppercase()
            (aliases[code] ?: code.takeIf { it in groups })?.let { return it }
        }
        val upper = categoryName.uppercase()
        return words.entries.firstOrNull { upper.contains(it.key) }?.value
    }

    fun label(code: String?): String = code?.let { groups[it] ?: it } ?: "Other"
}
