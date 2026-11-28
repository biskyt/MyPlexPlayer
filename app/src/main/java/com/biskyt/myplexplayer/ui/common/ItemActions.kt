package com.biskyt.myplexplayer.ui.common

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import com.biskyt.myplexplayer.R
import com.biskyt.myplexplayer.appString
import com.biskyt.myplexplayer.core.plex.TitleLink
import com.biskyt.myplexplayer.data.db.CAT_MOVIE
import com.biskyt.myplexplayer.ui.Navigator
import com.biskyt.myplexplayer.ui.Screen
import com.biskyt.myplexplayer.ui.theme.LocalFormFactor
import kotlin.math.abs

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
 * [BoardActionsPanel]. [used] keeps the place of the option used last, for a panel that one opens in this one's place:
 * back from it, this one shows again with that option focused (see [focusedAt]).
 */
@Composable
fun ItemActionsPanel(
    title: String,
    actions: List<ItemAction>,
    onDismiss: () -> Unit,
    subtitle: String? = null,
    used: MutableState<OptionPlace?>? = null,
    more: (@Composable () -> Unit)? = null
) = BoardActionsPanel(title, subtitle, actions, onDismiss, used = used, more = more)

/** The labels of an options panel's [actions], for [focusedAt]: null for a heading. */
fun optionLabels(actions: List<ItemAction>): List<String?> = actions.map { it.takeUnless { a -> a is ItemAction.Heading }?.label }

/**
 * The focus of an options panel ([ItemActionsPanel], the player's More) with [labels] (see [optionLabels]): the option
 * it follows, first the one [used] (back from the panel it opened), then the one the user moves to. Each time the
 * options change (some are read later, such as TMDB's), that option's row takes the focus: the focus stays on it, and a
 * late option does not take it. On a TV only. [reveal] brings a row on screen before it takes the focus (a lazy list's
 * row has nothing to focus until then). Each row goes in [OptionFocus.ActionRow], or [OptionFocus.Row] for a row that
 * is not an [ItemAction].
 */
@Composable
fun rememberOptionFocus(labels: List<String?>, used: OptionPlace?, reveal: suspend (Int) -> Unit = {}): OptionFocus {
    // Not rememberFirstFocus: the panel must know which focus moves are its own.
    val focus = remember { OptionFocus(used) }
    val tv = LocalFormFactor.current.isTv
    val show by rememberUpdatedState(reveal)
    LaunchedEffect(labels, tv) { if (tv) focus.focus(labels) { show(it) } else focus.moving = false }
    return focus
}

/**
 * See [rememberOptionFocus]. Its moves of the focus are its own, not the user's, until its first move: the window's own
 * first focus, as it opens, is not the user's. Not state: a move of the focus needs no new composition.
 */
class OptionFocus internal constructor(place: OptionPlace?) {
    private var place = place
        set(value) {
            field = value
            seen = true // only the user's moves and renames set it, to an option that is there
        }
    internal var moving = true

    /** Whether the option followed was in the panel: the option used can load late, after the panel opens. */
    private var seen = false

    /** The row with the focus now (-1: none), as the rows themselves tell it, whatever their kind. */
    private var focused = -1

    /** The link the user moved to last, with the place of its row of links: only that row focuses it, not the first. */
    private var link by mutableStateOf<Pair<OptionPlace, Int>?>(null)

    private val rows = mutableListOf<FocusRequester>()

    /** [followedRow]'s last answer, for the [labels][followedLabels] and [place][followedPlace] it was for. */
    private var followedLabels: List<String?>? = null
    private var followedPlace: OptionPlace? = null
    private var followed = -1

    /** The row of the option followed in [labels]: worked out once for each change, not once for each row. */
    private fun followedRow(labels: List<String?>): Int {
        if (labels !== followedLabels || place != followedPlace) {
            followed = focusedAt(labels, place)
            followedLabels = labels
            followedPlace = place
        }
        return followed
    }

    private fun requester(i: Int): FocusRequester {
        while (rows.size <= i) rows += FocusRequester()
        return rows[i]
    }

    /**
     * Row [i] of [labels]: [content] gets the modifier for what takes the focus in it, and for a row of links, which
     * link it goes on (the one the user was on, in the row followed). The row stays the same as its option changes kind
     * (an option becomes a row of links), so that it can tell that it lost the focus.
     */
    @Composable
    fun Row(labels: List<String?>, i: Int, content: @Composable (requester: Modifier, link: Int) -> Unit) {
        Box(
            Modifier.onFocusChanged {
                if (it.hasFocus) {
                    focused = i
                    if (!moving) place = optionPlace(labels, i)
                } else if (focused == i) {
                    focused = -1
                }
            }
        ) {
            val requester = Modifier.focusRequester(requester(i))
            val shown = link?.takeIf { (row, _) -> row == place && i == followedRow(labels) }?.second ?: 0
            content(requester, shown)
        }
    }

    /** Row [i] of [labels], for the option [a] (see [Row] and [ItemActionRow]), which [perform] does. */
    @Composable
    fun ActionRow(labels: List<String?>, i: Int, a: ItemAction, perform: () -> Unit) = Row(labels, i) { requester, focusOn ->
        ItemActionRow(a, requester, focusOn, { link = optionPlace(labels, i) to it }, perform)
    }

    /**
     * MyPlexPlayer's: the [ItemAction.Confirmed] at row [i] of [labels] asks its question in the options' place (see
     * BoardActionsPanel). It is followed also when a tap chose it (a tap gives no focus). Back from its question, its row
     * takes the focus again, and no focus move until then is the user's.
     */
    internal fun asking(labels: List<String?>, i: Int) {
        place = optionPlace(labels, i)
        focused = -1
        moving = true
    }

    /** Focuses the row of the option followed in [labels], once the options change; [reveal] brings it on screen. */
    internal suspend fun focus(labels: List<String?>, reveal: suspend (Int) -> Unit) {
        val i = followedRow(labels)
        try {
            if (i < 0) return
            val now = optionPlace(labels, i)
            val followed = place
            when {
                // None used: it follows the first option from now on, not an option read later above it.
                followed == null -> place = now
                followed.label in labels -> seen = true
                // Renamed in its place (Add to favourites became Remove from favourites): followed by its new label.
                // Not before it was there: until the option used loads, the row it focuses only stands in for it.
                seen && renamed(labels, followed, now) -> place = now
            }
            // Its row still has the focus: the focus stays where it is (on a row of links, perhaps not on the first).
            if (i == focused) return
            // The panel's own move. The window may not be ready for it yet as the panel opens.
            moving = true
            reveal(i)
            repeat(FOCUS_TRIES) {
                if (requester(i).tryFocus()) return
                withFrameNanos { }
            }
        } finally {
            moving = false
        }
    }
}

/** Frames to try for the first focus in a panel, while its window gets ready. */
private const val FOCUS_TRIES = 10

/**
 * Whether [now] is [followed] renamed: its label is gone, and [now] has the same entries next to it (not two headings or
 * ends, which say nothing of where it is).
 */
private fun renamed(labels: List<String?>, followed: OptionPlace, now: OptionPlace) = followed.label !in labels &&
    (followed.before != null || followed.after != null) &&
    now.before == followed.before &&
    now.after == followed.after

/**
 * An option of a panel: its place in the panel, its label, and the labels of the entries next to it ([before] and
 * [after]). A heading, or no entry at the end, is null. See [focusedAt].
 */
data class OptionPlace(val index: Int, val label: String, val before: String?, val after: String?)

/** The option at [index] of a panel's [labels] (null for a heading), for [focusedAt]. */
fun optionPlace(labels: List<String?>, index: Int) =
    OptionPlace(index, labels[index].orEmpty(), labels.getOrNull(index - 1), labels.getOrNull(index + 1))

/**
 * Which of a panel's options ([labels], null for a heading) takes the focus: the option [used], else the first. -1 means
 * no options. (Back from a panel returns to the option that opened it, the owner, 2026-11-26.)
 * - Options read later, such as TMDB's, can come in above or below it. An episode's options have two Cast and crew.
 *   So the first choice is an option with its label, with the same entries next to it, else the nearest its place.
 * - If no option has its label, its label can have changed (Add to favourites became Remove from favourites). Then the
 *   option between the same two entries takes the focus.
 * - Else the option in its place takes the focus.
 */
fun focusedAt(labels: List<String?>, used: OptionPlace?): Int {
    val first = labels.indexOfFirst { it != null }
    if (used == null) return first
    fun neighbours(i: Int) = (if (labels.getOrNull(i - 1) == used.before) 1 else 0) +
        (if (labels.getOrNull(i + 1) == used.after) 1 else 0)
    fun nearest(places: List<Int>) = places.maxWithOrNull(compareBy<Int> { neighbours(it) }.thenByDescending { abs(it - used.index) })
    val byLabel = nearest(labels.indices.filter { labels[it] == used.label })
    // Two headings or ends next to it say nothing of where it is.
    val between = if (byLabel != null || used.before == null && used.after == null) {
        null
    } else {
        nearest(labels.indices.filter { labels[it] != null && neighbours(it) == 2 })
    }
    return byLabel ?: between ?: used.index.takeIf { labels.getOrNull(it) != null } ?: first
}

/**
 * One row of an options panel, drawn the same in every one ([BoardActionsPanel], the player's More): a heading, a row of
 * links ([modifier] on its link [link]; [onLinkFocused] tells which link the user moves to), a choice changed where it
 * is, or an option with its help line and icon, which [perform] does. A [ItemAction.Confirmed] is asked first by its
 * panel, which draws it.
 */
@Composable
fun ItemActionRow(a: ItemAction, modifier: Modifier = Modifier, link: Int = 0, onLinkFocused: (Int) -> Unit = {}, perform: () -> Unit) =
    when (a) {
        is ItemAction.Heading -> BoardSection(a.label)
        is ItemAction.Links -> BoardLinksRow(a.label, a.links, modifier, link, onLinkFocused)
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
