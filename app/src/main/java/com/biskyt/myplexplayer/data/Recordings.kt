package com.biskyt.myplexplayer.data

import androidx.annotation.StringRes
import com.biskyt.myplexplayer.R
import com.biskyt.myplexplayer.appString
import com.biskyt.myplexplayer.core.plex.PlexClient
import com.biskyt.myplexplayer.core.plex.PlexException
import com.biskyt.myplexplayer.core.plex.PlexItem
import com.biskyt.myplexplayer.core.plex.PlexRecordingRule
import com.biskyt.myplexplayer.core.plex.PlexRecordingRule.Companion.ALL_AIRINGS
import com.biskyt.myplexplayer.core.plex.PlexRecordingRule.Companion.NEW_ONLY
import com.biskyt.myplexplayer.core.plex.PlexRecordingRule.Companion.ONLY_NEW_AIRINGS
import com.biskyt.myplexplayer.core.plex.PlexScheduledRecording
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** How a guide programme is set to record: one airing, as part of its series, or recording now. */
enum class RecordingMark { EPISODE, SERIES, RECORDING }

/**
 * What a server has set to record ([rules], and the airings [scheduled] by them), read from the server whenever a
 * screen shows it and after each change: Plex keeps it, the app only shows it (docs/plex-api.md 14, 22-24).
 */
data class RecordingState(val rules: List<PlexRecordingRule> = emptyList(), val scheduled: List<PlexScheduledRecording> = emptyList()) {
    private val ruleByKey = rules.associateBy { it.key }

    /** Airings to come or recording now, by their programme's id and start (seconds). */
    private val airings = scheduled.filter { it.isScheduled || it.isRecording }
        .mapNotNull { s -> s.programme?.guid?.let { guid -> s.airing?.beginsAt?.let { (guid to it) to s } } }.toMap()

    /** The airing of programme [guid] that starts at [startMs] set to record, if it is. */
    fun airing(guid: String?, startMs: Long): PlexScheduledRecording? =
        if (airings.isEmpty() || guid == null) null else airings[guid to startMs / 1000]

    /** The rule that set [airing] to record. */
    fun ruleOf(airing: PlexScheduledRecording): PlexRecordingRule? = airing.mediaSubscriptionID?.let(ruleByKey::get)

    /** The series rules, by their show's id (a guide programme's `grandparentGuid`). */
    private val seriesByShow: Map<String, PlexRecordingRule> = buildMap {
        rules.forEach { r -> r.subject?.guid?.takeIf { r.isSeries }?.let { putIfAbsent(it, r) } } // the first, if two
    }

    /** The series rule recording show [showGuid], if there is one. */
    fun seriesRule(showGuid: String?): PlexRecordingRule? = showGuid?.let(seriesByShow::get)

    /** The airings recording now (by their key), from the server's status. */
    val recordingNow: Map<String, PlexScheduledRecording> = scheduled.filter {
        it.isRecording && it.key.isNotBlank()
    }.associateBy { it.key }

    /** The same, by their channel's key. */
    private val recordingOnChannel = recordingNow.values.associateBy { it.airing?.channelIdentifier }

    /** What channel [channelKey] (its `providerEpgId`) is recording now, if anything. */
    fun recordingOn(channelKey: String?): PlexScheduledRecording? = channelKey?.let(recordingOnChannel::get)

    /**
     * How programme [guid] (of show [showGuid]) starting at [startMs] is marked: as its airing is set to record, else
     * as part of a series set to record. As in Plex's own guide, every airing of that show is marked, also one the
     * rule won't record (a repeat, for new airings only).
     */
    fun mark(guid: String?, showGuid: String?, startMs: Long): RecordingMark? =
        airing(guid, startMs)?.let(::mark) ?: seriesRule(showGuid)?.let { RecordingMark.SERIES }

    /** How [airing] is set to record: recording now, as part of its series, or alone. */
    fun mark(airing: PlexScheduledRecording): RecordingMark = when {
        airing.isRecording -> RecordingMark.RECORDING
        ruleOf(airing)?.isSeries == true -> RecordingMark.SERIES
        else -> RecordingMark.EPISODE
    }

    companion object {
        val NONE = RecordingState()
    }
}

/** A recording that started or ended, for its toast: [title] is the show's (an episode's) or the programme's. */
data class RecordingEvent(val kind: Kind, val title: String) {
    enum class Kind { STARTED, FINISHED, STOPPED_EARLY }
}

/**
 * What changed between two reads of a server's recordings ([before], [after]) at [nowS] (seconds): airings that began
 * recording, and those that stopped (finished, or more than a minute before their end: stopped early).
 */
fun recordingEvents(before: RecordingState, after: RecordingState, nowS: Long): List<RecordingEvent> {
    val started = (after.recordingNow - before.recordingNow.keys).values.map { RecordingEvent(RecordingEvent.Kind.STARTED, it.title) }
    val ended = (before.recordingNow - after.recordingNow.keys).values.map {
        val early = (it.airing?.endsAt ?: 0) > nowS + EARLY_S
        RecordingEvent(if (early) RecordingEvent.Kind.STOPPED_EARLY else RecordingEvent.Kind.FINISHED, it.title)
    }
    return started + ended
}

/** When to read a server's recordings again after [state] at [nowS]: just after the next airing starts or ends, at most [MAX_WAIT_S] away. */
fun nextRecordingCheckS(state: RecordingState, nowS: Long): Long {
    // One still recording after its end (Plex records on a little): look again soon, for when it stops.
    if (state.scheduled.any { it.isRecording && (it.airing?.endsAt ?: Long.MAX_VALUE) <= nowS }) return OVERRUN_WAIT_S
    val times = state.scheduled.filter { it.isScheduled || it.isRecording }
        .flatMap { listOfNotNull(it.airing?.beginsAt, it.airing?.endsAt) }.filter { it > nowS }
    return ((times.minOrNull() ?: Long.MAX_VALUE) - nowS + SETTLE_S).coerceIn(MIN_WAIT_S, MAX_WAIT_S)
}

private const val EARLY_S = 60L

/** Plex takes a few seconds to mark an airing recording once it starts. */
private const val SETTLE_S = 10L
private const val MIN_WAIT_S = 15L
private const val OVERRUN_WAIT_S = 60L
private const val MAX_WAIT_S = 15 * 60L

/**
 * Recording from the guide on each Plex server (a source): its [RecordingState], and setting a programme, or its
 * series (new airings only, the owner's choice), to record, and cancelling. Changes run in the app's [scope] (the
 * options that start them close at once); what doesn't work is said by [notices].
 */
class Recordings(
    private val scope: CoroutineScope,
    private val playlists: PlaylistRepository,
    private val plex: PlexSources,
    private val notices: Notices
) {
    private val states = MutableStateFlow<Map<Long, RecordingState>>(emptyMap())

    /** Each source's newest refresh started, and the one its state is from: an older one finishing late is dropped. */
    private val started = AtomicLong()
    private val applied = mutableMapOf<Long, Long>()

    private val changes = MutableSharedFlow<RecordingEvent>(extraBufferCapacity = EVENTS)

    /** Recordings that started or ended on the source being watched ([startWatching]), for their toasts. */
    val events: SharedFlow<RecordingEvent> = changes

    private var watcher: Job? = null

    /** A change made here (see [change]): the watcher works out its next look again. */
    private val changed = Channel<Unit>(Channel.CONFLATED)

    /**
     * Sources read since the app was shown: [events] tells only what changes after a source's first read, not what
     * happened while the app was away.
     */
    private val seen = ConcurrentHashMap.newKeySet<Long>()

    /**
     * While the app is shown: reads the active source's ([activePlaylist], read again when it changes) recordings just
     * after each airing starts or ends (and every 15 minutes), so [events] says when one starts or stops.
     */
    fun startWatching(activePlaylist: Flow<Long>) {
        stopWatching()
        watcher = scope.launch {
            activePlaylist.collectLatest { pid ->
                var read = true
                while (true) {
                    if (read) refresh(pid)
                    val waitMs = nextRecordingCheckS(current(pid), System.currentTimeMillis() / 1000) * 1000
                    // After a change here the state is new already: only the wait is worked out again.
                    read = withTimeoutOrNull(waitMs) { changed.receive() } == null
                }
            }
        }
    }

    fun stopWatching() {
        watcher?.cancel()
        watcher = null
        seen.clear()
    }

    /** Source [playlistId]'s state as last read, for a screen's first frame. */
    fun current(playlistId: Long): RecordingState = states.value[playlistId] ?: RecordingState.NONE

    /** Source [playlistId]'s state as it changes, without asking the server (the guide's [observe] does). */
    fun watch(playlistId: Long): Flow<RecordingState> = states.map { it[playlistId] ?: RecordingState.NONE }.distinctUntilChanged()

    /** As [watch], and read from the server again first (the guide, each time it opens). */
    fun observe(playlistId: Long): Flow<RecordingState> = watch(playlistId).onStart { scope.launch { refresh(playlistId) } }

    /** Reads source [playlistId]'s rules and scheduled airings again; out of reach, the last ones stay. */
    suspend fun refresh(playlistId: Long) {
        val p = playlists.get(playlistId) ?: return
        val run = started.incrementAndGet()
        try {
            val client = plex.client(p)
            val state = coroutineScope {
                val rules = async { client.recordingRules() }
                val scheduled = async { client.scheduledRecordings() }
                RecordingState(rules.await(), scheduled.await())
            }
            val before = synchronized(applied) {
                if (run < (applied[playlistId] ?: 0)) return
                applied[playlistId] = run
                states.value[playlistId].also { states.update { it + (playlistId to state) } }
            }
            // Not on the first read since the app was shown: what was already recording then didn't just start.
            if (!seen.add(playlistId)) {
                before?.let { recordingEvents(it, state, System.currentTimeMillis() / 1000).forEach(changes::tryEmit) }
            }
        } catch (_: IOException) {
            // Shown as it was: the guide works without its marks.
        }
    }

    /**
     * The library item (a film or an episode) that [airing] recorded to, read from the server; null when it can't be
     * read (a notice says why, and the recordings are read again: the item may be deleted).
     */
    suspend fun recordedItem(playlistId: Long, airing: PlexScheduledRecording): PlexItem? {
        val key = airing.recordedRatingKey ?: return null
        val p = playlists.get(playlistId) ?: return null
        val failed = try {
            return plex.client(p).item(key)
        } catch (e: PlexException) {
            e.userMessage()
        } catch (e: IOException) {
            userMessage(e)
        }
        notices.post(Notice(appString(R.string.dvr_play_failed), failed))
        refresh(playlistId)
        return null
    }

    /** Sets programme [guid] to record: this airing, or (with [series]) every new airing of its show. */
    fun record(playlistId: Long, guid: String, series: Boolean) = change(playlistId, R.string.dvr_record_failed) { client ->
        val option = client.recordingOptions(guid).firstOrNull { it.isSeries == series }
            ?: throw PlexException("No way to record it", reason = PlexException.Reason.UNEXPECTED_RESPONSE)
        client.record(option, if (series) mapOf(ONLY_NEW_AIRINGS to NEW_ONLY) else emptyMap())
    }

    /** Cancels [airing]: for a series, this airing alone; otherwise its rule, which recorded only this. */
    fun cancel(playlistId: Long, airing: PlexScheduledRecording, rule: PlexRecordingRule?) =
        change(playlistId, R.string.dvr_cancel_failed) { client ->
            when {
                rule != null && !rule.isSeries -> client.removeRecording(rule.key)
                airing.key.isNotBlank() -> client.cancelRecording(airing.key)
                else -> throw PlexException("No key to cancel it by", reason = PlexException.Reason.UNEXPECTED_RESPONSE)
            }
        }

    /** Sets whether series [rule] records new airings only (Plex decides what's new), or repeats too. */
    fun setNewOnly(playlistId: Long, rule: PlexRecordingRule, newOnly: Boolean) = change(playlistId, R.string.dvr_change_failed) { client ->
        client.changeRecording(rule.key, mapOf(ONLY_NEW_AIRINGS to if (newOnly) NEW_ONLY else ALL_AIRINGS))
    }

    /** Removes [rule] (a series, or one waiting for its programme to air): the rule and every airing it set to record go. */
    fun remove(playlistId: Long, rule: PlexRecordingRule) =
        change(playlistId, R.string.dvr_cancel_failed) { client -> client.removeRecording(rule.key) }

    private fun change(playlistId: Long, @StringRes failed: Int, action: suspend (PlexClient) -> Unit) {
        scope.launch {
            val p = playlists.get(playlistId) ?: return@launch
            try {
                action(plex.client(p))
            } catch (e: PlexException) {
                notices.post(
                    when {
                        // A second rule for the same thing (only recording makes one).
                        e.code == HTTP_CONFLICT && failed == R.string.dvr_record_failed -> Notice(appString(R.string.dvr_already), null)
                        e.code == HTTP_FORBIDDEN -> Notice(appString(failed), appString(R.string.dvr_not_allowed))
                        else -> Notice(appString(failed), e.userMessage())
                    }
                )
            } catch (e: IOException) {
                notices.post(Notice(appString(failed), userMessage(e)))
            }
            refresh(playlistId)
            changed.trySend(Unit)
        }
    }

    private companion object {
        const val HTTP_CONFLICT = 409
        const val HTTP_FORBIDDEN = 403
        const val EVENTS = 8
    }
}
