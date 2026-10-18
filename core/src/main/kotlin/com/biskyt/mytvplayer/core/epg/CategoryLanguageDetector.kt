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
        "AF" to "African", "CN" to "Chinese", "KR" to "Korean", "JP" to "Japanese"
    )

    private val aliases = mapOf(
        "GB" to "UK", "EN" to "UK", "ENG" to "UK", "USA" to "US", "FRA" to "FR", "GER" to "DE", "ESP" to "ES",
        "SP" to "ES", "ITA" to "IT", "POR" to "PT", "NLD" to "NL", "HOL" to "NL", "TUR" to "TR", "ARA" to "AR",
        "ARAB" to "AR", "IND" to "IN", "HINDI" to "IN", "YU" to "EXYU", "SWE" to "SE", "NOR" to "NO", "DEN" to "DK",
        "LATINO" to "LAT", "LATAM" to "LAT", "AFR" to "AF", "AFRICA" to "AF"
    )

    // Whole words anywhere in the name, including full country names (iptv-org groups channels by country).
    private val words = mapOf(
        "UNITED KINGDOM" to "UK", "ENGLISH" to "UK", "UNITED STATES" to "US", "CANADA" to "CA", "AUSTRALIA" to "AU",
        "IRELAND" to "IE", "FRANCE" to "FR", "FRENCH" to "FR", "GERMANY" to "DE", "GERMAN" to "DE", "SPAIN" to "ES",
        "SPANISH" to "ES", "ITALY" to "IT", "ITALIAN" to "IT", "PORTUGAL" to "PT", "BRAZIL" to "BR",
        "NETHERLANDS" to "NL", "DUTCH" to "NL", "BELGIUM" to "BE", "POLAND" to "PL", "POLISH" to "PL",
        "TURKEY" to "TR", "TURKISH" to "TR", "ARABIC" to "AR", "INDIA" to "IN", "PAKISTAN" to "PK", "GREECE" to "GR",
        "GREEK" to "GR", "ROMANIA" to "RO", "ALBANIA" to "AL", "SWEDEN" to "SE", "NORWAY" to "NO", "DENMARK" to "DK",
        "FINLAND" to "FI", "RUSSIA" to "RU", "MEXICO" to "MX", "CHINA" to "CN", "SOUTH KOREA" to "KR", "JAPAN" to "JP",
    )

    // A short code at the start, wrapped or followed by a separator: "UK |", "|UK|", "[UK]", "UK:", "UK -", "UK/".
    private val leading = Regex("""^\W*([A-Za-z]{2,6})\s*[|:\-\]/)]""")

    private val nonLetters = Regex("[^\\p{L}]+")

    fun detect(categoryName: String): String? {
        val m = leading.find(categoryName)
        if (m != null) {
            val code = m.groupValues[1].uppercase()
            (aliases[code] ?: code.takeIf { it in groups })?.let { return it }
        }
        val upper = " " + categoryName.uppercase().replace(nonLetters, " ") + " "
        return words.entries.firstOrNull { upper.contains(" ${it.key} ") }?.value
    }

    fun label(code: String?): String = code?.let { groups[it] ?: it } ?: "Other"
}
