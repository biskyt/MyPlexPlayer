package com.biskyt.myplexplayer.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import com.biskyt.myplexplayer.R
import com.biskyt.myplexplayer.appString
import com.biskyt.myplexplayer.core.plex.TitleLink
import com.biskyt.myplexplayer.data.db.CAT_MOVIE
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

    /** Does [run]; [icon] shows after the label (the favourite's heart). */
    class Run(
        override val label: String,
        override val help: String? = null,
        val icon: ImageVector? = null,
        val run: () -> Unit
    ) : ItemAction

    /** A choice changed where it is: left and right step through it ([step] gets -1 or 1), shown as "◀ [value] ▶". */
    class Cycle(override val label: String, val value: String, val step: (Int) -> Unit) : ItemAction {
        override val help: String? = null
    }

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

/**
 * "Add to favourites", or for a [favourite] "Remove from favourites", with the heart (filled for a favourite) as every
 * menu shows it (the owner, 2026-11-22). [setFavourite] gets what it is to be.
 */
fun favouriteAction(favourite: Boolean, setFavourite: (Boolean) -> Unit) =
    ItemAction.Run(favouriteLabel(favourite), icon = favouriteIcon(favourite)) { setFavourite(!favourite) }

fun favouriteIcon(favourite: Boolean) = if (favourite) AppIcons.Heart else AppIcons.HeartOutline

fun favouriteFlash(added: Boolean) = appString(if (added) R.string.common_favourite_added else R.string.common_favourite_removed)

/** What every channel menu offers (search, the player): add it to favourites or take it off. */
fun channelActions(favourite: Boolean, setFavourite: (Boolean) -> Unit): List<ItemAction> = listOf(favouriteAction(favourite, setFavourite))

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
        favourite?.let { f -> favouriteAction(f, setFavourite) }
    )

/** "Mark as watched", or for one [watched], "Mark as unwatched". */
fun markWatched(watched: Boolean, setWatched: (Boolean) -> Unit) =
    ItemAction.Run(appString(if (watched) R.string.common_mark_unwatched else R.string.common_mark_watched)) { setWatched(!watched) }

/** A movie's or series' page ([type] is CAT_MOVIE or CAT_SERIES). */
fun vodDetails(playlistId: Long, type: String, id: Long): Screen =
    if (type == CAT_MOVIE) Screen.MovieDetails(playlistId, id) else Screen.SeriesDetails(playlistId, id)

/**
 * A side panel of what can be done with something, with [subtitle] under its [title] if given (as MyTVPlayer's): see
 * [BoardActionsPanel].
 */
@Composable
fun ItemActionsPanel(
    title: String,
    actions: List<ItemAction>,
    onDismiss: () -> Unit,
    subtitle: String? = null,
    more: (@Composable () -> Unit)? = null
) = BoardActionsPanel(title, subtitle, actions, onDismiss, more = more)

/**
 * One row of an options panel, drawn the same in every one ([BoardActionsPanel], the player's More): a heading, a row of
 * links, a choice changed where it is, or an option with its help line and icon, which [perform] does. A
 * [ItemAction.Confirmed] is asked first by its panel, which draws it.
 */
@Composable
fun ItemActionRow(a: ItemAction, modifier: Modifier = Modifier, perform: () -> Unit) = when (a) {
    is ItemAction.Heading -> BoardSection(a.label)
    is ItemAction.Links -> BoardLinksRow(a.label, a.links, modifier)
    is ItemAction.Cycle -> BoardCycle(a.label, a.value, a.step, modifier)
    else -> BoardOption(a.label, a.help, modifier, icon = (a as? ItemAction.Run)?.icon, onClick = perform)
}

/**
 * [confirm]'s [question], then its "no" (focused, [onNo]) and its yes ([onDismiss], then it is done), each drawn by
 * [option] (given whether it is the yes): what every options panel asks before a [ItemAction.Confirmed].
 */
@Composable
fun ConfirmQuestion(
    confirm: ItemAction.Confirmed,
    onNo: () -> Unit,
    onDismiss: () -> Unit,
    question: @Composable (String) -> Unit,
    option: @Composable (label: String, modifier: Modifier, yes: Boolean, onClick: () -> Unit) -> Unit
) {
    question(confirm.question)
    option(stringResource(R.string.vod_delete_no), rememberFirstFocus(confirm), false, onNo)
    option(confirm.yes, Modifier, true) {
        onDismiss()
        confirm.run()
    }
}
