package com.biskyt.myplexplayer.data

import android.util.LruCache
import com.biskyt.myplexplayer.R
import com.biskyt.myplexplayer.appString
import com.biskyt.myplexplayer.core.plex.PersonCredit
import com.biskyt.myplexplayer.core.plex.PlexCreditGroup
import com.biskyt.myplexplayer.core.plex.PlexItem
import com.biskyt.myplexplayer.core.plex.PlexPerson
import com.biskyt.myplexplayer.core.plex.alsoAppearsIn
import com.biskyt.myplexplayer.core.plex.kindsOfWork
import com.biskyt.myplexplayer.core.plex.personCredits
import com.biskyt.myplexplayer.core.plex.plexGuidId
import com.biskyt.myplexplayer.data.db.AppDatabase
import java.io.IOException
import java.time.LocalDate
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/** The guid of a film or show, or of an episode's show. */
private val PlexItem.showGuid: String? get() = if (type == "episode") grandparentGuid else guid

/**
 * A cast or crew member's page: who they are, the titles of theirs on this server, and their other credits. [kinds] are
 * their kinds of work, most credits first ([PlexCreditGroup.ACTOR] and others). Any part can be missing.
 */
data class PersonPage(
    val name: String,
    val photoUrl: String?,
    val kinds: List<String>,
    val born: LocalDate?,
    val birthPlace: String?,
    val died: LocalDate?,
    val biography: String?,
    val library: List<LibraryTitle>,
    val elsewhere: List<PersonCredit>,
    val credits: List<PersonCredit>,
)

/**
 * A title of the person's that is on the server and in the app: a film, or a [show] (by its [id], its page's). [role] is
 * their part in it, else their job ([kind]). Both are null when they are not known.
 */
data class LibraryTitle(
    val id: Long,
    val show: Boolean,
    val title: String,
    val year: Int?,
    val posterUrl: String?,
    val role: String?,
    val kind: String?,
)

/**
 * People's pages. The server gives the titles that it has of theirs. Plex's Discover service (with the account's
 * token), who they are and their credits (docs/plex-api.md 29). Nothing is saved: Discover's answers are kept in memory
 * for the last few people.
 */
class PersonRepository(private val db: AppDatabase, private val plex: PlexSources) {
    /** What Discover gave for a person: [person] null or [groups] null when that part could not be read. */
    private class Discovered(val person: PlexPerson?, val groups: List<PlexCreditGroup>?)

    private val discovered = LruCache<String, Discovered>(KEPT)

    /**
     * The page of person [personId] (their id on server [playlistId]) and [tagKey] (their id on Discover). If [tagKey]
     * is null, the page has only the server's part. The page leaves out each part that it cannot read. If it can read
     * no part, this fails.
     */
    suspend fun page(playlistId: Long, personId: Long, tagKey: String?, name: String): PersonPage = coroutineScope {
        val p = db.playlists().get(playlistId) ?: throw IOException(appString(R.string.vod_person_failed))
        val client = plex.client(p)
        val media = async { offlineOk { client.personMedia(personId) } }
        val found = async { tagKey?.let { discover(it) } }
        val items = media.await()
        val d = found.await()
        val person = d?.person
        if (items == null && person == null && d?.groups == null) throw IOException(appString(R.string.vod_person_failed))
        val credits = personCredits(d?.groups.orEmpty()).map { it.copy(thumb = plex.image(client, it.thumb)) }
        // An episode's show is the title that Discover knows.
        val onServer = items.orEmpty().mapNotNullTo(HashSet()) { plexGuidId(it.showGuid) }
        PersonPage(
            name = person?.title?.ifBlank { null } ?: name,
            photoUrl = person?.thumb?.let { plex.image(client, it, PHOTO, PHOTO) },
            kinds = kindsOfWork(person?.creditTypes.orEmpty()),
            born = airDateOf(person?.bornAt),
            birthPlace = person?.birthPlace?.ifBlank { null },
            died = airDateOf(person?.diedAt),
            biography = person?.summary?.trim()?.ifEmpty { null },
            library = library(playlistId, personId, items.orEmpty(), credits),
            elsewhere = alsoAppearsIn(credits, onServer),
            credits = credits,
        )
    }

    /**
     * Discover's person and credits for [tagKey]. The two parts are read at the same time, and each one that fails is
     * null. The answers are kept only when both parts were read.
     */
    private suspend fun discover(tagKey: String): Discovered = discovered.get(tagKey) ?: coroutineScope {
        val person = async { offlineOk { plex.person(tagKey) } }
        val groups = async { offlineOk { plex.credits(tagKey) } }
        Discovered(person.await(), groups.await()).also { if (it.person != null && it.groups != null) discovered.put(tagKey, it) }
    }

    /**
     * Of the server's titles of the person ([items]), those the app has (synced, so shown in its lists), newest first,
     * each once: an episode counts as its show. The person's part comes from their [credits], matched by guid, else
     * from the title's own cast.
     */
    private suspend fun library(playlistId: Long, personId: Long, items: List<PlexItem>, credits: List<PersonCredit>): List<LibraryTitle> {
        val vod = db.vod()
        return items.mapNotNull { item ->
            val credit = plexGuidId(item.showGuid)?.let { id -> credits.firstOrNull { it.ratingKey == id } }
            val role = credit?.role ?: item.roles.firstOrNull { it.id == personId }?.role?.ifBlank { null }
            when (item.type) {
                "movie" -> item.ratingKey.toLongOrNull()?.let { vod.movie(playlistId, it) }
                    ?.let { LibraryTitle(it.streamId, false, it.title, it.year, it.posterUrl, role, credit?.kind) }
                "show", "episode" -> (if (item.type == "show") item.ratingKey else item.grandparentRatingKey)?.toLongOrNull()
                    ?.let { vod.series(playlistId, it) }
                    ?.let { LibraryTitle(it.seriesId, true, it.title, it.year, it.coverUrl, role, credit?.kind) }
                else -> null
            }
        }.distinctBy { it.show to it.id }.sortedByDescending { it.year ?: 0 }
    }

    private companion object {
        /** People whose Discover answers are kept. */
        const val KEPT = 16

        /** The person's photo in pixels: 200 on the page, on a 1080p screen. */
        const val PHOTO = 300
    }
}
