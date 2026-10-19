package com.biskyt.mytvplayer.ui.common

import com.biskyt.mytvplayer.data.db.ChannelEntity
import com.biskyt.mytvplayer.ui.Screen

/** Something an options menu offers for an item: open a screen, or do something there and then. */
sealed interface ItemAction {
    val label: String
    val help: String?

    data class Open(override val label: String, val screen: Screen, override val help: String? = null) : ItemAction

    class Run(override val label: String, override val help: String? = null, val run: () -> Unit) : ItemAction
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
