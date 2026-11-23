package com.biskyt.myplexplayer.ui.vod

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import com.biskyt.myplexplayer.AppContainer
import com.biskyt.myplexplayer.R
import com.biskyt.myplexplayer.core.tmdb.TmdbTitle
import com.biskyt.myplexplayer.core.vod.EpisodeInfo
import com.biskyt.myplexplayer.core.vod.VodDetails
import com.biskyt.myplexplayer.data.db.CAT_MOVIE
import com.biskyt.myplexplayer.ui.LocalNavigator
import com.biskyt.myplexplayer.ui.Screen
import com.biskyt.myplexplayer.ui.common.CastPanel
import com.biskyt.myplexplayer.ui.common.ItemAction
import com.biskyt.myplexplayer.ui.common.ItemActionsPanel
import com.biskyt.myplexplayer.ui.common.Tab
import com.biskyt.myplexplayer.ui.common.rememberTrailerOpener
import com.biskyt.myplexplayer.ui.player.DescribedEpisode
import com.biskyt.myplexplayer.ui.player.EpisodeDescriptionPanel
import com.biskyt.myplexplayer.ui.player.episodeDescriptionAction

// A film's or show's More… away from its page (Home's cards): the same options and panels as on its page (MyTVPlayer
// 1.30.0's TitleExtras, the owner, 2026-11-22). Here the data is the server's first (its details, cast, trailers), and
// TMDB's only where the server has none, as on the title's own page.

/** A panel that a title's More… opens away from its page. */
sealed interface TitlePanel {
    data object Cast : TitlePanel

    data object About : TitlePanel

    /** A film's Technical info (a show has none). */
    data object Tech : TitlePanel

    data object Trailers : TitlePanel

    data class Describe(val episode: DescribedEpisode) : TitlePanel
}

/**
 * What a More… away from its page knows of film or series [id] ([type] CAT_MOVIE or CAT_SERIES): the server's details,
 * for a series its episodes too, and the trailers (the server's, then TMDB's with a key).
 */
data class TitleExtras(val details: VodDetails?, val episodes: List<EpisodeInfo>, val trailers: TitleTrailers)

/**
 * The server's details of film or series [id] on [playlistId], as its page reads them (a copy kept from its page is
 * used as it is, and its play state is not asked for again), with TMDB's trailers when the user has a TMDB key and the
 * server gives the TMDB id. Null when the server can't be reached.
 */
suspend fun AppContainer.titleExtras(playlistId: Long, type: String, id: Long): TitleExtras? = runCatching {
    val film = type == CAT_MOVIE
    val p = source(playlistId)
    val (details, episodes) = if (film) {
        vod.movieDetails(p, id, withState = false) to emptyList()
    } else {
        vod.seriesDetails(p, id, withState = false).let { it.info to it.episodes }
    }
    val tmdb: TmdbTitle? = details.tmdbId?.let { tmdb.title(series = !film, it) }
    TitleExtras(details, episodes, TitleTrailers(details.trailers, tmdb?.trailers.orEmpty()))
}.getOrNull()

/** [titleExtras], read once for a panel: null until it is known (the panel's other options show at once). */
@Composable
fun rememberTitleExtras(c: AppContainer, playlistId: Long, type: String, id: Long): TitleExtras? {
    val extras by produceState<TitleExtras?>(null, playlistId, type, id) { value = c.titleExtras(playlistId, type, id) }
    return extras
}

/**
 * What a More… away from the title's page adds, as its page offers it: the episode's description ([described]), then
 * the trailer, Other trailers, Cast and crew, More like this (with a TMDB key), About, a film's Technical info and the
 * links (see [titleActions]). [open] opens their panels (see [TitlePanels]). Film or series [id] is on [playlistId].
 */
@Composable
fun titleExtraActions(
    c: AppContainer,
    playlistId: Long,
    id: Long,
    film: Boolean,
    title: String,
    extras: TitleExtras?,
    described: DescribedEpisode?,
    open: (TitlePanel) -> Unit,
): List<ItemAction> {
    val playTrailer = rememberTrailerOpener()
    val tmdbOn by c.tmdb.on.collectAsState(initial = false)
    val episode = described?.let { d -> episodeDescriptionAction(d.episode) { open(TitlePanel.Describe(d)) } }
    extras ?: return listOfNotNull(episode)
    val trailer = trailerAction(
        extras.details?.trailer,
        { trailerScreen(playlistId, id, film, it) },
        extras.trailers.tmdbTrailer,
        playTrailer,
        film
    )
    return listOfNotNull(episode) + titleActions(
        extras.details,
        trailer,
        otherTrailers = { open(TitlePanel.Trailers) }.takeIf { extras.trailers.more },
        similar = if (tmdbOn) { tmdbId -> Screen.MoreLike(playlistId, tmdbId, series = !film, title, tabOf(film)) } else null,
        showCast = { open(TitlePanel.Cast) },
        showAbout = { open(TitlePanel.About) },
        showTech = if (film) {
            { open(TitlePanel.Tech) }
        } else {
            null
        },
    )
}

/** The panel [open] (null: none) for [title], with the server's data in [extras]. OK on a person opens their page. */
@Composable
@Suppress("LongParameterList") // the title, as the page's own panels take it
fun TitlePanels(open: TitlePanel?, playlistId: Long, id: Long, title: String, extras: TitleExtras?, film: Boolean, close: () -> Unit) {
    val nav = LocalNavigator.current
    when (open) {
        null -> Unit
        TitlePanel.Cast -> {
            val crew = remember(extras) { castAndCrew(extras?.details) }
            val acting = extras?.details?.cast?.size ?: 0
            CastPanel(title, crew, stringResource(R.string.vod_cast_crew), open = { i ->
                personScreen(playlistId, crew[i], title, tabOf(film), i < acting)?.let(nav::push)
            }, onDismiss = close)
        }
        TitlePanel.About -> AboutPanel(film, extras?.details, close)
        TitlePanel.Tech -> TechnicalInfoPanel(extras?.details, close)
        TitlePanel.Trailers -> extras?.let {
            OtherTrailersPanel(title, it.trailers, { t -> trailerScreen(playlistId, id, film, t) }, close)
        }
        is TitlePanel.Describe -> EpisodeDescriptionPanel(open.episode, close)
    }
}

/** The server's own trailer [trailer] of film or series [id], played in the app as its page plays it. */
private fun trailerScreen(playlistId: Long, id: Long, film: Boolean, trailer: Long) =
    Screen.VodPlayer(playlistId, id, if (film) null else id, trailer = trailer)

/** The top bar's tab of a film's or show's pages. */
fun tabOf(film: Boolean) = if (film) Tab.MOVIES else Tab.SERIES

/**
 * A title's options away from its page: [base] (what the card itself offers), then what its page's More… offers (see
 * [titleExtraActions]), which come in once the server answers. One of the title's panels opens in place of the
 * options; closing it closes both ([close]). Film or series [id] is on [playlistId].
 */
@Composable
@Suppress("LongParameterList") // the title, as titleExtraActions takes it
fun TitleOptions(
    c: AppContainer,
    playlistId: Long,
    id: Long,
    film: Boolean,
    title: String,
    extras: TitleExtras?,
    described: DescribedEpisode?,
    base: List<ItemAction>,
    close: () -> Unit,
) {
    var panel by remember { mutableStateOf<TitlePanel?>(null) }
    val actions = base + titleExtraActions(c, playlistId, id, film, title, extras, described) { panel = it }
    if (panel == null) ItemActionsPanel(title, actions, close)
    TitlePanels(panel, playlistId, id, title, extras, film, close)
}

/**
 * The episode that Play plays from a Continue watching card on episode [itemId] of [episodes] ([finished]: it was
 * watched to its end, so the next one, as the player picks it), or null when it is not listed.
 */
fun carryOnEpisode(episodes: List<EpisodeInfo>, itemId: Long, finished: Boolean): EpisodeInfo? {
    val index = episodes.indexOfFirst { it.id == itemId }.takeIf { it >= 0 } ?: return null
    return episodes[if (finished && index < episodes.lastIndex) index + 1 else index]
}
