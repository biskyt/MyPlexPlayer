package com.biskyt.myplexplayer.ui.vod

import android.content.Context
import android.text.format.Formatter
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import com.biskyt.myplexplayer.AppText
import com.biskyt.myplexplayer.R
import com.biskyt.myplexplayer.appString
import com.biskyt.myplexplayer.core.plex.PlexMediaInfo
import com.biskyt.myplexplayer.core.vod.VodDetails
import com.biskyt.myplexplayer.data.airDateOf
import com.biskyt.myplexplayer.ui.common.BoardFact
import com.biskyt.myplexplayer.ui.common.BoardFactsHeading
import com.biskyt.myplexplayer.ui.common.BoardPanel
import com.biskyt.myplexplayer.ui.common.BoardPanelTitle
import com.biskyt.myplexplayer.ui.common.ErrorRetry
import com.biskyt.myplexplayer.ui.common.Muted
import com.biskyt.myplexplayer.ui.common.formatDate
import com.biskyt.myplexplayer.ui.common.rememberFirstFocus
import com.biskyt.myplexplayer.ui.player.frameRate
import com.biskyt.myplexplayer.ui.player.pictureSize
import com.biskyt.myplexplayer.ui.theme.bd
import kotlin.coroutines.cancellation.CancellationException

// The film's More… › About this film and Technical info (boards "PlexFilmAbout" and "PlexFilmTech"), and a show's
// About this show. Each is read from the details its page already has, so they ask the server for nothing; when a
// film's page has none (the server was out of reach), the panel asks for them, with Retry if that fails too. Each fact
// takes the remote in turn, so that a long list scrolls.

/**
 * About this film (or this show, when not [film]): its original title, when it was released and added, its studio,
 * countries, collections and producers. A line with no data is left out. Without [details], they are read with [load].
 */
@Composable
fun AboutPanel(film: Boolean, details: VodDetails?, onDismiss: () -> Unit, load: (suspend () -> VodDetails)? = null) {
    BoardPanel(onDismiss) {
        BoardPanelTitle(
            stringResource(if (film) R.string.vod_about_this_film else R.string.vod_about_this_show),
            Modifier.padding(bottom = 3.bd)
        )
        Loaded(details, load) { d ->
            remember(d) { aboutFacts(d) }.forEachIndexed { i, (label, value) -> BoardFact(label, value, ABOUT_LABEL, first(i == 0)) }
        }
    }
}

/**
 * A film's Technical info: its file (container, size and bitrate), its picture (codec, size and frame rate, high dynamic
 * range), each soundtrack and each subtitle track (forced ones marked). A line with no data is left out.
 */
@Composable
fun TechnicalInfoPanel(details: VodDetails?, onDismiss: () -> Unit, load: (suspend () -> VodDetails)? = null) {
    BoardPanel(onDismiss, width = 380.bd, padding = PaddingValues(28.bd), gap = 6.bd) {
        BoardPanelTitle(stringResource(R.string.vod_tech_info), Modifier.padding(bottom = 4.bd))
        val context = LocalContext.current
        Loaded(details, load) { d ->
            val m = d.media ?: return@Loaded
            val groups = remember(m) { technicalInfo(context, m).filter { it.second.isNotEmpty() } }
            groups.forEachIndexed { g, (heading, facts) ->
                BoardFactsHeading(heading)
                facts.forEachIndexed { i, (label, value) -> BoardFact(label, value, TECH_LABEL, first(g == 0 && i == 0)) }
            }
        }
    }
}

/**
 * The remote on the first fact, so that Up and Down move through them and the panel scrolls with it (a panel with no
 * focusable element can't be scrolled with a remote).
 */
@Composable
private fun first(isFirst: Boolean): Modifier = if (isFirst) rememberFirstFocus() else Modifier

/**
 * [content] once the details are known: [given] (its page's), else read with [load] ("Loading…" meanwhile), and the
 * error with Retry when that fails, whatever the failure.
 */
@Composable
private fun ColumnScope.Loaded(
    given: VodDetails?,
    load: (suspend () -> VodDetails)?,
    content: @Composable ColumnScope.(VodDetails) -> Unit
) {
    var attempt by remember { mutableIntStateOf(0) }
    val result by produceState(given?.let { Result.success(it) }, attempt) {
        if (given == null && load != null) {
            value = null
            value = try {
                Result.success(load())
            } catch (e: CancellationException) {
                throw e
            } catch (@Suppress("TooGenericExceptionCaught") e: Exception) { // any failure is shown, with Retry
                Result.failure(e)
            }
        }
    }
    val r = result
    if (r == null) {
        Muted(stringResource(R.string.common_loading))
        return
    }
    r.onSuccess { content(it) }
    r.onFailure { e -> ErrorRetry(e.message ?: stringResource(R.string.vod_episodes_failed)) { attempt++ } }
}

/** About's lines, in the board's order: those with a value only. */
fun aboutFacts(d: VodDetails): List<Pair<String, String>> = listOf(
    appString(R.string.vod_original_title) to d.originalTitle,
    appString(R.string.vod_released) to d.released?.let(::airDateOf)?.let(::formatDate),
    appString(R.string.vod_added) to d.addedAt?.let { formatDate(it * MS_PER_S) },
    appString(R.string.vod_studio) to d.studio,
    appString(R.string.vod_countries) to d.countries.list(),
    appString(R.string.vod_collections) to d.collections.list(),
    appString(R.string.vod_producers) to d.producers.list(),
).mapNotNull { (label, value) -> value?.takeIf { it.isNotBlank() }?.let { label to it } }

/** Technical info's groups (File, Video, Audio, Subtitles), each with its lines that have a value. */
fun technicalInfo(context: Context, m: PlexMediaInfo): List<Pair<String, List<Pair<String, String>>>> {
    val v = m.video
    return listOf(
        appString(R.string.vod_tech_file) to listOf(
            appString(R.string.vod_container) to m.container,
            appString(R.string.vod_size) to m.sizeBytes?.let { Formatter.formatFileSize(context, it) },
            appString(R.string.vod_tech_bitrate) to m.bitrateKbps?.let(::bitrate),
        ),
        appString(R.string.vod_tech_video) to listOf(
            appString(R.string.vod_tech_codec) to v?.codec,
            appString(R.string.vod_picture) to listOfNotNull(
                v?.let { pictureSize(it.width ?: 0, it.height ?: 0) },
                v?.frameRate?.let { frameRate(it.toFloat()) }
            ).joinToString(" · "),
            appString(R.string.vod_tech_hdr) to v?.dynamicRange?.joinToString(", "),
        ),
        appString(R.string.vod_tech_audio) to m.audioTracks.mapIndexed { i, a ->
            val format = listOfNotNull(a.format, a.layout).joinToString(" ").ifEmpty { null }
            trackName(a.language, i) to listOfNotNull(format, a.bitrateKbps?.let(::bitrate)).joinToString(" · ")
        },
        appString(R.string.vod_subtitles) to m.subtitleTracks.mapIndexed { i, s ->
            trackName(s.language, i) to
                listOfNotNull(s.format, if (s.forced) appString(R.string.vod_tech_forced) else null).joinToString(" · ")
        },
    ).map { (heading, facts) -> heading to facts.mapNotNull { (label, value) -> value?.takeIf { it.isNotBlank() }?.let { label to it } } }
}

/** A track's language as the server names it, else "Track 2". */
private fun trackName(language: String?, index: Int) = language ?: appString(R.string.vod_tech_track, index + 1)

/** "22.1 Mbit/s", or under a megabit "640 kbit/s" (units, the same in every language). */
private fun bitrate(kbps: Int): String =
    if (kbps < KBPS_PER_MBPS) "$kbps kbit/s" else "%.1f Mbit/s".format(AppText.locale, kbps / KBPS_PER_MBPS.toDouble())

private fun List<String>.list() = takeIf { it.isNotEmpty() }?.joinToString(", ")

/** The boards' label columns: About's 104, Technical info's 92. */
private val ABOUT_LABEL: Dp = 104.bd
private val TECH_LABEL: Dp = 92.bd

private const val MS_PER_S = 1000L
private const val KBPS_PER_MBPS = 1000
