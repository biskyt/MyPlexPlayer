package com.biskyt.myplexplayer.core.plex

/**
 * The Skip pill for [marker]: counting down to an automatic skip ([countdownSec] whole seconds left), or waiting to be
 * pressed (null). [due]: the automatic skip's time has come (no pill: it skips now).
 */
data class SkipPill(val marker: PlexMarker, val countdownSec: Int? = null, val due: Boolean = false)

/**
 * Where playback at [ms] stands with [markers]' intros and credits, having been at [fromMs] a moment before (null: just
 * started). [delaySec] gives each marker's automatic skip delay (null: none, as for a marker already skipped or
 * cancelled). An automatic skip comes [AUTO_SKIP_AFTER_MS] after the marker's start (markers can be a little early), or
 * the delay after it if that's shorter, and its pill shows the delay before that, counting down. It's due only when
 * playback has played on to that moment: one sought into the marker gets the pill to press, as without a delay.
 */
fun skipPill(markers: List<PlexMarker>, ms: Long, fromMs: Long?, delaySec: (PlexMarker) -> Int?): SkipPill? =
    markers.firstNotNullOfOrNull { m -> if (m.type in PlexMarker.SKIPPABLE) pillFor(m, ms, fromMs, delaySec(m)) else null }

private fun pillFor(m: PlexMarker, ms: Long, fromMs: Long?, delaySec: Int?): SkipPill? {
    val playing = ms >= m.startMs && ms < m.endMs
    if (delaySec == null) return if (playing) SkipPill(m) else null
    val delayMs = delaySec * 1000L
    val skipAt = m.startMs + minOf(AUTO_SKIP_AFTER_MS, delayMs)
    val pillFrom = skipAt - delayMs
    val playedOn = fromMs != null && fromMs >= pillFrom - PLAYED_ON_MS && fromMs < skipAt
    return when {
        ms >= skipAt && playing -> SkipPill(m, due = playedOn)
        ms >= pillFrom && ms < skipAt -> SkipPill(m, countdownSec = ((skipAt - ms + 999) / 1000).toInt())
        else -> null
    }
}

/** How long after a marker's start an automatic skip waits, at most. */
const val AUTO_SKIP_AFTER_MS = 2_000L

/** How far back the last look may be for playback to count as having played on (the player looks twice a second). */
private const val PLAYED_ON_MS = 1_000L
