package com.biskyt.myplexplayer.data

import com.biskyt.myplexplayer.core.epg.ProgrammeKind
import com.biskyt.myplexplayer.core.plex.PlexChannel
import com.biskyt.myplexplayer.core.plex.PlexClient
import com.biskyt.myplexplayer.core.plex.PlexLiveTv
import com.biskyt.myplexplayer.core.plex.PlexPlayState
import com.biskyt.myplexplayer.core.plex.PlexProgramme
import com.biskyt.myplexplayer.core.plex.PlexStream
import com.biskyt.myplexplayer.data.db.ProgrammeEntity
import java.util.UUID
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** A Plex channel's id here, from its key on the server: the same at every sync, so favourites and choices stay. */
fun plexChannelId(key: String): Long = key.fold(FNV_OFFSET) { h, c -> (h xor c.code.toLong()) * FNV_PRIME } and Long.MAX_VALUE

private const val FNV_OFFSET = -0x340d631b7bdddcdbL // FNV-1a, 64-bit
private const val FNV_PRIME = 0x100000001b3L

/**
 * The guide's showings of [programmes] as the app's guide rows, each on its channel's key ([channels]: a showing
 * may name its channel by any of the channel's keys). An episode's title is its series', with its own as subtitle.
 */
fun plexGuideRows(programmes: List<PlexProgramme>, channels: List<PlexChannel>): List<ProgrammeEntity> {
    val keyOf = channels.flatMap { ch -> ch.tuneKey?.let { key -> ch.keys.map { it to key } }.orEmpty() }.toMap()
    return programmes.flatMap { programme ->
        val episode = programme.type == "episode" && programme.grandparentTitle != null
        programme.airings.mapNotNull { airing ->
            ProgrammeEntity(
                sourceId = 0,
                xmlId = airing.channelIdentifier?.let(keyOf::get) ?: return@mapNotNull null,
                startMs = (airing.beginsAt ?: return@mapNotNull null) * 1000,
                stopMs = (airing.endsAt ?: return@mapNotNull null) * 1000,
                title = if (episode) programme.grandparentTitle.orEmpty() else programme.title,
                subtitle = programme.title.takeIf { episode && it.isNotBlank() },
                description = programme.summary.ifBlank { null },
                kind = when (programme.type) {
                    "movie" -> ProgrammeKind.MOVIE
                    "episode", "show" -> ProgrammeKind.SERIES
                    else -> ProgrammeKind.UNKNOWN
                },
                season = programme.parentIndex.takeIf { episode },
                episode = programme.index.takeIf { episode },
            )
        }
    }.distinctBy { it.xmlId to it.startMs }
}

/** A server's Live TV ([live]) and the channels of its guide's lineup. */
class PlexLineup(val live: PlexLiveTv, val channels: List<PlexChannel>)

/**
 * One channel ([channelKey]) played from a Plex server's tuner: [start] tunes it (once: a tune is never sent again,
 * as a second can take a second tuner) and streams the live session as it is; [heartbeat] keeps the session going;
 * [close] lets the tuner go. Starting and closing go one at a time, so a close during a tune waits for it and stops
 * what it took.
 */
class PlexLive(private val client: PlexClient, private val live: PlexLiveTv, private val channelKey: String) {
    private val session = UUID.randomUUID().toString()
    private val lock = Mutex()
    private var tuned: PlexProgramme? = null
    private var startedAt = 0L

    private fun playedMs() = System.currentTimeMillis() - startedAt

    /** The live stream, its picture and sound copied. The server answers before the tuner has locked: it fills slowly. */
    suspend fun start(): PlexStream = lock.withLock {
        startedAt = System.currentTimeMillis()
        // Seen through even when the channel is left meanwhile: only its answer says which session to stop, which
        // lets the tuner go (stopping the stream alone isn't known to).
        val programme = withContext(NonCancellable) { client.tune(live, channelKey, session) }
        tuned = programme
        client.liveStream(programme.key, session)
    }

    /** Every 10 seconds while it plays: without it the server ends the session. */
    suspend fun heartbeat() {
        val programme = tuned ?: return
        send { client.liveTimeline(programme, PlexPlayState.PLAYING, playedMs(), session) }
    }

    /** Stops the session and lets the tuner go (as far as it got: a tune cut short is stopped by its session). */
    suspend fun close() = lock.withLock {
        if (startedAt == 0L) return@withLock // never tuned
        coroutineScope {
            tuned?.let { launch { send { client.liveTimeline(it, PlexPlayState.STOPPED, playedMs(), session) } } }
            launch { send { client.stopStream(session) } }
        }
    }
}
