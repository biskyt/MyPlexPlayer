package com.biskyt.mytvplayer.data

import android.util.Xml
import androidx.room.withTransaction
import com.biskyt.mytvplayer.core.epg.ChannelMatcher
import com.biskyt.mytvplayer.core.epg.ChannelNames
import com.biskyt.mytvplayer.core.epg.EpgCandidate
import com.biskyt.mytvplayer.core.epg.EpgMatch
import com.biskyt.mytvplayer.core.epg.XmltvParser
import com.biskyt.mytvplayer.data.db.AppDatabase
import com.biskyt.mytvplayer.data.db.ChannelEpgMappingEntity
import com.biskyt.mytvplayer.data.db.ChannelProgramme
import com.biskyt.mytvplayer.data.db.EpgChannelEntity
import com.biskyt.mytvplayer.data.db.EpgSourceEntity
import com.biskyt.mytvplayer.data.db.PlaylistEntity
import com.biskyt.mytvplayer.data.db.ProgrammeEntity
import java.io.BufferedInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.zip.Deflater
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * TV guide data: XMLTV sources, which guide channel each playlist channel uses, and programme lookups.
 *
 * To keep storage small on low-end boxes, every source's channel list is stored (needed for matching) but programmes
 * are only stored for guide channels that some playlist channel actually uses, within a few days of now. The
 * downloaded file is kept (gzipped) so a newly mapped channel can be filled in without downloading again.
 */
class EpgRepository(
    private val db: AppDatabase,
    private val http: OkHttpClient,
    private val cacheDir: File,
    /** Runs the debounced re-imports after manual guide changes. */
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis
) {
    private val epg = db.epg()

    private val _changes = MutableStateFlow(0)

    /** Ticks whenever programme data or a channel's guide choice changes, so screens can reload now/next. */
    val changes: StateFlow<Int> = _changes

    private fun changed() = _changes.update { it + 1 }

    /**
     * Built from every guide channel and reused while matching (building it is the slow part). Dropped after each
     * import's matching step and when the matching screen closes, so it doesn't hold memory while watching TV.
     */
    @Volatile private var matcherCache: ChannelMatcher? = null

    private suspend fun matcher(): ChannelMatcher = matcherCache ?: ChannelMatcher(
        epg.allChannels().map { EpgCandidate(it.sourceId, it.xmlId, it.displayName, it.nameNorm) }
    ).also { matcherCache = it }

    fun observeSources() = epg.observeSources()

    fun observeMatchCounts(playlistId: Long) = epg.observeMatchCounts(playlistId)

    fun pageForMatching(playlistId: Long, query: String, status: Int) = androidx.paging.Pager(androidx.paging.PagingConfig(pageSize = 40)) {
        epg.pageForMatching(playlistId, query.trim(), status)
    }.flow

    suspend fun ensureProviderSource(p: PlaylistEntity, xmltvUrl: String) {
        val existing = epg.providerSource(p.id)
        if (existing == null) {
            epg.insertSource(EpgSourceEntity(name = "${p.name} guide", url = xmltvUrl, playlistId = p.id))
        } else if (existing.url != xmltvUrl) {
            epg.upsertSource(existing.copy(url = xmltvUrl))
        }
    }

    suspend fun addSource(name: String, url: String): Long = epg.insertSource(
        EpgSourceEntity(
            name = name.ifBlank {
                url
            },
            url = url.trim()
        )
    )

    suspend fun setSourceEnabled(source: EpgSourceEntity, enabled: Boolean) = epg.upsertSource(source.copy(enabled = enabled))

    suspend fun removeSource(id: Long) {
        db.withTransaction {
            epg.clearProgrammes(id)
            epg.clearChannels(id)
            epg.deleteMappingsForSource(id)
            epg.deleteSource(id)
        }
        sourceFile(id).delete()
        matcherCache = null
        changed()
    }

    /** Downloads and imports every enabled source, then re-runs automatic matching for [playlistIds]. */
    suspend fun importAll(playlistIds: List<Long>, onProgress: (String) -> Unit = {}) = withContext(Dispatchers.IO) {
        val sources = epg.enabledSources()
        for (s in sources) {
            onProgress("Downloading ${s.name}…")
            val result = runCatching {
                download(s)
                onProgress("Reading ${s.name}…")
                importChannels(s)
            }
            epg.upsertSource(
                s.copy(
                    lastImportAt = if (result.isSuccess) clock() else s.lastImportAt,
                    channelCount = result.getOrNull() ?: s.channelCount,
                    lastError = result.exceptionOrNull()?.let { it.message ?: it.javaClass.simpleName }
                )
            )
        }
        onProgress("Matching channels…")
        playlistIds.forEach { autoMatch(it) }
        releaseMatcher()
        for (s in sources) {
            if (!sourceFile(s.id).exists()) continue
            onProgress("Loading programmes from ${s.name}…")
            runCatching { importProgrammes(s.id, epg.mappedXmlIds(s.id).toHashSet()) }
        }
        epg.pruneProgrammes(clock() - KEEP_PAST_MS)
        changed()
    }

    /** Re-matches every channel of a playlist that doesn't have a manual choice. */
    suspend fun autoMatch(playlistId: Long) = withContext(Dispatchers.Default) {
        val matcher = matcher()
        val manual = epg.manualStreamIds(playlistId).toHashSet()
        val channels = db.channels().names(playlistId)
        val updates = channels.filter { it.streamId !in manual }.mapNotNull { c ->
            matcher.autoMatch(c.name, c.providerEpgId)?.let { m ->
                ChannelEpgMappingEntity(playlistId, c.streamId, m.candidate.sourceId, m.candidate.xmlId, manual = false, score = m.score)
            }
        }
        db.withTransaction {
            epg.clearAutoMappings(playlistId)
            updates.chunked(BATCH).forEach { epg.upsertMappings(it) }
        }
    }

    fun releaseMatcher() {
        matcherCache = null
    }

    suspend fun suggestions(channelName: String, providerEpgId: String?, limit: Int = 8): List<EpgMatch> =
        withContext(Dispatchers.Default) {
            matcher().suggestions(channelName, providerEpgId, limit)
        }

    suspend fun searchGuideChannels(query: String) = epg.searchChannels(query.trim(), 50)

    suspend fun mappingInfo(playlistId: Long, ids: List<Long>) = if (ids.isEmpty()) emptyList() else epg.mappingInfo(playlistId, ids)

    /** The user's choice for a channel. Programmes for the chosen guide channel are loaded from the cached file. */
    suspend fun setManual(playlistId: Long, streamId: Long, sourceId: Long, xmlId: String, offsetMinutes: Int = 0) = applyMapping(
        ChannelEpgMappingEntity(playlistId, streamId, sourceId, xmlId, manual = true, score = 1.0, offsetMinutes = offsetMinutes)
    )

    private suspend fun applyMapping(m: ChannelEpgMappingEntity) {
        epg.upsertMappings(listOf(m))
        if (m.sourceId != null && m.xmlId != null) queueProgrammeImport(m.sourceId, m.xmlId)
        changed()
    }

    private val pendingImports = HashMap<Long, MutableSet<String>>()
    private var pendingJob: Job? = null

    /**
     * Reading a guide file takes seconds on a slow box, so channels re-mapped in quick succession are collected and
     * their programmes loaded in one pass per guide shortly after the last change.
     */
    private fun queueProgrammeImport(sourceId: Long, xmlId: String) = synchronized(pendingImports) {
        pendingImports.getOrPut(sourceId) { HashSet() } += xmlId
        pendingJob?.cancel()
        pendingJob = scope.launch(Dispatchers.IO) {
            delay(REMAP_DEBOUNCE_MS)
            val work = synchronized(pendingImports) { pendingImports.toMap().also { pendingImports.clear() } }
            work.forEach { (source, ids) -> runCatching { importProgrammes(source, ids, onlyThese = true) } }
            changed()
        }
    }

    suspend fun setNoGuide(playlistId: Long, streamId: Long) {
        epg.upsertMappings(listOf(ChannelEpgMappingEntity(playlistId, streamId, null, null, manual = true, score = 0.0)))
        changed()
    }

    suspend fun setOffset(playlistId: Long, streamId: Long, offsetMinutes: Int) {
        val m = epg.mapping(playlistId, streamId) ?: return
        epg.upsertMappings(listOf(m.copy(offsetMinutes = offsetMinutes, manual = true)))
        changed()
    }

    suspend fun resetToAutomatic(playlistId: Long, streamId: Long, channelName: String, providerEpgId: String?) {
        epg.deleteMapping(playlistId, streamId)
        val m = withContext(Dispatchers.Default) { matcher().autoMatch(channelName, providerEpgId) }
        if (m == null) {
            changed()
        } else {
            applyMapping(
                ChannelEpgMappingEntity(playlistId, streamId, m.candidate.sourceId, m.candidate.xmlId, manual = false, score = m.score)
            )
        }
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

    /** Deletes cached guide files whose source no longer exists. */
    suspend fun deleteOrphanFiles() = withContext(Dispatchers.IO) {
        val valid = epg.allSourceIds().map { "source-$it.xml.gz" }.toSet()
        File(cacheDir, "epg").listFiles()?.filter { it.name !in valid }?.forEach { it.delete() }
    }

    // --- import internals ---

    private fun sourceFile(id: Long) = File(cacheDir, "epg/source-$id.xml.gz")

    /** Saves the response gzipped (compressing it if the server sent plain XML). */
    private fun download(s: EpgSourceEntity) {
        val target = sourceFile(s.id)
        target.parentFile?.mkdirs()
        val tmp = File(target.path + ".part")
        http.newCall(Request.Builder().url(s.url).build()).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Server returned error ${response.code}")
            val body = BufferedInputStream(response.body!!.byteStream())
            body.mark(2)
            val gz = body.read() == 0x1f && body.read() == 0x8b
            body.reset()
            tmp.outputStream().use { out ->
                if (gz) {
                    body.copyTo(out)
                } else {
                    object : GZIPOutputStream(out, 64 * 1024) {
                        init {
                            def.setLevel(Deflater.BEST_SPEED)
                        }
                    }.use { body.copyTo(it) }
                }
            }
        }
        if (!tmp.renameTo(target)) throw IOException("Couldn't save guide file")
    }

    private fun open(sourceId: Long): InputStream = GZIPInputStream(sourceFile(sourceId).inputStream().buffered(64 * 1024), 64 * 1024)

    private fun parser(start: Long = Long.MIN_VALUE, end: Long = Long.MAX_VALUE) = XmltvParser({ Xml.newPullParser() }, start, end)

    /** Replaces the stored channel list of a source. Returns the number of channels. */
    private suspend fun importChannels(s: EpgSourceEntity): Int {
        val rows = ArrayList<EpgChannelEntity>()
        open(s.id).use { input ->
            parser().parse(input, wantProgrammes = { false }, onChannel = { c ->
                val name = c.names.firstOrNull() ?: c.id
                rows += EpgChannelEntity(s.id, c.id, name, ChannelNames.normalize(name))
            }, onProgramme = {})
        }
        db.withTransaction {
            epg.clearChannels(s.id)
            rows.chunked(BATCH).forEach { epg.upsertChannels(it) }
        }
        matcherCache = null
        return rows.size
    }

    /**
     * Stores programmes for [xmlIds] within the keep window, in batches as they are read. Rows are replaced in place
     * and older rows removed at the end, so screens never see a half-empty guide during an import.
     * [onlyThese] limits the clean-up to [xmlIds] (used when one channel is re-mapped).
     */
    private suspend fun importProgrammes(sourceId: Long, xmlIds: Set<String>, onlyThese: Boolean = false) {
        if (!sourceFile(sourceId).exists()) return
        if (xmlIds.isEmpty()) {
            if (!onlyThese) epg.clearProgrammes(sourceId) // nothing uses this guide any more
            return
        }
        val now = clock()
        val stamp = now
        val batch = ArrayList<ProgrammeEntity>(BATCH)
        open(sourceId).use { input ->
            parser(now - KEEP_PAST_MS, now + KEEP_FUTURE_MS).parse(input, wantProgrammes = {
                it in xmlIds
            }, onChannel = {}, onProgramme = { p ->
                batch += ProgrammeEntity(sourceId, p.channelId, p.startMs, p.stopMs, p.title, p.subtitle, p.description, stamp)
                if (batch.size >= BATCH) {
                    epg.insertProgrammes(batch.toList())
                    batch.clear()
                }
            })
        }
        if (batch.isNotEmpty()) epg.insertProgrammes(batch)
        if (onlyThese) {
            xmlIds.chunked(MAX_SQL_ARGS).forEach { epg.deleteOlderProgrammes(sourceId, it, stamp) }
        } else {
            epg.deleteOlderProgrammes(sourceId, stamp)
        }
    }

    private companion object {
        const val BATCH = 1000
        const val MAX_SQL_ARGS = 900
        const val KEEP_PAST_MS = 2 * 3_600_000L
        const val KEEP_FUTURE_MS = 3 * 24 * 3_600_000L
        const val REMAP_DEBOUNCE_MS = 1_500L
    }
}
