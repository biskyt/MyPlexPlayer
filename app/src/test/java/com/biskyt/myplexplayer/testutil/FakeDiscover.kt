package com.biskyt.myplexplayer.testutil

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * A cast or crew member for [FakePlex.people]: their ids on the server ([id]) and on Plex's Discover service
 * ([tagKey]), what Discover knows of them ([summary], birth, [creditTypes] by kind, and [credits] by kind), and the
 * server's titles of theirs ([media], by ratingKey).
 */
data class FakePerson(
    val id: Long,
    val tagKey: String,
    val name: String,
    val summary: String? = null,
    val bornAt: String? = null,
    val birthPlace: String? = null,
    val creditTypes: Map<String, Int> = emptyMap(),
    val credits: Map<String, List<FakeCredit>> = emptyMap(),
    val media: List<String> = emptyList(),
)

/**
 * A credit on Discover: a title known there by [ratingKey] (a server title's is the end of its guid, see
 * [FakePlex.guidId]), a show or a film of [year], and the [role] played.
 */
data class FakeCredit(val ratingKey: String, val title: String, val show: Boolean = false, val year: Int? = null, val role: String? = null)

/**
 * Discover's reply to [path]: a person (`/library/people/{tagKey}`) or their credits (`…/credits`) among [people], in
 * the shapes S13 found (docs/plex-api.md 29); null for someone it does not know.
 */
internal fun discover(path: String, people: List<FakePerson>): JsonObject? {
    val parts = path.split('/')
    val person = people.firstOrNull { it.tagKey == parts[3] } ?: return null
    return buildJsonObject {
        putJsonObject("MediaContainer") {
            if (parts.getOrNull(4) == "credits") {
                putJsonArray("CreditGroup") {
                    person.credits.forEach { (kind, credits) ->
                        add(
                            buildJsonObject {
                                put("type", kind)
                                putJsonArray("Credit") { credits.forEach { add(credit(it)) } }
                            }
                        )
                    }
                }
            } else {
                putJsonArray("Metadata") { add(person(person)) }
            }
        }
    }
}

private fun person(p: FakePerson) = buildJsonObject {
    put("title", p.name)
    put("type", "person")
    p.summary?.let { put("summary", it) }
    p.bornAt?.let { put("bornAt", it) }
    p.birthPlace?.let { put("birthPlace", it) }
    put("thumb", "/people/${p.id}.jpg")
    putJsonArray("CreditType") {
        p.creditTypes.forEach { (type, count) ->
            add(
                buildJsonObject {
                    put("type", type)
                    put("count", count)
                }
            )
        }
    }
}

private fun credit(c: FakeCredit) = buildJsonObject {
    c.role?.let { put("role", it) }
    putJsonObject("Metadata") {
        put("ratingKey", c.ratingKey)
        put("title", c.title)
        put("type", if (c.show) "show" else "movie")
        c.year?.let {
            put("year", it)
            put("originallyAvailableAt", "$it-06-01T00:00:00.000Z")
        }
        put("thumb", "/discover/${c.ratingKey}.jpg")
    }
}
