package com.biskyt.myplexplayer.ui.player

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.biskyt.myplexplayer.R
import com.biskyt.myplexplayer.appString
import com.biskyt.myplexplayer.core.vod.EpisodeInfo
import com.biskyt.myplexplayer.ui.common.BoardPanel
import com.biskyt.myplexplayer.ui.common.BoardPanelSubtitle
import com.biskyt.myplexplayer.ui.common.BoardPanelText
import com.biskyt.myplexplayer.ui.common.BoardPanelTitle
import com.biskyt.myplexplayer.ui.common.ItemAction
import com.biskyt.myplexplayer.ui.common.formatDate
import com.biskyt.myplexplayer.ui.common.rememberFirstFocus
import com.biskyt.myplexplayer.ui.theme.bd

/** An episode, and the show it is in (null when not known), as its description tells of them. */
data class DescribedEpisode(val show: String?, val episode: EpisodeInfo)

/**
 * The "Episode description" item that opens [e]'s description with [open]; null when there is nothing to show (no
 * description and no date).
 */
fun episodeDescriptionAction(e: EpisodeInfo, open: () -> Unit): ItemAction? =
    if (e.plot != null || e.airDate != null) ItemAction.Run(appString(R.string.player_description), run = open) else null

/**
 * An episode's description, the same on the series page and over the player (and in MyPlexPlayer): the show, the
 * episode's name, when it was first shown and what it is about. A line is left out when the provider doesn't say.
 */
@Composable
fun EpisodeDescription(d: DescribedEpisode, modifier: Modifier = Modifier) {
    val e = d.episode
    Column(modifier, verticalArrangement = Arrangement.spacedBy(5.bd)) {
        d.show?.takeIf { it.isNotBlank() }?.let { BoardPanelSubtitle(it) }
        BoardPanelTitle(episodeName(e), small = true)
        e.airDate?.let { BoardPanelSubtitle(stringResource(R.string.vod_first_shown, formatDate(it)), Modifier.padding(bottom = 4.bd)) }
        // At most what fits in a side panel: its text doesn't scroll (the remote has nothing in it to move to).
        e.plot?.let { BoardPanelText(it, maxLines = PLOT_LINES) }
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
