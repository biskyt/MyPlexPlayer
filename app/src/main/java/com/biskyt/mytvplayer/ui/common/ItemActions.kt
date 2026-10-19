package com.biskyt.mytvplayer.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.biskyt.mytvplayer.data.db.ChannelEntity
import com.biskyt.mytvplayer.ui.LocalNavigator
import com.biskyt.mytvplayer.ui.Navigator
import com.biskyt.mytvplayer.ui.Screen

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
    }
}

fun favouriteLabel(favourite: Boolean) = if (favourite) "Remove from favourites" else "Add to favourites"

fun favouriteFlash(added: Boolean) = if (added) "Added to favourites" else "Removed from favourites"

/** What every channel menu offers (search, the player): favourites, catch-up where the provider has it, its guide. */
fun channelActions(ch: ChannelEntity, favourite: Boolean, setFavourite: (Boolean) -> Unit): List<ItemAction> = buildList {
    add(ItemAction.Run(favouriteLabel(favourite)) { setFavourite(!favourite) })
    if (ch.archiveDays > 0) {
        add(ItemAction.Open("Catch-up", Screen.Catchup(ch.playlistId, ch.streamId), "Programmes from the last ${ch.archiveDays} days"))
    }
    add(
        ItemAction.Open("Change guide source…", Screen.GuideMatching(ch.playlistId, ch.streamId), "Pick which guide data this channel uses")
    )
}

/** A side panel of what can be done with something: [actions] in order, the first with focus. */
@Composable
fun ItemActionsPanel(title: String, actions: List<ItemAction>, onDismiss: () -> Unit) {
    val nav = LocalNavigator.current
    SidePanel(onDismiss) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 2, modifier = Modifier.padding(bottom = 8.dp))
            actions.forEachIndexed { i, a ->
                MenuOption(a.label, a.help, if (i == 0) rememberFirstFocus() else Modifier) {
                    a.perform(nav, onDismiss)
                }
            }
        }
    }
}
