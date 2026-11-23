package com.biskyt.myplexplayer.ui.vod

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.tv.material3.Text
import com.biskyt.myplexplayer.AppContainer
import com.biskyt.myplexplayer.AppText
import com.biskyt.myplexplayer.R
import com.biskyt.myplexplayer.appString
import com.biskyt.myplexplayer.core.plex.PlexTrailer
import com.biskyt.myplexplayer.core.tmdb.TmdbVideo
import com.biskyt.myplexplayer.core.vod.CastMember
import com.biskyt.myplexplayer.core.vod.VodDetails
import com.biskyt.myplexplayer.data.db.PlaylistEntity
import com.biskyt.myplexplayer.ui.Screen
import com.biskyt.myplexplayer.ui.common.BoardActionsPanel
import com.biskyt.myplexplayer.ui.common.ItemAction
import com.biskyt.myplexplayer.ui.common.TrailersPanel
import com.biskyt.myplexplayer.ui.common.formatRuntime
import com.biskyt.myplexplayer.ui.common.trailerActions
import com.biskyt.myplexplayer.ui.theme.BoardStyle
import com.biskyt.myplexplayer.ui.theme.EXTRA_BOLD
import com.biskyt.myplexplayer.ui.theme.Palette
import com.biskyt.myplexplayer.ui.theme.bd
import java.io.IOException

// MyPlexPlayer's own parts of the film and series pages (the shared ones are in DetailsParts.kt, as in MyTVPlayer).

/**
 * "Play trailer": the server's own trailer [serverTrailer] (the screen [open] gives plays it), else TMDB's
 * [tmdbTrailer] (on YouTube or Vimeo: [openTmdb] opens its page, as the TMDB page does); null when there is neither. A film's
 * action has a help line under it ([film]), as its board draws it.
 */
fun trailerAction(
    serverTrailer: Long?,
    open: (Long) -> Screen,
    tmdbTrailer: TmdbVideo?,
    openTmdb: (String) -> Unit,
    film: Boolean
): ItemAction? {
    val label = appString(R.string.vod_play_trailer)
    val help = if (film) appString(R.string.vod_play_trailer_help) else null
    return serverTrailer?.let { ItemAction.Open(label, open(it), help) }
        ?: tmdbTrailer?.let { v -> ItemAction.Run(label, help) { openTmdb(v.url) } }
}

/**
 * A title's trailers for "Other trailers": the server's own ([own], its trailers and teasers, the one "Play trailer"
 * plays first), then TMDB's ([tmdb], best first; none without a TMDB key).
 */
data class TitleTrailers(val own: List<PlexTrailer> = emptyList(), val tmdb: List<TmdbVideo> = emptyList()) {
    /** Whether "Other trailers" is offered: only when there is more than one trailer in all. */
    val more: Boolean get() = own.size + tmdb.size > 1

    /** TMDB's best trailer, as [com.biskyt.myplexplayer.core.tmdb.TmdbTitle.trailer] gives it. */
    val tmdbTrailer: TmdbVideo? get() = tmdb.firstOrNull()
}

/**
 * "Other trailers" for [title], in place of MyTVPlayer's [TrailersPanel]: the server's own trailers first, each played
 * in the app (the screen [open] gives), then TMDB's, each opened as [TrailersPanel] opens one.
 */
@Composable
fun OtherTrailersPanel(title: String, trailers: TitleTrailers, open: (Long) -> Screen, onDismiss: () -> Unit) {
    val trailer = stringResource(R.string.vod_trailer)
    val teaser = stringResource(R.string.vod_teaser)
    val own = trailers.own.map { ItemAction.Open(it.label(trailer, teaser), open(it.id)) }
    BoardActionsPanel(title, stringResource(R.string.vod_other_trailers), own + trailerActions(trailers.tmdb), onDismiss)
}

/**
 * What a film's or show's More… shares: its [trailer] (see [trailerAction]), Other trailers ([otherTrailers] opens
 * them, given only when there is more than one, see [TitleTrailers]), More like this ([similar]
 * opens it with the title's TMDB id: given only with a TMDB key, and offered only when the server gives the id), its
 * cast and crew ([showCast], where known), About ([showAbout]), a film's Technical info ([showTech]: a film has one, a
 * show not) and its links in one row. A film's items have a help line under them, as its board draws them.
 */
@Suppress("LongParameterList") // one action for each option
fun titleActions(
    details: VodDetails?,
    trailer: ItemAction?,
    otherTrailers: (() -> Unit)?,
    similar: ((Int) -> Screen)?,
    showCast: () -> Unit,
    showAbout: () -> Unit,
    showTech: (() -> Unit)? = null
): List<ItemAction> {
    val film = showTech != null
    fun help(id: Int) = if (film) appString(id) else null
    val crew = details != null && (details.cast.isNotEmpty() || details.directors.isNotEmpty() || details.writers.isNotEmpty())
    return listOfNotNull(
        trailer,
        otherTrailers?.let { ItemAction.Run(appString(R.string.vod_other_trailers), help(R.string.vod_other_trailers_help), run = it) },
        similar?.let { open ->
            details?.tmdbId?.let { ItemAction.Open(appString(R.string.vod_similar), open(it), help(R.string.vod_similar_help)) }
        },
        if (crew) ItemAction.Run(appString(R.string.vod_cast_crew), help(R.string.vod_cast_crew_help), run = showCast) else null,
        ItemAction.Run(
            aboutLabel(film),
            help(R.string.vod_about_film_help),
            run = showAbout
        ),
        showTech?.let { ItemAction.Run(appString(R.string.vod_tech_info), help(R.string.vod_tech_help), run = it) },
        details?.links?.takeIf { it.isNotEmpty() }?.let { ItemAction.Links(appString(R.string.vod_links), it) },
    )
}

/** The cast, then who directed and wrote it (named by their job): everyone Cast and crew lists. */
fun castAndCrew(d: VodDetails?): List<CastMember> = d?.let { it.cast + crewOf(it.directors, it.writers) }.orEmpty()

/** Who [directors] directed and [writers] wrote it, named by their job, as Cast and crew lists them after the cast. */
fun crewOf(directors: List<CastMember>, writers: List<CastMember>): List<CastMember> =
    directors.map { p -> p.copy(role = appString(R.string.vod_role_director)) } +
        writers.map { p -> p.copy(role = appString(R.string.vod_role_writer)) }

/** "About this film", or for a show "About this show": the option that opens About. */
fun aboutLabel(film: Boolean) = appString(if (film) R.string.vod_about_this_film else R.string.vod_about_this_show)

/** Two genres at most: "Drama, Mystery". */
fun genresOf(genres: List<String>): String? = genres.take(2).takeIf { it.isNotEmpty() }?.joinToString(", ")

/** "★ 8.1": only where the server names no source for it (otherwise its scores show, with their logos). */
fun starRating(rating: Double?): String? = rating?.let { "★ %.1f".format(AppText.locale, it) }

/** "1 h 52 min", when the length is known. */
fun runtimeOf(seconds: Int?): String? = seconds?.takeIf { it > 0 }?.let(::formatRuntime)

/** The source [playlistId], or a failure to load (the server gone from the app meanwhile). */
suspend fun AppContainer.source(playlistId: Long): PlaylistEntity =
    playlists.get(playlistId) ?: throw IOException(appString(R.string.vod_episodes_failed))

/**
 * The picture and sound labels ("4K", "Dolby Vision", "Dolby Atmos 7.1"), 8 apart; [small] on the series page's open
 * episode, on the page's background.
 */
@Composable
fun LabelRow(labels: List<String>, modifier: Modifier = Modifier, small: Boolean = false) {
    Row(modifier.testTag("labels"), horizontalArrangement = Arrangement.spacedBy(if (small) 6.bd else 8.bd)) {
        labels.forEach {
            Text(
                it,
                style = if (small) LabelSmall.current else LabelText.current,
                maxLines = 1,
                modifier = Modifier.clip(LabelShape).background(if (small) Palette.background else Palette.surfaceHigh)
                    .padding(horizontal = if (small) 7.bd else 8.bd, vertical = if (small) 1.bd else 2.bd)
            )
        }
    }
}

private val LabelShape = RoundedCornerShape(5.bd)
private val LabelText = BoardStyle(12, EXTRA_BOLD)
private val LabelSmall = BoardStyle(11, EXTRA_BOLD)
