package com.biskyt.myplexplayer.core.plex

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// A cast or crew member's page: the person and their credits on Plex's Discover service, and which of those titles are
// on the server. Proven on the owner's server (S13, docs/plex-api.md 29).

/**
 * A person on Discover. Any field can be missing: [summary] (the biography) is often missing or empty, and a writer
 * can have no [bornAt] or [birthPlace]. [diedAt] was not seen in S13, so nothing depends on it.
 */
@Serializable
data class PlexPerson(
    val title: String = "",
    val thumb: String? = null,
    val summary: String? = null,
    /** "1971-02-01". */
    val bornAt: String? = null,
    val birthPlace: String? = null,
    val diedAt: String? = null,
    /** How many credits of each kind the person has. */
    @SerialName("CreditType") @Serializable(with = SkippingList::class) val creditTypes: List<PlexCreditType> = emptyList(),
)

/** How many credits of one kind ([type]: [PlexCreditGroup.ACTOR] and others) a person has. */
@Serializable
data class PlexCreditType(val type: String = "", val count: Int = 0)

/** A person's credits of one kind: [type] is actor, director, writer, producer, appeared or other. */
@Serializable
data class PlexCreditGroup(
    val type: String = "",
    @SerialName("Credit") @Serializable(with = SkippingList::class) val credits: List<PlexCredit> = emptyList(),
) {
    companion object {
        const val ACTOR = "actor"
        const val DIRECTOR = "director"
        const val WRITER = "writer"
        const val PRODUCER = "producer"
    }
}

/** One credit: the part played or the job ([role], often missing for crew), in [title]. */
@Serializable
data class PlexCredit(val role: String? = null, @SerialName("Metadata") val title: PlexCreditTitle? = null)

/**
 * The film or show of a credit. Its [ratingKey] is the id at the end of a server title's guid
 * (`plex://movie/{ratingKey}`), so [plexGuidId] finds it on the server.
 */
@Serializable
data class PlexCreditTitle(
    val ratingKey: String = "",
    val title: String = "",
    /** "movie" or "show". */
    val type: String = "",
    val year: Int? = null,
    /** A full address, on Plex's or another picture service. */
    val thumb: String? = null,
    /** "2026-10-09T00:00:00.000Z". */
    val originallyAvailableAt: String? = null,
)

@Serializable
internal class PlexPersonResponse(@SerialName("MediaContainer") val container: PlexPersonContainer = PlexPersonContainer())

@Serializable
internal class PlexPersonContainer(
    @SerialName("Metadata") @Serializable(with = SkippingList::class) val people: List<PlexPerson> = emptyList()
)

@Serializable
internal class PlexCreditsResponse(@SerialName("MediaContainer") val container: PlexCreditsContainer = PlexCreditsContainer())

@Serializable
internal class PlexCreditsContainer(
    @SerialName("CreditGroup") @Serializable(with = SkippingList::class) val groups: List<PlexCreditGroup> = emptyList(),
)

/**
 * One credit as a person's page lists it: [kind] is its group's type (who acted, directed, wrote or produced), [role]
 * the part or job when Discover names one (else the page names it by [kind]), and [show] a TV show rather than a film.
 */
data class PersonCredit(
    val ratingKey: String,
    val title: String,
    val show: Boolean,
    val year: Int?,
    val thumb: String?,
    val role: String?,
    val kind: String,
    /** What the credits are sorted by, newest first: the date, else the year. */
    internal val date: String,
)

/** The kinds of credit that "All credits" lists, in this order. "appeared" (as themselves) and "other" are left out. */
val ALL_CREDIT_KINDS = listOf(PlexCreditGroup.ACTOR, PlexCreditGroup.DIRECTOR, PlexCreditGroup.WRITER, PlexCreditGroup.PRODUCER)

/** The kinds of credit that "Also appears in" shows. */
val ELSEWHERE_KINDS = listOf(PlexCreditGroup.ACTOR, PlexCreditGroup.DIRECTOR, PlexCreditGroup.WRITER)

/** The id at the end of a Plex guid ("plex://movie/5d776…" gives "5d776…"). Null for another kind of guid. */
fun plexGuidId(guid: String?): String? = guid?.takeIf { it.startsWith(PLEX_GUID) }?.substringAfterLast('/')?.ifEmpty { null }

/**
 * The credits of [groups] of [kinds], newest first (those with no date or year last), each title once for each kind.
 * Credits of a kind come in [kinds]' order where their dates are the same.
 */
fun personCredits(groups: List<PlexCreditGroup>, kinds: List<String> = ALL_CREDIT_KINDS): List<PersonCredit> = kinds.flatMap { kind ->
    groups.filter { it.type == kind }.flatMap { group ->
        group.credits.mapNotNull { credit ->
            val t = credit.title?.takeIf { it.ratingKey.isNotEmpty() && it.title.isNotBlank() } ?: return@mapNotNull null
            PersonCredit(
                ratingKey = t.ratingKey,
                title = t.title,
                show = t.type == "show",
                year = t.year,
                thumb = t.thumb,
                role = credit.role?.takeIf { it.isNotBlank() },
                kind = kind,
                date = t.originallyAvailableAt?.take(DATE_LENGTH) ?: t.year?.toString().orEmpty(),
            )
        }
    }
}.distinctBy { it.kind to it.ratingKey }.sortedByDescending { it.date }

/**
 * "Also appears in": of a person's [credits] (as [personCredits] gives them), the titles they acted in, directed or
 * wrote that are not on the server (their ids not in [onServer], see [plexGuidId]), newest first, each once, [max] at
 * most.
 */
fun alsoAppearsIn(credits: List<PersonCredit>, onServer: Set<String>, max: Int = ELSEWHERE_MAX): List<PersonCredit> =
    credits.filter { it.kind in ELSEWHERE_KINDS && it.ratingKey !in onServer }.distinctBy { it.ratingKey }.take(max)

/** A person's kinds of work (actor, director…), most credits first, [max] at most: the ones "All credits" lists. */
fun kindsOfWork(types: List<PlexCreditType>, max: Int = KINDS_MAX): List<String> =
    types.filter { it.type in ALL_CREDIT_KINDS && it.count > 0 }.sortedByDescending { it.count }.map { it.type }.distinct().take(max)

private const val PLEX_GUID = "plex://"
private const val DATE_LENGTH = 10
private const val ELSEWHERE_MAX = 20
private const val KINDS_MAX = 2
