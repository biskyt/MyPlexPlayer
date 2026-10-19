package com.biskyt.mytvplayer.data

import com.biskyt.mytvplayer.core.epg.CategoryLanguageDetector
import com.biskyt.mytvplayer.data.db.EpgSourceEntity
import java.util.Locale

/** A free XMLTV guide for one country. */
data class FreeGuide(val code: String, val country: String, val url: String) {
    val sourceName: String get() = "$country (iptv-epg.org)"
}

/**
 * Free country guides from iptv-epg.org, all named `epg-<country code>.xml.gz`. The list is fixed here rather than
 * read from their site, so it works without scraping a page that may change; new countries need an app update.
 */
object FreeGuides {
    private const val BASE = "https://iptv-epg.org/files/epg-"

    private val codes = (
        "al am ar at au ba be bg bo br bs by ca ch cl co cr cw cz de dk do ec es fi fr gb ge gh gr gt hk hn hr id il in is " +
            "it jm jp kr lt lu lv me mt mx my ng ni nl no nz pa pe pl pt py rs ru se sg sv th tr tt tw ua ug us uy ve za zw"
        ).split(' ')

    private var cached: Pair<Locale, List<FreeGuide>>? = null

    /** Every country, the TV's own first, then by name (kept: the country names take a while to look up). */
    fun all(locale: Locale = Locale.getDefault()): List<FreeGuide> =
        cached?.takeIf { it.first == locale }?.second ?: build(locale).also { cached = locale to it }

    private fun build(locale: Locale): List<FreeGuide> {
        val own = locale.country.lowercase()
        return codes.map { FreeGuide(it, Locale("", it).getDisplayCountry(Locale.ENGLISH).ifEmpty { it.uppercase() }, "$BASE$it.xml.gz") }
            .sortedWith(compareBy<FreeGuide> { it.code != own }.thenBy { it.country })
    }

    /** Every free guide not among [sources] yet, the TV's own country first. */
    fun notAdded(sources: List<EpgSourceEntity>): List<FreeGuide> {
        val added = sources.mapTo(HashSet()) { it.url }
        return all().filter { it.url !in added }
    }

    /**
     * The guides for category groups (country codes as [CategoryLanguageDetector] gives them, "UK" for Great Britain),
     * for the countries that have one.
     */
    fun forGroups(groups: Collection<String?>): List<FreeGuide> {
        val wanted = groups.mapNotNullTo(HashSet()) { it?.let(CategoryLanguageDetector::countryForGroup) }
        return all().filter { it.code in wanted }
    }
}
