package com.biskyt.myplexplayer.testutil

import com.biskyt.myplexplayer.testutil.FakePlex.Companion.MACHINE_ID
import com.biskyt.myplexplayer.testutil.FakePlex.Companion.SERVER_TOKEN
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/** [FakePlex]'s account resources: two servers (Home Plex at [url], and a friend's) and a phone, which is no server. */
internal fun plexResources(url: String) = buildJsonArray {
    add(resource("Home Plex", MACHINE_ID, owned = true, url))
    add(resource("Friend's Plex", "friend-plex", owned = false, "$url/friend"))
    add(
        buildJsonObject {
            put("name", "Phone")
            put("clientIdentifier", "phone")
            put("provides", "client,player")
        }
    )
}

private fun resource(name: String, id: String, owned: Boolean, uri: String) = buildJsonObject {
    put("name", name)
    put("clientIdentifier", id)
    put("provides", "server")
    put("owned", owned)
    put("accessToken", SERVER_TOKEN)
    putJsonArray("connections") {
        add(
            buildJsonObject {
                put("uri", uri)
                put("local", true)
                put("relay", false)
            }
        )
    }
}
