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
import androidx.tv.material3.Text
import com.biskyt.myplexplayer.AppContainer
import com.biskyt.myplexplayer.AppText
import com.biskyt.myplexplayer.R
import com.biskyt.myplexplayer.appString
import com.biskyt.myplexplayer.core.vod.CastMember
import com.biskyt.myplexplayer.core.vod.VodDetails
import com.biskyt.myplexplayer.data.db.PlaylistEntity
import com.biskyt.myplexplayer.ui.common.ItemAction
import com.biskyt.myplexplayer.ui.common.favouriteLabel
import com.biskyt.myplexplayer.ui.common.formatRuntime
import com.biskyt.myplexplayer.ui.theme.BoardStyle
import com.biskyt.myplexplayer.ui.theme.EXTRA_BOLD
import com.biskyt.myplexplayer.ui.theme.Palette
import com.biskyt.myplexplayer.ui.theme.bd
import java.io.IOException

// MyPlexPlayer's own parts of the film and series pages (the shared ones are in DetailsParts.kt, as in MyTVPlayer).

/**
 * What a film's or show's More… shares: its cast and crew ([showCast], where known), About ([showAbout]), a film's
 * Technical info ([showTech]: a film has one, a show not) and its links in one row. A film's items have a help line
 * under them, as its board draws them.
 */
fun titleActions(details: VodDetails?, showCast: () -> Unit, showAbout: () -> Unit, showTech: (() -> Unit)? = null): List<ItemAction> {
    val film = showTech != null
    fun help(id: Int) = if (film) appString(id) else null
    val crew = details != null && (details.cast.isNotEmpty() || details.directors.isNotEmpty() || details.writers.isNotEmpty())
    return listOfNotNull(
        if (crew) ItemAction.Run(appString(R.string.vod_cast_crew), help(R.string.vod_cast_crew_help), showCast) else null,
        ItemAction.Run(
            appString(if (film) R.string.vod_about_this_film else R.string.vod_about_this_show),
            help(R.string.vod_about_film_help),
            showAbout
        ),
        showTech?.let { ItemAction.Run(appString(R.string.vod_tech_info), help(R.string.vod_tech_help), it) },
        details?.links?.takeIf { it.isNotEmpty() }?.let { ItemAction.Links(appString(R.string.vod_links), it) },
    )
}

/** "Add to favourites", or for a [favourite], "Remove from favourites". */
fun favouriteAction(favourite: Boolean, setFavourite: () -> Unit) = ItemAction.Run(favouriteLabel(favourite), run = setFavourite)

/** The cast, then who directed and wrote it (named by their job): everyone Cast and crew lists. */
fun castAndCrew(d: VodDetails?): List<CastMember> = d?.let {
    it.cast + it.directors.map { p -> p.copy(role = appString(R.string.vod_role_director)) } +
        it.writers.map { p -> p.copy(role = appString(R.string.vod_role_writer)) }
}.orEmpty()

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
