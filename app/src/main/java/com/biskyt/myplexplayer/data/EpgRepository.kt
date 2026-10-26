package com.biskyt.myplexplayer.data

import androidx.room.withTransaction
import com.biskyt.myplexplayer.core.plex.PlexException
import com.biskyt.myplexplayer.data.db.AppDatabase
import com.biskyt.myplexplayer.data.db.ChannelEpgMappingEntity
import com.biskyt.myplexplayer.data.db.ChannelProgramme
import com.biskyt.myplexplayer.data.db.EpgSourceEntity
import com.biskyt.myplexplayer.data.db.MAX_SQL_ARGS
import com.biskyt.myplexplayer.data.db.PlaylistEntity
import com.biskyt.myplexplayer.data.db.ProgrammeEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

/**
 * The TV guide: each Plex server's own guide, kept as that server's guide source (its channels use it), and programme
 * lookups.
 */
class EpgRepository(private val db: AppDatabase) {
    private val epg = db.epg()

    private val _changes = MutableStateFlow(0)

    /** Ticks whenever programme data changes, so screens can reload now/next. */
    val changes: StateFlow<Int> = _changes

    private fun changed() = _changes.update { it + 1 }

    /**
     * A Plex server's own guide ([programmes], each on its channel's key), kept as that server's guide source: its
     * channels ([channelKeys], by stream id) use it.
     */
    suspend fun savePlexGuide(p: PlaylistEntity, channelKeys: Map<Long, String>, programmes: List<ProgrammeEntity>) {
        val stamp = System.currentTimeMillis()
        db.withTransaction {
            val source = plexGuide(p)
            epg.upsertMappings(channelKeys.map { (id, key) -> ChannelEpgMappingEntity(p.id, id, source, key, manual = false, score = 1.0) })
            programmes.map { it.copy(sourceId = source, stamp = stamp) }.chunked(BATCH).forEach { epg.insertProgrammes(it) }
            epg.deleteOlderProgrammes(source, stamp)
            epg.setImportResult(source, stamp, channelKeys.size, error = null)
        }
        changed()
    }

    /** A Plex server's guide that couldn't be fetched: the last one stays, and why it failed shows with the server. */
    suspend fun plexGuideFailed(p: PlaylistEntity, e: PlexException) {
        // Kept as what failed (the server's code, or a guide not understood), to read in the language of the moment.
        val error = GuideError.saved(
            when {
                e.code > 0 -> GuideError.server(e.code)
                e.reason == PlexException.Reason.UNEXPECTED_RESPONSE -> GuideError.unreadable()
                e.reason == PlexException.Reason.UNREACHABLE -> GuideError.unreachable()
                else -> e
            }
        )
        db.withTransaction { epg.setSourceError(plexGuide(p), error) }
    }

    /** Why each source's guide didn't load at its last fetch (saved as what failed: see [GuideError]), by source id. */
    fun observeGuideErrors(): Flow<Map<Long, String>> = epg.observeSources().map { sources ->
        sources.mapNotNull { s -> s.playlistId?.let { id -> s.lastError?.let { id to it } } }.toMap()
    }

    /** Why source [playlistId]'s guide didn't load at its last fetch, in words; null if it did. */
    suspend fun plexGuideError(playlistId: Long): String? = epg.providerSource(playlistId)?.lastError?.let(GuideError::text)

    /** The id of [p]'s own guide source, added the first time. */
    private suspend fun plexGuide(p: PlaylistEntity): Long = epg.providerSource(p.id)?.id
        ?: epg.insertSource(EpgSourceEntity(name = p.name, url = "$PLEX_GUIDE${p.id}", playlistId = p.id))

    /** Programmes over well before now go (a guide that no longer loads would otherwise keep them for ever). */
    suspend fun pruneProgrammes() {
        epg.pruneProgrammes(System.currentTimeMillis() - KEEP_PAST_MS)
    }

    /** Programmes changed elsewhere (a backup restored over them): screens read them again. */
    fun reload() = changed()

    /** Removes source [playlistId]'s guide and all it holds (the server is gone, or no longer shares Live TV). */
    suspend fun removeGuide(playlistId: Long) {
        val id = epg.providerSource(playlistId)?.id ?: return
        db.withTransaction {
            epg.clearProgrammes(id)
            epg.deleteMappingsForSource(id)
            epg.deleteSource(id)
        }
        changed()
    }

    /**
     * Programmes overlapping [from]..[to] for the given channels, grouped by stream id and sorted by start.
     * Lists that only show titles pass [details] = false to skip loading descriptions.
     */
    suspend fun programmes(
        playlistId: Long,
        streamIds: Collection<Long>,
        from: Long,
        to: Long,
        details: Boolean = true
    ): Map<Long, List<ChannelProgramme>> {
        if (streamIds.isEmpty()) return emptyMap()
        return streamIds.chunked(MAX_SQL_ARGS).flatMap { epg.programmes(playlistId, it, from, to, details) }.groupBy { it.streamId }
    }

    /**
     * [p] with its description (lists of programmes are read without it: descriptions are large), as the guide has
     * it; [p] itself if it has one already or the guide no longer has [p].
     */
    suspend fun withDetails(playlistId: Long, p: ChannelProgramme): ChannelProgramme {
        if (p.description != null) return p
        return programmes(playlistId, listOf(p.streamId), p.startMs, p.stopMs)[p.streamId]?.firstOrNull { it.startMs == p.startMs } ?: p
    }
    private companion object {
        const val BATCH = 1000
        const val KEEP_PAST_MS = 2 * 3_600_000L
    }
}

/** A Plex server's guide source's address: not one to download (see [EpgRepository.savePlexGuide]). */
private const val PLEX_GUIDE = "plex:"
