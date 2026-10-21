package com.biskyt.mytvplayer.data

import com.biskyt.mytvplayer.core.epg.CategoryLanguageDetector
import com.biskyt.mytvplayer.core.m3u.M3uEntry
import com.biskyt.mytvplayer.core.xtream.XtreamCategory
import java.util.concurrent.ConcurrentHashMap

/**
 * M3U categories: an entry's group-title (or "Other"), filed under the group its tvg-country or tvg-language gives
 * when the name itself names none, so "News" splits by country while "UK | News" stays as it is. The group follows a
 * separator no playlist uses, so the ids differ by group while the names stay the playlist's own.
 */
object M3uCategories {
    private const val TAG = '\u001F'
    private const val OTHER = "Other"

    private data class Key(val name: String, val country: String?, val language: String?)

    // A playlist has a few hundred combinations at most: each is worked out once, and its id reused.
    private val ids = ConcurrentHashMap<Key, String>()

    /** The id of [e]'s category. */
    fun idOf(e: M3uEntry): String {
        val name = e.group ?: OTHER
        if (e.country == null && e.language == null) return name
        return ids.getOrPut(Key(name, e.country, e.language)) {
            val group = CategoryLanguageDetector.groupFor(e.country, e.language)
            if (group == null || CategoryLanguageDetector.detect(name) != null) name else "$name$TAG$group"
        }
    }

    /** The group an id was filed under, or null. */
    fun groupOf(id: String): String? = id.substringAfter(TAG, "").ifEmpty { null }

    /** A category from its id, named as the playlist names it. */
    fun category(id: String) = XtreamCategory(id, id.substringBefore(TAG))
}
