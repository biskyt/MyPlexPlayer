package com.biskyt.myplexplayer.ui.vod

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.biskyt.myplexplayer.R
import com.biskyt.myplexplayer.core.plex.DiscoverTitle
import com.biskyt.myplexplayer.core.tmdb.SeriesStatus
import com.biskyt.myplexplayer.core.tmdb.TmdbAiring
import com.biskyt.myplexplayer.core.tmdb.TmdbTitle
import com.biskyt.myplexplayer.ui.common.formatDate
import java.time.LocalDate

// What Plex's Discover service and TMDB say of a series as a whole, for its library page and its TMDB page. As
// MyTVPlayer's SeriesStatusLines.kt, which reads TMDB only: here Discover comes first (the owner, 2026-11-17).

/**
 * A series as a whole: whether new episodes come ([status]), its seasons (specials not counted) and episodes so far,
 * and when the next episode is first shown ([nextDate], with TMDB's [next] episode where known). Null: not known.
 */
data class SeriesStatusInfo(
    val status: SeriesStatus? = null,
    val seasonCount: Int? = null,
    val episodeCount: Int? = null,
    val nextDate: LocalDate? = null,
    val next: TmdbAiring? = null,
)

/**
 * What [discover] and [tmdb] (null without a TMDB key) say of a series. Discover's word comes first. TMDB's status is
 * finer (Cancelled, In production…), so it is used where TMDB gives one. The next episode's code comes from TMDB only:
 * Discover gives only its date, and TMDB's code goes with it only when TMDB gives the same date (else the two can be
 * about different episodes). There is no next episode for a series that has ended or was cancelled, nor one before
 * [today] (a service not yet updated). Null when neither says anything.
 */
fun seriesStatusOf(discover: DiscoverTitle?, tmdb: TmdbTitle?, today: LocalDate = LocalDate.now()): SeriesStatusInfo? {
    val status = tmdb?.status ?: discover?.continuing?.let { if (it) SeriesStatus.CONTINUING else SeriesStatus.ENDED }
    val over = status == SeriesStatus.ENDED || status == SeriesStatus.CANCELLED
    val date = (discover?.nextEpisode ?: tmdb?.nextEpisode?.airDate)?.takeIf { !over && !it.isBefore(today) }
    val info = SeriesStatusInfo(
        status = status,
        seasonCount = discover?.seasonCount ?: tmdb?.seasonCount,
        episodeCount = discover?.episodeCount ?: tmdb?.episodeCount,
        nextDate = date,
        next = tmdb?.nextEpisode?.takeIf { date != null && it.airDate == date },
    )
    return info.takeIf { it != SeriesStatusInfo() }
}

/**
 * "Continuing · 2 seasons · 18 episodes": [s]'s status and counts; null when none of them is known. Without [seasons],
 * the season count is left out (the series page shows it in its facts line, see [seasonCountText]).
 */
@Composable
fun seriesStatusLine(s: SeriesStatusInfo?, seasons: Boolean = true): String? {
    if (s == null) return null
    return listOfNotNull(
        s.status?.let { stringResource(statusName(it)) },
        s.seasonCount?.takeIf { seasons }?.let { pluralStringResource(R.plurals.vod_season_count, it, it) },
        s.episodeCount?.let { pluralStringResource(R.plurals.vod_episode_count, it, it) },
    ).joinToString(" · ").ifEmpty { null }
}

/** "Next episode: 4 Dec 2026 · S2E9", or without the code when only the date is known; null without a date. */
@Composable
fun nextEpisodeLine(s: SeriesStatusInfo?): String? {
    val date = s?.nextDate ?: return null
    val code = s.next?.let { stringResource(R.string.home_episode_code, it.season, it.number) }
    return stringResource(R.string.vod_next_episode, listOfNotNull(formatDate(date), code).joinToString(" · "))
}

/**
 * The series page's season count (the owner's ruling, 2026-11-17): the whole show's [total], then how many are on the
 * server ([onServer], specials not counted), as "6 seasons (3 available)". The count alone when the two are the same,
 * or when the server has more. When [total] is not known, the server's count ([listed], every season it lists). One
 * season: its name says it, so not "1 season" too.
 */
@Composable
fun seasonCountText(total: Int?, onServer: Int, listed: Int): String? {
    if (total != null && total > onServer) {
        val whole = pluralStringResource(R.plurals.vod_season_count, total, total)
        return pluralStringResource(R.plurals.vod_seasons_available, onServer, whole, onServer)
    }
    val shown = if (total != null) onServer else listed
    return shown.takeIf { it > 1 }?.let { pluralStringResource(R.plurals.vod_season_count, it, it) }
}

private fun statusName(s: SeriesStatus) = when (s) {
    SeriesStatus.CONTINUING -> R.string.vod_status_continuing
    SeriesStatus.ENDED -> R.string.vod_status_ended
    SeriesStatus.CANCELLED -> R.string.vod_status_cancelled
    SeriesStatus.IN_PRODUCTION -> R.string.vod_status_in_production
    SeriesStatus.PLANNED -> R.string.vod_status_planned
    SeriesStatus.PILOT -> R.string.vod_status_pilot
}
