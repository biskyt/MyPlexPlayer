package com.biskyt.mytvplayer.data

import android.util.LruCache
import com.biskyt.mytvplayer.core.xtream.CatchupListing
import com.biskyt.mytvplayer.core.xtream.CatchupProgramme
import com.biskyt.mytvplayer.data.db.ChannelEntity
import com.biskyt.mytvplayer.data.db.PlaylistEntity

/**
 * Catch-up (replaying what a channel showed in the last few days), for Xtream channels with
 * [ChannelEntity.archiveDays]. The listing comes from the provider only when catch-up is used (the catch-up page,
 * "Start over", the TV guide scrolled into the past) and is kept briefly for the few channels used most recently.
 */
class CatchupRepository(private val playlists: PlaylistRepository, private val clock: () -> Long = System::currentTimeMillis) {
    private class Cached(val listing: CatchupListing, val at: Long)

    private val cache = LruCache<String, Cached>(CACHE_CHANNELS)

    /** The channel's whole listing (past and coming programmes). Throws XtreamException when the provider fails. */
    suspend fun listing(p: PlaylistEntity, ch: ChannelEntity): CatchupListing {
        val key = "${p.id}:${ch.streamId}"
        cache[key]?.takeIf { clock() - it.at < MAX_AGE_MS }?.let { return it.listing }
        return playlists.client(p).catchupListing(ch.streamId).also { cache.put(key, Cached(it, clock())) }
    }

    /** Programmes that can be replayed: started, kept by the provider, and within the channel's catch-up days. */
    fun playable(listing: CatchupListing, ch: ChannelEntity): List<CatchupProgramme> {
        val now = clock()
        val from = now - ch.archiveDays * DAY_MS
        return listing.programmes.filter { it.hasArchive && it.startMs < now && it.startMs >= from }
    }

    /** The programme on now, for "Start over". */
    fun onNow(listing: CatchupListing): CatchupProgramme? {
        val now = clock()
        return listing.programmes.firstOrNull { it.startMs <= now && it.stopMs > now }
    }

    /** The channel as it was from [startMs], running on to the present. */
    fun url(p: PlaylistEntity, ch: ChannelEntity, listing: CatchupListing, startMs: Long): String {
        // A minute over, so the stream doesn't end just short of now.
        val minutes = ((clock() - startMs) / 60_000).toInt().coerceAtLeast(1) + 1
        return playlists.client(p).catchupUrl(ch.streamId, startMs, listing, minutes)
    }

    companion object {
        const val DAY_MS = 24 * 3_600_000L
        private const val CACHE_CHANNELS = 16
        private const val MAX_AGE_MS = 10 * 60_000L
    }
}
