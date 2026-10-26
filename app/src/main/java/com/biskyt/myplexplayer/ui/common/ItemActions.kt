package com.biskyt.myplexplayer.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.biskyt.myplexplayer.R
import com.biskyt.myplexplayer.appString
import com.biskyt.myplexplayer.core.plex.TitleLink
import com.biskyt.myplexplayer.core.vod.VodDetails
import com.biskyt.myplexplayer.data.db.CAT_MOVIE
import com.biskyt.myplexplayer.ui.LocalNavigator
import com.biskyt.myplexplayer.ui.Navigator
import com.biskyt.myplexplayer.ui.Screen

/** Something an options menu offers for an item: open a screen, or do something there and then. */
sealed interface ItemAction {
    val label: String
    val help: String?

    /** Opens [screen] on top, or in place of this one when [replace] (e.g. leaving a movie for its details). */
    data class Open(
        override val label: String,
        val screen: Screen,
        override val help: String? = null,
        val replace: Boolean = false
    ) : ItemAction

    class Run(override val label: String, override val help: String? = null, val run: () -> Unit) : ItemAction

    /** A choice changed where it is: left and right step through it ([step] gets -1 or 1), shown as "◀ [value] ▶". */
    class Cycle(override val label: String, override val help: String?, val value: String, val step: (Int) -> Unit) : ItemAction

    /** A heading over the options after it ("This episode", "The show"); not an option itself. */
    class Heading(override val label: String) : ItemAction {
        override val help: String? = null
    }

    /** Pages about the title elsewhere, side by side in one row: left and right move between them, OK opens one. */
    class Links(override val label: String, val links: List<TitleLink>) : ItemAction {
        override val help: String? = null
    }

    /** Done only once confirmed: [ItemActionsPanel] asks [question] first, with [yes] to go ahead. */
    class Confirmed(override val label: String, val question: String, val yes: String, val run: () -> Unit) : ItemAction {
        override val help: String? = null
    }
}

/** Does [this] from a menu: an Open closes the menu ([close]) first, and calls [beforeReplace] before replacing. */
fun ItemAction.perform(nav: Navigator, close: () -> Unit, beforeReplace: () -> Unit = {}) {
    when (this) {
        is ItemAction.Open -> {
            close()
            if (replace) {
                beforeReplace()
                nav.replace(screen)
            } else {
                nav.push(screen)
            }
        }
        is ItemAction.Run -> run()
        is ItemAction.Cycle -> step(1)
        is ItemAction.Confirmed, is ItemAction.Heading, is ItemAction.Links -> error("Only ItemActionsPanel offers $label")
    }
}

fun favouriteLabel(favourite: Boolean) = appString(if (favourite) R.string.common_favourite_remove else R.string.common_favourite_add)

fun favouriteFlash(added: Boolean) = appString(if (added) R.string.common_favourite_added else R.string.common_favourite_removed)

/** What every channel menu offers (search, the player): add it to favourites or take it off. */
fun channelActions(favourite: Boolean, setFavourite: (Boolean) -> Unit): List<ItemAction> =
    listOf(ItemAction.Run(favouriteLabel(favourite)) { setFavourite(!favourite) })

/**
 * What a movie or series menu offers (search, Home): open it, and add or remove it from favourites once that's known
 * ([favourite] null until read: "Add" for a favourite would undo it).
 */
fun vodActions(playlistId: Long, type: String, id: Long, favourite: Boolean?, setFavourite: (Boolean) -> Unit): List<ItemAction> =
    listOfNotNull(
        ItemAction.Open(
            appString(if (type == CAT_MOVIE) R.string.search_open_movie else R.string.search_open_series),
            vodDetails(playlistId, type, id)
        ),
        favourite?.let { f -> ItemAction.Run(favouriteLabel(f)) { setFavourite(!f) } }
    )

/**
 * What a film's or show's options share wherever it's open (its page, the player): its cast ([showCast], where known),
 * More info, its links in one row, and favourites.
 */
@Suppress("LongParameterList") // the title, what's known of it, and what its options do
fun titleActions(
    playlistId: Long,
    type: String,
    id: Long,
    details: VodDetails?,
    favourite: Boolean,
    showCast: () -> Unit,
    setFavourite: () -> Unit
): List<ItemAction> = listOfNotNull(
    details?.cast?.takeIf { it.isNotEmpty() }?.let { ItemAction.Run(appString(R.string.vod_cast_title), run = showCast) },
    ItemAction.Open(appString(R.string.vod_more_info), Screen.MoreInfo(playlistId, type, id)),
    details?.links?.takeIf { it.isNotEmpty() }?.let { ItemAction.Links(appString(R.string.vod_links), it) },
    ItemAction.Run(favouriteLabel(favourite), run = setFavourite)
)

/** "Mark as watched", or for one [watched], "Mark as unwatched". */
fun markWatched(watched: Boolean, setWatched: (Boolean) -> Unit) =
    ItemAction.Run(appString(if (watched) R.string.common_mark_unwatched else R.string.common_mark_watched)) { setWatched(!watched) }

/** A movie's or series' page ([type] is CAT_MOVIE or CAT_SERIES). */
fun vodDetails(playlistId: Long, type: String, id: Long): Screen =
    if (type == CAT_MOVIE) Screen.MovieDetails(playlistId, id) else Screen.SeriesDetails(playlistId, id)

/**
 * A side panel of what can be done with something: [actions] in order, the first with focus. A [ItemAction.Confirmed]
 * asks its question in the panel first, with "no" focused.
 */
@Composable
fun ItemActionsPanel(title: String, actions: List<ItemAction>, onDismiss: () -> Unit) {
    val nav = LocalNavigator.current
    var asking by remember { mutableStateOf<ItemAction.Confirmed?>(null) }
    SidePanel(onDismiss) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.verticalScroll(rememberScrollState())) {
            Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 2, modifier = Modifier.padding(bottom = 8.dp))
            val confirm = asking
            if (confirm != null) {
                Text(confirm.question, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(bottom = 8.dp))
                MenuOption(stringResource(R.string.vod_delete_no), modifier = rememberFirstFocus(confirm)) { asking = null }
                MenuOption(confirm.yes) {
                    onDismiss()
                    confirm.run()
                }
                return@Column
            }
            val first = actions.indexOfFirst { it !is ItemAction.Heading }
            actions.forEachIndexed { i, a ->
                val focus = if (i == first) rememberFirstFocus() else Modifier
                when (a) {
                    is ItemAction.Heading -> SectionLabel(a.label)
                    is ItemAction.Links -> LinksRow(a.label, a.links, focus)
                    is ItemAction.Cycle -> CycleSettingRow(a.label, a.help, a.value, a.step, focus)
                    is ItemAction.Confirmed -> MenuOption(a.label, a.help, focus) { asking = a }
                    else -> MenuOption(a.label, a.help, focus) { a.perform(nav, onDismiss) }
                }
            }
        }
    }
}
