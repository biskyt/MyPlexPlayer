package com.biskyt.mytvplayer.data

import android.util.Xml
import androidx.room.withTransaction
import com.biskyt.mytvplayer.core.epg.ChannelMatcher
import com.biskyt.mytvplayer.core.epg.ChannelNames
import com.biskyt.mytvplayer.core.epg.EpgCandidate
import com.biskyt.mytvplayer.core.epg.EpgMatch
import com.biskyt.mytvplayer.core.epg.XmltvChannel
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
import java.text.NumberFormat
import java.util.zip.Deflater
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/** Which channels automatic matching looks at, and how sure it must be. */
enum class MatchScope(val threshold: Double) {
    /** Every channel without a manual choice (when a guide is first loaded). */
    ALL(ChannelMatcher.AUTO_THRESHOLD),

    /** Channels new since the last update, or whose guide channel has gone. */
    NEW(ChannelMatcher.AUTO_THRESHOLD),

    /** As [NEW], plus channels tried before without a match (a guide's channel list changed). */
    RETRY(ChannelMatcher.AUTO_THRESHOLD),

    /** Channels without a guide, near-certain matches only (the "Match channels without a guide" button). */
    MISSING(ChannelMatcher.MISSING_THRESHOLD)
}

/** How many channels without a guide were checked, and how many got one. */
data class MatchResult(val checked: Int, val matched: Int)

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
        // Normalized afresh rather than from nameNorm, so guides stored by an older version follow today's rules.
        epg.allChannels().map { EpgCandidate(it.sourceId, it.xmlId, it.displayName, ChannelNames.normalize(it.displayName)) }
    ).also { matcherCache = it }

    fun observeSources() = epg.observeSources()

    fun observeMatchCounts(playlistId: Long) = epg.observeMatchCounts(playlistId)

    suspend fun coverage() = epg.coverage()

    fun pageForMatching(playlistId: Long, query: String, status: Int) = androidx.paging.Pager(androidx.paging.PagingConfig(pageSize = 40)) {
        epg.pageForMatching(playlistId, likeArg(query), status)
    }.flow

    suspend fun ensureProviderSource(p: PlaylistEntity, xmltvUrl: String) {
        val existing = epg.providerSource(p.id)
        if (existing == null) {
            epg.insertSource(EpgSourceEntity(name = "${p.name} guide", url = xmltvUrl, playlistId = p.id))
        } else if (existing.url != xmltvUrl) {
            epg.upsertSource(existing.copy(url = xmltvUrl, lastImportAt = 0)) // a different guide: match everything again
        }
    }

    /** Named after its address when [name] is blank. */
    suspend fun addSource(name: String, url: String): Long =
        epg.insertSource(EpgSourceEntity(name = name.trim().ifEmpty { url.trim() }, url = url.trim()))

    /** Adds those of [guides] not added already (by address). */
    suspend fun addMissing(guides: List<FreeGuide>) {
        val added = sourceUrls()
        guides.filter { it.url !in added }.forEach { addSource(it.sourceName, it.url) }
    }

    suspend fun sourceUrls(): Set<String> = epg.sourceUrls().toHashSet()

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

    /**
     * Downloads and imports every enabled source, then re-runs automatic matching for [playlistIds]. A source that
     * fails doesn't stop the others: returns each failed source's error, by id (also kept as its lastError). With
     * [only], just that source is downloaded again; the others are used as last downloaded.
     */
    suspend fun importAll(playlistIds: List<Long>, only: Long? = null, onProgress: (String) -> Unit = {}): Map<Long, String> =
        withContext(Dispatchers.IO) {
            val errors = HashMap<Long, String>()
            val sources = epg.enabledSources()
            // Everything is matched again only when a guide loads for the first time; otherwise matches are kept, and
            // channels without one are tried again only if a guide's channel list changed.
            var firstLoad = false
            var guideChanged = false
            for (s in sources) {
                if (only != null && s.id != only && sourceFile(s.id).exists()) continue
                val result = load(s, onProgress)
                result.onSuccess { count ->
                    if (s.lastImportAt == 0L) firstLoad = true
                    if (count != s.channelCount) guideChanged = true
                }
                result.exceptionOrNull()?.let { errors[s.id] = it.message ?: it.javaClass.simpleName }
            }
            onProgress("Matching channels…")
            val scope = when {
                firstLoad -> MatchScope.ALL
                guideChanged -> MatchScope.RETRY
                else -> MatchScope.NEW
            }
            playlistIds.forEach { autoMatch(it, scope, onProgress) }
            releaseMatcher()
            val late = HashMap<Long, List<EpgChannelEntity>>()
            for (s in sources) {
                if (!sourceFile(s.id).exists()) continue
                onProgress("Loading programmes from ${s.name}…")
                runCatching { importProgrammes(s.id, epg.mappedXmlIds(s.id).toHashSet(), known = epg.channelXmlIds(s.id).toHashSet()) }
                    .onSuccess { if (it.isNotEmpty()) late[s.id] = it }
                    // E.g. a file cut off part-way: the channels at its start read fine, so say so here.
                    .onFailure { e ->
                        (e.message ?: e.javaClass.simpleName).let {
                            epg.setSourceError(s.id, it)
                            errors[s.id] = it
                        }
                    }
            }
            if (late.isNotEmpty()) addLateChannels(late, playlistIds, onProgress)
            epg.pruneProgrammes(clock() - KEEP_PAST_MS)
            changed()
            errors
        }

    /**
     * A guide that lists channels after programmes (against the XMLTV format, e.g. guides joined end to end): its
     * channel list was read short. Adds the rest, matches again and reloads its programmes; later updates read such a
     * guide in full ([interleavedMarker]).
     */
    private suspend fun addLateChannels(late: Map<Long, List<EpgChannelEntity>>, playlistIds: List<Long>, onProgress: (String) -> Unit) {
        for ((id, rows) in late) {
            rows.chunked(BATCH).forEach { epg.upsertChannels(it) }
            interleavedMarker(id).createNewFile()
        }
        matcherCache = null
        playlistIds.forEach { autoMatch(it, MatchScope.RETRY, onProgress) }
        releaseMatcher()
        late.keys.forEach { id -> runCatching { importProgrammes(id, epg.mappedXmlIds(id).toHashSet()) } }
    }

    /** Downloads one source and reads its channels: their number, or why it failed (also stored with the source). */
    private suspend fun load(s: EpgSourceEntity, onProgress: (String) -> Unit): Result<Int> {
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
        return result
    }

    /**
     * Matches a playlist's channels ([scope] says which) to guide channels. A channel with nothing good enough is stored
     * with no guide, so later updates know it was tried. Returns a row for every channel checked.
     */
    suspend fun autoMatch(
        playlistId: Long,
        scope: MatchScope = MatchScope.ALL,
        onProgress: (String) -> Unit = {}
    ): List<ChannelEpgMappingEntity> = withContext(Dispatchers.Default) {
        val channels = when (scope) {
            MatchScope.ALL -> {
                val manual = epg.manualStreamIds(playlistId).toHashSet()
                db.channels().names(playlistId).filter { it.streamId !in manual }
            }
            MatchScope.NEW -> epg.namesToMatch(playlistId, retryFailed = false, visibleOnly = false)
            MatchScope.RETRY -> epg.namesToMatch(playlistId, retryFailed = true, visibleOnly = false)
            MatchScope.MISSING -> epg.namesToMatch(playlistId, retryFailed = true, visibleOnly = true)
        }
        // Building the matcher is the slow part: skip it when there's nothing to match.
        if (channels.isEmpty() && scope != MatchScope.ALL) return@withContext emptyList()
        val matcher = matcher()
        val rows = channels.mapIndexed { i, c ->
            if (i % PROGRESS_EVERY == 0) onProgress(matchingLabel(i, channels.size))
            val m = matcher.autoMatch(c.name, c.providerEpgId, scope.threshold)
            ChannelEpgMappingEntity(
                playlistId,
                c.streamId,
                m?.candidate?.sourceId,
                m?.candidate?.xmlId,
                manual = false,
                score = m?.score ?: 0.0
            )
        }
        db.withTransaction {
            if (scope == MatchScope.ALL) epg.clearAutoMappings(playlistId)
            rows.chunked(BATCH).forEach { epg.upsertMappings(it) }
        }
        rows
    }

    /**
     * Tries again, with only near-certain matches, for every visible channel without a guide (except those the user set
     * to "no guide"), then loads the programmes of the guide channels found.
     */
    suspend fun matchMissing(playlistIds: List<Long>, onProgress: (String) -> Unit = {}): MatchResult = withContext(Dispatchers.IO) {
        val checked = playlistIds.flatMap { autoMatch(it, MatchScope.MISSING, onProgress) }
        val found = checked.filter { it.sourceId != null }
        releaseMatcher()
        val names = epg.enabledSources().associate { it.id to it.name }
        found.groupBy({ it.sourceId!! }, { it.xmlId!! }).forEach { (source, xmlIds) ->
            onProgress("Loading programmes from ${names[source] ?: "the guide"}…")
            runCatching { importProgrammes(source, xmlIds.toHashSet(), onlyThese = true) }
        }
        changed()
        MatchResult(checked.size, found.size)
    }

    private fun matchingLabel(done: Int, total: Int): String {
        val n = NumberFormat.getIntegerInstance()
        return "Matching channels… ${n.format(done)} of ${n.format(total)}"
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
    private var importScheduled = false

    /**
     * Reading a guide file takes seconds on a slow box, so channels re-mapped in quick succession are collected and
     * their programmes loaded in one pass per guide. A running import is never cancelled; changes made while it runs
     * are picked up by the next one.
     */
    private fun queueProgrammeImport(sourceId: Long, xmlId: String) = synchronized(pendingImports) {
        pendingImports.getOrPut(sourceId) { HashSet() } += xmlId
        if (importScheduled) return@synchronized
        importScheduled = true
        scope.launch(Dispatchers.IO) {
            delay(REMAP_DEBOUNCE_MS)
            val work = synchronized(pendingImports) {
                importScheduled = false
                pendingImports.toMap().also { pendingImports.clear() }
            }
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
        val valid = epg.allSourceIds().flatMap { listOf(sourceFile(it).name, interleavedMarker(it).name) }.toSet()
        File(cacheDir, "epg").listFiles()?.filter { it.name !in valid }?.forEach { it.delete() }
    }

    /** After the sources were replaced wholesale (restoring a backup): their downloaded files and matcher are stale. */
    suspend fun forgetDownloads() = withContext(Dispatchers.IO) {
        matcherCache = null
        File(cacheDir, "epg").listFiles()?.forEach { it.delete() }
        changed()
    }

    // --- import internals ---

    private fun sourceFile(id: Long) = File(cacheDir, "epg/source-$id.xml.gz")

    /** Present for a guide whose channels aren't all before its programmes: its channels are read from the whole file. */
    private fun interleavedMarker(id: Long) = File(cacheDir, "epg/source-$id.interleaved")

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
            val onChannel: suspend (XmltvChannel) -> Unit = { c ->
                val name = c.names.firstOrNull() ?: c.id
                rows += EpgChannelEntity(s.id, c.id, name, ChannelNames.normalize(name))
            }
            if (interleavedMarker(s.id).exists()) {
                parser().parse(input, { false }, onChannel) {}
            } else {
                parser().parseChannels(input, onChannel)
            }
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
    private suspend fun importProgrammes(
        sourceId: Long,
        xmlIds: Set<String>,
        onlyThese: Boolean = false,
        known: Set<String>? = null
    ): List<EpgChannelEntity> {
        // With [known] (the channels read before), returns any others the file has: see addLateChannels.
        val late = ArrayList<EpgChannelEntity>()
        if (!sourceFile(sourceId).exists()) return late
        if (xmlIds.isEmpty() && known == null) {
            if (!onlyThese) epg.clearProgrammes(sourceId) // nothing uses this guide any more
            return late
        }
        val now = clock()
        val batch = ArrayList<ProgrammeEntity>(BATCH)
        val onChannel: (suspend (XmltvChannel) -> Unit)? = known?.let {
            { c ->
                if (c.id !in known) {
                    val name = c.names.firstOrNull() ?: c.id
                    late += EpgChannelEntity(sourceId, c.id, name, ChannelNames.normalize(name))
                }
            }
        }
        open(sourceId).use { input ->
            parser(now - KEEP_PAST_MS, now + KEEP_FUTURE_MS).parse(input, wantProgrammes = {
                it in xmlIds
            }, onChannel = onChannel, onProgramme = { p ->
                batch += ProgrammeEntity(
                    sourceId, p.channelId, p.startMs, p.stopMs, p.title, p.subtitle, p.description, now, p.kind, p.season, p.episode
                )
                if (batch.size >= BATCH) {
                    epg.insertProgrammes(batch.toList())
                    batch.clear()
                }
            })
        }
        if (batch.isNotEmpty()) epg.insertProgrammes(batch)
        if (onlyThese) {
            xmlIds.chunked(MAX_SQL_ARGS).forEach { epg.deleteOlderProgrammes(sourceId, it, now) }
        } else {
            epg.deleteOlderProgrammes(sourceId, now)
        }
        return late
    }

    private companion object {
        const val BATCH = 1000
        const val PROGRESS_EVERY = 100
        const val MAX_SQL_ARGS = 900
        const val KEEP_PAST_MS = 2 * 3_600_000L
        const val KEEP_FUTURE_MS = 3 * 24 * 3_600_000L
        const val REMAP_DEBOUNCE_MS = 1_500L
    }
}
