package com.biskyt.mytvplayer.data

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

    /** Every country, the TV's own first, then by name. */
    fun all(locale: Locale = Locale.getDefault()): List<FreeGuide> {
        val own = locale.country.lowercase()
        return codes.map { FreeGuide(it, Locale("", it).getDisplayCountry(Locale.ENGLISH).ifEmpty { it.uppercase() }, "$BASE$it.xml.gz") }
            .sortedWith(compareBy<FreeGuide> { it.code != own }.thenBy { it.country })
    }
}
