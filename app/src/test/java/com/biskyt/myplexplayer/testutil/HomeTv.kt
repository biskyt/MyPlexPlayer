package com.biskyt.myplexplayer.testutil

import com.biskyt.myplexplayer.AppContainer

/**
 * Adds [provider] as the "Home TV" playlist and loads its channels, the start of most UI tests; with [vod] its films
 * and series too, and with [guide] its guide. Returns the playlist's id.
 */
suspend fun AppContainer.addHomeTv(provider: FakeProvider, vod: Boolean = false, guide: Boolean = false): Long {
    val id = playlists.add("Home TV", provider.url, "me", "secret")
    playlists.syncChannels(id)
    if (vod) this.vod.sync(playlists.get(id)!!)
    if (guide) {
        playlists.get(id)?.let { epg.ensureProviderSource(it, playlists.client(it).xmltvUrl()) }
        epg.importAll(listOf(id))
    }
    return id
}
