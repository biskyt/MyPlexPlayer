package com.biskyt.myplexplayer.ui.player

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.biskyt.myplexplayer.R
import com.biskyt.myplexplayer.appString
import com.biskyt.myplexplayer.core.vod.EpisodeInfo
import com.biskyt.myplexplayer.data.EpisodeExtras
import com.biskyt.myplexplayer.data.EpisodeFacts
import com.biskyt.myplexplayer.data.TmdbRepository
import com.biskyt.myplexplayer.data.episodeExtras
import com.biskyt.myplexplayer.ui.common.BoardPanel
import com.biskyt.myplexplayer.ui.common.BoardPanelGap
import com.biskyt.myplexplayer.ui.common.BoardPanelSubtitle
import com.biskyt.myplexplayer.ui.common.BoardPanelText
import com.biskyt.myplexplayer.ui.common.BoardPanelTitle
import com.biskyt.myplexplayer.ui.common.ItemAction
import com.biskyt.myplexplayer.ui.common.formatDate
import com.biskyt.myplexplayer.ui.common.rememberFirstFocus
import com.biskyt.myplexplayer.ui.theme.bd
import com.biskyt.myplexplayer.ui.vod.ScoresRow
import com.biskyt.myplexplayer.ui.vod.filledFrom
import com.biskyt.myplexplayer.ui.vod.people
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/**
 * An episode, and the show it is in (null when not known), as its description tells of them; with its [extras] (its
 * scores and who directed and wrote it: the server's, else TMDB's) once known.
 */
data class DescribedEpisode(val show: String?, val episode: EpisodeInfo, val extras: EpisodeExtras? = null)

/**
 * Episode [e] of [show] as its description tells of it, the same on the series page and over the player (the owner,
 * 2026-11-24: one way for both): the server's words, date, scores and people ([server]: its full episode; null when it
 * did not answer), with TMDB's (with a key, for the show with TMDB id [tmdbId]) only where the server has none. TMDB
 * is asked only for what is missing: the season for a missing description or score, the credits for missing people.
 */
suspend fun TmdbRepository.describe(show: String?, e: EpisodeInfo, server: EpisodeFacts?, tmdbId: Int?): DescribedEpisode {
    val s = server ?: EpisodeFacts()
    val needsSeason = tmdbId != null && (s.scores.isEmpty() || e.plot.isNullOrBlank())
    // The two TMDB requests do not depend on each other: both go at once.
    return coroutineScope {
        val season = async { if (needsSeason) season(tmdbId, e.season)?.firstOrNull { it.number == e.number } else null }
        val credits = async { if (tmdbId != null && s.missesPeople) episodeCredits(tmdbId, e.season, e.number) else null }
        val t = season.await()
        DescribedEpisode(show, e.filledFrom(t), episodeExtras(server, t?.score, credits.await()))
    }
}

/**
 * The "Episode description" item that opens [d]'s description with [open]; null when there is nothing to show (no
 * description, no date, no score and no director or writer).
 */
fun episodeDescriptionAction(d: DescribedEpisode, open: () -> Unit): ItemAction? {
    val e = d.episode
    val crew = d.extras?.credits?.let { it.directors.isNotEmpty() || it.writers.isNotEmpty() } == true
    val shown = e.plot != null || e.airDate != null || d.extras?.scores?.isNotEmpty() == true || crew
    return if (shown) ItemAction.Run(appString(R.string.player_description), run = open) else null
}

/**
 * An episode's description, the same on the series page and over the player (and in MyTVPlayer): the show, the
 * episode's name, when it was first shown, its scores, who directed and wrote it, and what it is about. A line is left
 * out when neither the server nor TMDB says.
 */
@Composable
fun EpisodeDescription(d: DescribedEpisode, modifier: Modifier = Modifier) {
    val e = d.episode
    val credits = d.extras?.credits
    Column(modifier, verticalArrangement = Arrangement.spacedBy(BoardPanelGap)) {
        d.show?.takeIf { it.isNotBlank() }?.let { BoardPanelSubtitle(it) }
        BoardPanelTitle(episodeName(e), small = true)
        e.airDate?.let { BoardPanelSubtitle(stringResource(R.string.vod_first_shown, formatDate(it))) }
        d.extras?.scores?.let { ScoresRow(it) }
        credits?.let { people(it.directors, it.writers) }?.let { BoardPanelSubtitle(it) }
        // At most what fits in a side panel: its text doesn't scroll (the remote has nothing in it to move to).
        e.plot?.let {
            Spacer(Modifier.height(4.bd))
            BoardPanelText(it, maxLines = PLOT_LINES)
        }
    }
}

/** [EpisodeDescription] in a side panel (the series page's), focused so that Back reaches the panel and closes it. */
@Composable
fun EpisodeDescriptionPanel(d: DescribedEpisode, onDismiss: () -> Unit) {
    BoardPanel(onDismiss, dim = false, scroll = false) {
        EpisodeDescription(d, rememberFirstFocus().focusable())
    }
}

/** What fits in a side panel (here, and a film's or series' plot): its text does not scroll. */
internal const val PLOT_LINES = 18
