package com.biskyt.mytvplayer.data

import androidx.room.withTransaction
import com.biskyt.mytvplayer.core.epg.CategoryLanguageDetector
import com.biskyt.mytvplayer.core.xtream.XtreamCategory
import com.biskyt.mytvplayer.data.db.AppDatabase
import com.biskyt.mytvplayer.data.db.CAT_LIVE
import com.biskyt.mytvplayer.data.db.CategoryEntity
import java.util.Locale

/**
 * Provider categories of every type (live, movie, series): which are included, and which items to download because
 * of that. New categories are included when their language/country group is one the user already includes, or, the
 * first time, the TV's own country/language.
 */
class CategoryStore(private val db: AppDatabase, private val locale: () -> Locale = Locale::getDefault) {
    private val categories = db.categories()

    /**
     * A category's language/country group. [groupByName]: M3U playlists also group by genre ("News"); when no
     * language is detected the group's own name is used, so each still appears in the language list.
     */
    fun langOf(name: String, groupByName: Boolean) =
        // Two-letter names ("TV", "SD") would read as country codes, so those go under "Other".
        CategoryLanguageDetector.detect(name) ?: name.takeIf { groupByName && it.length > 2 }

    /**
     * Saves the provider's categories of one [type], keeping the user's choices for ones already known.
     *
     * Movie and series categories are often named without a language ("Action", "Netflix Movies"); those are taken to
     * be in the provider's main language and included too. If none of a movie or series list is included yet (as
     * after updating from a version that didn't do this), its defaults are applied again.
     */
    suspend fun store(playlistId: Long, type: String, remote: List<XtreamCategory>, groupByName: Boolean = false) {
        val vod = type != CAT_LIVE
        val known = categories.all(playlistId, type).associateBy { it.categoryId }
        val existing = if (vod && known.values.none { it.included }) emptyMap() else known
        val langs = remote.map { langOf(it.name, groupByName) }
        val wanted = if (existing.isEmpty()) {
            defaultGroups(
                langs.filterNotNullTo(HashSet())
            )
        } else {
            includedLangs(existing.values, groupByName)
        }
        val items = remote.mapIndexed { i, c ->
            val old = existing[c.id]
            CategoryEntity(
                playlistId = playlistId,
                categoryId = c.id,
                name = c.name,
                lang = langs[i],
                included = old?.included ?: (langs[i] in wanted || (vod && langs[i] == null)),
                sortOrder = old?.sortOrder ?: (existing.size + i),
                channelCount = old?.channelCount ?: 0,
                type = type
            )
        }
        db.withTransaction {
            categories.upsert(items)
            if (items.isNotEmpty()) categories.deleteOthers(playlistId, type, items.map { it.categoryId })
        }
    }

    /**
     * Groups the user includes: a new category in one of these is included automatically. Worked out from the names
     * rather than the stored groups, so it still matches after an update improves language detection.
     */
    fun includedLangs(existing: Collection<CategoryEntity>, groupByName: Boolean) =
        existing.filter { it.included }.mapNotNullTo(HashSet()) { langOf(it.name, groupByName) }

    /**
     * Language groups included by default: the TV's own country (e.g. en-GB → UK) when the provider has it,
     * otherwise every group of the TV's language (e.g. en-US with a UK-only provider → UK). The user can change this.
     */
    fun defaultGroups(available: Set<String>): Set<String> {
        val l = locale()
        val country = CategoryLanguageDetector.groupForCountry(l.country)
        if (country in available) return setOf(country)
        return languageGroups[l.language].orEmpty().intersect(available)
    }

    /**
     * Reads the items of included categories of [type]: with just a few included, one request per category;
     * otherwise the whole list once, dropping the rest as it streams in.
     */
    suspend fun <T> forIncluded(
        playlistId: Long,
        type: String,
        categoryOf: (T) -> String,
        fetch: suspend (categoryId: String?, emit: suspend (T) -> Unit) -> Unit,
        onItem: suspend (T) -> Unit
    ) {
        val all = categories.all(playlistId, type)
        val included = all.filter { it.included }.mapTo(HashSet()) { it.categoryId }
        if (included.isEmpty()) return
        // Items are checked in both cases: some panels ignore the category asked for and send everything.
        val keep: suspend (T) -> Unit = { item -> if (categoryOf(item) in included) onItem(item) }
        if (included.size <= PER_CATEGORY_LIMIT && included.size * 2 < all.size) {
            for (cat in included) fetch(cat, keep)
        } else {
            fetch(null, keep)
        }
    }

    private companion object {
        const val PER_CATEGORY_LIMIT = 20

        /** Category groups that share a language, for the default selection. */
        val languageGroups = mapOf(
            "en" to setOf("UK", "US", "CA", "AU", "IE"), "fr" to setOf("FR", "BE"), "de" to setOf("DE"),
            "es" to setOf("ES", "MX", "LAT"), "it" to setOf("IT"), "pt" to setOf("PT", "BR"), "nl" to setOf("NL", "BE"),
            "pl" to setOf("PL"), "tr" to setOf("TR"), "ar" to setOf("AR"), "el" to setOf("GR"), "ro" to setOf("RO"),
            "sv" to setOf("SE"), "nb" to setOf("NO"), "no" to setOf("NO"), "da" to setOf("DK"), "fi" to setOf("FI"),
            "ru" to setOf("RU"), "hi" to setOf("IN"), "sq" to setOf("AL"), "zh" to setOf("CN"), "ko" to setOf("KR"), "ja" to setOf("JP"),
        )
    }
}

/** Writes a long list in batches as it streams in, so it never sits in memory whole. [count] is the total added. */
class Batch<E>(private val size: Int, private val write: suspend (List<E>) -> Unit, private val onFlush: (Int) -> Unit = {}) {
    private val items = ArrayList<E>(size)
    var count = 0
        private set

    suspend fun add(item: E) {
        items += item
        count++
        if (items.size >= size) flush()
    }

    suspend fun flush() {
        if (items.isEmpty()) return
        write(items.toList())
        items.clear()
        onFlush(count)
    }
}
