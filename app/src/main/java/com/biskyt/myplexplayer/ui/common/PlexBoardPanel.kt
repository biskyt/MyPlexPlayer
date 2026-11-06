package com.biskyt.myplexplayer.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.Text
import com.biskyt.myplexplayer.core.plex.TitleLink
import com.biskyt.myplexplayer.ui.LocalNavigator
import com.biskyt.myplexplayer.ui.theme.Palette
import com.biskyt.myplexplayer.ui.theme.bd
import com.biskyt.myplexplayer.ui.vod.SourceLogo
import com.biskyt.myplexplayer.ui.vod.linkLogo

// The Plex-only parts of the film and series pages' panels (boards "PlexFilmMore" and "PlexSeriesMore"), made of the
// shared parts in BoardPanel.kt. That file stays as MyTVPlayer has it, so that ports apply.

/**
 * A panel's choice changed where it is (board "PlexSeriesMore": "Delete watched episodes"): its [label], then
 * "◀ [value] ▶" on the right; left and right step it, as OK does.
 */
@Composable
fun BoardCycle(label: String, value: String, onStep: (Int) -> Unit, modifier: Modifier = Modifier) {
    BoardRow({ onStep(1) }, modifier.cycleKeys(onStep)) {
        Row(Modifier.padding(horizontal = 14.bd, vertical = 8.bd), horizontalArrangement = Arrangement.spacedBy(10.bd)) {
            Text(label, style = BoardOptionText.current, color = LocalContentColor.current, modifier = Modifier.weight(1f))
            Text("◀ $value ▶", style = BoardOptionText.current, color = LocalContentColor.current, maxLines = 1)
        }
    }
}

/**
 * "Links", then a pill for each of [links] (its site's logo, or its name), as the boards draw them: each opens its page
 * here, or, where this device can't, shows it as a QR code. [modifier] (first focus) goes on the first.
 */
@Composable
fun BoardLinksRow(label: String, links: List<TitleLink>, modifier: Modifier = Modifier) {
    val open = rememberLinkOpener()
    Row(
        Modifier.padding(horizontal = 14.bd, vertical = 4.bd),
        horizontalArrangement = Arrangement.spacedBy(8.bd),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = BoardOptionText.current, modifier = Modifier.weight(1f))
        links.forEachIndexed { i, link ->
            Box(Modifier.clip(PillShape).background(Palette.surfaceHigh)) {
                AccentFillSurface({ open(link) }, PillShape, if (i == 0) modifier else Modifier) {
                    val padding = Modifier.padding(horizontal = 14.bd, vertical = 8.bd)
                    linkLogo(link.name)?.let { SourceLogo(it, link.name, padding, height = LINK_LOGO) }
                        ?: Text(link.name, style = BoardOptionText.current, color = LocalContentColor.current, modifier = padding)
                }
            }
        }
    }
}

/**
 * An options panel (boards "PlexFilmMore" and "PlexSeriesMore"), the one every list of [ItemAction]s opens: [title]
 * ([smallTitle] for an episode's, which is longer), [subtitle] under it if given, then [actions] in order, the first
 * with focus, then any [more] options (such as a programme's recording options). A [ItemAction.Confirmed] asks its
 * question first, with "no" focused.
 */
@Composable
fun BoardActionsPanel(
    title: String,
    subtitle: String?,
    actions: List<ItemAction>,
    onDismiss: () -> Unit,
    smallTitle: Boolean = false,
    more: (@Composable () -> Unit)? = null
) {
    val nav = LocalNavigator.current
    var asking by remember { mutableStateOf<ItemAction.Confirmed?>(null) }
    BoardPanel(onDismiss) {
        BoardPanelTitle(title, Modifier.padding(bottom = if (subtitle == null) 6.bd else 2.bd), small = smallTitle)
        subtitle?.let { BoardPanelSubtitle(it, Modifier.padding(bottom = 8.bd)) }
        val confirm = asking
        if (confirm != null) {
            ConfirmQuestion(
                confirm,
                onNo = { asking = null },
                onDismiss = onDismiss,
                question = { BoardPanelText(it, Modifier.padding(bottom = 8.bd)) }
            ) { label, modifier, yes, click -> BoardOption(label, modifier = modifier, danger = yes, onClick = click) }
            return@BoardPanel
        }
        val first = actions.indexOfFirst { it !is ItemAction.Heading }
        actions.forEachIndexed { i, a ->
            val focus = if (i == first) rememberFirstFocus() else Modifier
            when (a) {
                is ItemAction.Heading -> BoardSection(a.label)
                is ItemAction.Links -> BoardLinksRow(a.label, a.links, focus)
                is ItemAction.Cycle -> BoardCycle(a.label, a.value, a.step, focus)
                is ItemAction.Confirmed -> BoardOption(a.label, a.help, focus, danger = true) { asking = a }
                else -> BoardOption(a.label, a.help, focus) { a.perform(nav, onDismiss) }
            }
        }
        more?.invoke()
    }
}

private val PillShape = RoundedCornerShape(50)

/** A link's logo in its pill: as tall as the board's IMDb logo there. */
private val LINK_LOGO = 18.bd
