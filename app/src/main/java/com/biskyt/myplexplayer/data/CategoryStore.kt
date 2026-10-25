package com.biskyt.myplexplayer.data

import androidx.room.withTransaction
import com.biskyt.myplexplayer.core.epg.CategoryLanguageDetector
import com.biskyt.myplexplayer.core.xtream.XtreamCategory
import com.biskyt.myplexplayer.data.db.AppDatabase
import com.biskyt.myplexplayer.data.db.CAT_LIVE
import com.biskyt.myplexplayer.data.db.CategoryEntity
import com.biskyt.myplexplayer.data.db.MAX_SQL_ARGS
import java.util.Locale

/**
 * Provider categories of every type (live, movie, series): which are included, and which items to download because
 * of that. New categories are included when their language/country group is one the user already includes, or, the
 * first time, the TV's own country/language.
 */
class CategoryStore(private val db: AppDatabase, private val locale: () -> Locale = Locale::getDefault) {
    private val categories = db.categories()

    /** A category's language/country group, as its name says; null when it names none. */
    private fun langOf(name: String): String? = CategoryLanguageDetector.detect(name)

    /**
     * Saves the provider's categories of one [type], keeping the user's choices for ones already known.
     *
     * Movie and series categories are often named without a language ("Action", "Netflix Movies"); those are taken to
     * be in the provider's main language and included too. A movie or series list the user unticked entirely is off:
     * new categories in it stay unticked too.
     *
     * [libraries]: a Plex server's libraries. Each new one is included whatever its name (a server is one person's
     * own choice of films), and the list is complete: an empty one means the libraries have all gone.
     */
    suspend fun store(playlistId: Long, type: String, remote: List<XtreamCategory>, libraries: Boolean = false) {
        val vod = type != CAT_LIVE
        val existing = categories.all(playlistId, type).associateBy { it.categoryId }
        val switchedOff = vod && existing.isNotEmpty() && existing.values.none { it.included }
        val langs = remote.map { langOf(it.name) }
        val isNew: (Int) -> Boolean = if (existing.isEmpty()) {
            val defaults = defaultGroups(langs.filterNotNullTo(HashSet()))

            // Nothing matches the TV's country or language (e.g. a playlist of genres, "Live: News", with no
            // countries at all): what names no country is chosen, so there's something to watch.
            fun(i: Int) = langs[i] in defaults || (defaults.isEmpty() && langs[i] == null)
        } else {
            val includes = includesNew(existing.values)
            fun(i: Int) = includes(remote[i])
        }
        val remoteIds = remote.mapTo(HashSet()) { it.id }
        val items = remote.mapIndexed { i, c ->
            val old = existing[c.id]
            CategoryEntity(
                playlistId = playlistId,
                categoryId = c.id,
                name = c.name,
                lang = langs[i],
                included = old?.included ?: includedAtFirst(libraries, switchedOff, isNew(i), vod && langs[i] == null),
                sortOrder = old?.sortOrder ?: (existing.size + i),
                channelCount = old?.channelCount ?: 0,
                type = type
            )
        }
        // Those the provider no longer has go.
        val gone = if (remote.isEmpty() && !libraries) emptyList() else existing.keys.filter { it !in remoteIds }
        db.withTransaction {
            categories.upsert(items)
            gone.chunked(MAX_SQL_ARGS).forEach { categories.delete(playlistId, type, it) }
        }
    }

    /**
     * Whether a category seen for the first time is included: every Plex library; else, unless the user switched the
     * list off, one in a group they include ([isNew]) or a movie or series one that names no language ([unnamed]).
     */
    private fun includedAtFirst(libraries: Boolean, switchedOff: Boolean, isNew: Boolean, unnamed: Boolean) =
        libraries || (!switchedOff && (isNew || unnamed))

    /**
     * Groups the user includes: a new category in one of these is included automatically. Worked out from the names
     * rather than the stored groups, so it still matches after an update improves language detection.
     */
    private fun includedLangs(existing: Collection<CategoryEntity>) =
        existing.filter { it.included }.mapNotNullTo(HashSet()) { langOf(it.name) }

    /**
     * Whether a category not seen before is included, given the [known] ones: when it's in a group the user includes,
     * or names no country while every known one that names none is included (as everything is to start with in a
     * playlist of genres, so a genre added later is too).
     */
    private fun includesNew(known: Collection<CategoryEntity>): (XtreamCategory) -> Boolean {
        val wanted = includedLangs(known)
        val undetected = known.filter { langOf(it.name) == null }
        val allUndetected = undetected.isNotEmpty() && undetected.all { it.included }
        return { c -> langOf(c.name) in wanted || (allUndetected && langOf(c.name) == null) }
    }

    /**
     * Language groups included by default: the TV's own country (e.g. en-GB → UK) when the provider has it,
     * otherwise every group of the TV's language (e.g. en-US with a UK-only provider → UK). The user can change this.
     */
    fun defaultGroups(available: Set<String>): Set<String> {
        val l = locale()
        val country = CategoryLanguageDetector.groupForCountry(l.country)
        if (country in available) return setOf(country)
        return CategoryLanguageDetector.languageGroups[l.language].orEmpty().intersect(available)
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
