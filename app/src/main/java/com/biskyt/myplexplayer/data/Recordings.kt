package com.biskyt.myplexplayer.data

import androidx.annotation.StringRes
import com.biskyt.myplexplayer.R
import com.biskyt.myplexplayer.appString
import com.biskyt.myplexplayer.core.plex.PlexClient
import com.biskyt.myplexplayer.core.plex.PlexException
import com.biskyt.myplexplayer.core.plex.PlexRecordingRule
import com.biskyt.myplexplayer.core.plex.PlexScheduledRecording
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** How a guide programme is set to record: one airing, as part of its series, or recording now. */
enum class RecordingMark { EPISODE, SERIES, RECORDING }

/**
 * What a server has set to record ([rules], and the airings [scheduled] by them), read from the server whenever a
 * screen shows it and after each change: Plex keeps it, the app only shows it (docs/plex-api.md 14, 22-24).
 */
class RecordingState(val rules: List<PlexRecordingRule> = emptyList(), val scheduled: List<PlexScheduledRecording> = emptyList()) {
    private val ruleByKey = rules.associateBy { it.key }

    /** Airings to come or recording now, by their programme's id and start (seconds). */
    private val airings = scheduled.filter { it.isScheduled || it.isRecording }
        .mapNotNull { s -> s.programme?.guid?.let { guid -> s.airing?.beginsAt?.let { (guid to it) to s } } }.toMap()

    /** The airing of programme [guid] that starts at [startMs] set to record, if it is. */
    fun airing(guid: String?, startMs: Long): PlexScheduledRecording? =
        if (airings.isEmpty() || guid == null) null else airings[guid to startMs / 1000]

    /** The rule that set [airing] to record. */
    fun ruleOf(airing: PlexScheduledRecording): PlexRecordingRule? = airing.mediaSubscriptionID?.let(ruleByKey::get)

    /** The series rule recording show [showGuid], if there is one. */
    fun seriesRule(showGuid: String?): PlexRecordingRule? =
        showGuid?.let { guid -> rules.firstOrNull { it.isSeries && it.subject?.guid == guid } }

    fun mark(guid: String?, startMs: Long): RecordingMark? = airing(guid, startMs)?.let {
        when {
            it.isRecording -> RecordingMark.RECORDING
            ruleOf(it)?.isSeries == true -> RecordingMark.SERIES
            else -> RecordingMark.EPISODE
        }
    }

    companion object {
        val NONE = RecordingState()
    }
}

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
            synchronized(applied) {
                if (run < (applied[playlistId] ?: 0)) return
                applied[playlistId] = run
                states.update { it + (playlistId to state) }
            }
        } catch (_: IOException) {
            // Shown as it was: the guide works without its marks.
        }
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

    /** Stops recording a series ([rule]): the rule and every airing it set to record go. */
    fun stopSeries(playlistId: Long, rule: PlexRecordingRule) =
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
        }
    }

    private companion object {
        const val ONLY_NEW_AIRINGS = "onlyNewAirings"
        const val NEW_ONLY = "1"
        const val HTTP_CONFLICT = 409
        const val HTTP_FORBIDDEN = 403
    }
}
