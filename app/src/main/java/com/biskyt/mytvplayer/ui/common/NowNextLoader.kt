package com.biskyt.mytvplayer.ui.common

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.mutableStateMapOf
import com.biskyt.mytvplayer.data.db.ChannelProgramme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

@Immutable
data class NowNext(val now: ChannelProgramme?, val next: ChannelProgramme?) {
    fun progress(nowMs: Long): Float = now?.let { (nowMs - it.startMs).toFloat() / (it.stopMs - it.startMs).coerceAtLeast(1) } ?: 0f

    companion object {
        val Empty = NowNext(null, null)
    }
}

/**
 * Loads now/next only for channels that are actually on screen. Rows call [request] when shown; requests are
 * collected for a moment and fetched in one query. Results live in a snapshot map so only the affected row redraws.
 * Every minute, rows whose programme has ended are refreshed. [scope] must run on the main thread.
 */
class NowNextLoader(
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
    private val load: suspend (ids: List<Long>, from: Long, to: Long) -> Map<Long, List<ChannelProgramme>>,
) {
    val entries = mutableStateMapOf<Long, NowNext>()
    private val pending = LinkedHashSet<Long>()
    private var batchJob: Job? = null

    init {
        scope.launch {
            while (isActive) {
                delay(60_000 - clock() % 60_000)
                val now = clock()
                val stale = entries.filterValues { v -> v.now != null && v.now.stopMs <= now }.keys
                if (stale.isNotEmpty()) fetch(stale.toList())
            }
        }
    }

    fun request(streamId: Long) {
        if (streamId in entries || !pending.add(streamId)) return
        if (batchJob?.isActive == true) return
        batchJob = scope.launch {
            delay(BATCH_DELAY_MS)
            val ids = pending.toList()
            pending.clear()
            batchJob = null // requests arriving during the fetch start a new batch
            fetch(ids)
        }
    }

    /** Clears cached values, e.g. after the guide was updated or a channel's guide source changed. */
    fun invalidate(streamId: Long? = null) {
        if (streamId == null) entries.clear() else entries.remove(streamId)
    }

    private suspend fun fetch(ids: List<Long>) {
        val now = clock()
        val result = load(ids, now, now + LOOKAHEAD_MS)
        for (id in ids) {
            val list = result[id].orEmpty()
            val current = list.firstOrNull { it.startMs <= now && it.stopMs > now }
            val next = list.firstOrNull { it.startMs >= (current?.stopMs ?: now) && it !== current }
            entries[id] = NowNext(current, next)
        }
    }

    private companion object {
        const val BATCH_DELAY_MS = 120L
        const val LOOKAHEAD_MS = 8 * 3_600_000L
    }
}
