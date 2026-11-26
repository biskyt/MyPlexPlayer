package com.biskyt.myplexplayer.ui.vod

import com.biskyt.myplexplayer.ui.common.ItemAction
import com.biskyt.myplexplayer.ui.common.favouriteAction
import com.biskyt.myplexplayer.ui.common.markWatched
import com.biskyt.myplexplayer.ui.player.DescribedEpisode
import com.biskyt.myplexplayer.ui.player.episodeDescriptionAction
import com.biskyt.myplexplayer.ui.player.episodeName

// What a film's and an episode's own options are, wherever they are offered: the title's page and a Continue watching
// card on Home (MyTVPlayer 1.39.0, the owner, 2026-11-25). Both places use these functions, so they offer the same.

/**
 * A film's own options, as its page's More… has them: Watched or Not watched ([watched]; [setWatched]), favourites
 * ([favourite]; null until read), then the server's details' options ([titleActions]), then Delete the film called
 * [title] ([delete]; null when the server does not allow it).
 */
@Suppress("LongParameterList") // one for each option
fun filmOwnActions(
    title: String,
    watched: Boolean,
    setWatched: (Boolean) -> Unit,
    favourite: Boolean?,
    setFavourite: (Boolean) -> Unit,
    titleActions: List<ItemAction>,
    delete: (() -> Unit)?
): List<ItemAction> = listOfNotNull(
    markWatched(watched, setWatched),
    favourite?.let { favouriteAction(it, setFavourite) },
) + titleActions + listOfNotNull(delete?.let { deleteAction(title, it) })

/**
 * An episode's own options, as its series page's More… has them: its description ([described]; [describe] opens it),
 * its own cast and crew ([showCast]), Watched, unless [finished], or Not watched, if [started] ([setWatched]), and
 * Delete ([delete]; null when the server does not allow it).
 */
@Suppress("LongParameterList") // one for each option
fun episodeOwnActions(
    described: DescribedEpisode,
    finished: Boolean,
    started: Boolean,
    describe: () -> Unit,
    showCast: () -> Unit,
    setWatched: (Boolean) -> Unit,
    delete: (() -> Unit)?
): List<ItemAction> = listOfNotNull(
    episodeDescriptionAction(described, describe),
    episodeCastAction(described.extras?.credits, showCast),
    if (!finished) markWatched(false, setWatched) else null,
    if (started) markWatched(true, setWatched) else null,
    delete?.let { deleteAction(episodeName(described.episode), it) },
)
