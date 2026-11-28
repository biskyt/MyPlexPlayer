package com.biskyt.myplexplayer.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.Text
import com.biskyt.myplexplayer.core.plex.TitleLink
import com.biskyt.myplexplayer.ui.LocalNavigator
import com.biskyt.myplexplayer.ui.theme.LocalFormFactor
import com.biskyt.myplexplayer.ui.theme.Palette
import com.biskyt.myplexplayer.ui.theme.bd
import com.biskyt.myplexplayer.ui.vod.SourceLogo
import com.biskyt.myplexplayer.ui.vod.WrapRow
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
 * here, or, where this device can't, shows it as a QR code. [modifier] (first focus) goes on link [focusOn] (the first,
 * if there are fewer), and [onLinkFocused] tells which link takes the focus.
 */
@Composable
fun BoardLinksRow(
    label: String,
    links: List<TitleLink>,
    modifier: Modifier = Modifier,
    focusOn: Int = 0,
    onLinkFocused: (Int) -> Unit = {}
) {
    val open = rememberLinkOpener()
    val focusAt = focusOn.takeIf { it in links.indices } ?: 0
    // On a phone's narrow sheet, the label goes over the links, which would otherwise squeeze it.
    if (!LocalFormFactor.current.isTv) {
        Column(Modifier.padding(horizontal = 14.bd, vertical = 4.bd), verticalArrangement = Arrangement.spacedBy(8.bd)) {
            Text(label, style = BoardOptionText.current)
            // The pills that do not fit go on the next line.
            WrapRow(8.bd) { LinkPills(links, open, modifier, focusAt, onLinkFocused) }
        }
        return
    }
    Row(
        Modifier.padding(horizontal = 14.bd, vertical = 4.bd),
        horizontalArrangement = Arrangement.spacedBy(8.bd),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = BoardOptionText.current, modifier = Modifier.weight(1f))
        LinkPills(links, open, modifier, focusAt, onLinkFocused)
    }
}

/** Each of [links] as a pill with its logo (or its name), which [open] opens; link [focusAt] gets [modifier]. */
@Composable
private fun LinkPills(links: List<TitleLink>, open: (TitleLink) -> Unit, modifier: Modifier, focusAt: Int, onLinkFocused: (Int) -> Unit) {
    links.forEachIndexed { i, link ->
        Box(Modifier.clip(PillShape).background(Palette.surfaceHigh)) {
            val focus = Modifier.onFocusChanged { if (it.isFocused) onLinkFocused(i) }
            AccentFillSurface({ open(link) }, PillShape, focus.then(if (i == focusAt) modifier else Modifier)) {
                val padding = Modifier.padding(horizontal = 14.bd, vertical = 8.bd)
                linkLogo(link.name)?.let { SourceLogo(it, link.name, padding, height = LINK_LOGO) }
                    ?: Text(link.name, style = BoardOptionText.current, color = LocalContentColor.current, modifier = padding)
            }
        }
    }
}

/**
 * An options panel (boards "PlexFilmMore" and "PlexSeriesMore"), the one every list of [ItemAction]s opens: [title]
 * ([smallTitle] for an episode's, which is longer), [subtitle] under it if given, then [actions] in order, the first
 * with focus (or the one [used] last, see [focusedAt]), then any [more] options (such as a programme's recording
 * options). A [ItemAction.Confirmed] asks its question first, with "no" focused.
 */
@Composable
fun BoardActionsPanel(
    title: String,
    subtitle: String?,
    actions: List<ItemAction>,
    onDismiss: () -> Unit,
    smallTitle: Boolean = false,
    used: MutableState<OptionPlace?>? = null,
    /** What an option that opens something closes: [onDismiss] unless Back goes back to another panel. */
    close: () -> Unit = onDismiss,
    more: (@Composable () -> Unit)? = null
) {
    val nav = LocalNavigator.current
    var asking by remember { mutableStateOf<ItemAction.Confirmed?>(null) }
    // The option to focus (see OptionFocus): the one used (back from the panel it opened, or from a question), then the
    // one the user moves to. Kept out of the panel's content, as a question takes the place of the options.
    val focus = remember { OptionFocus(used?.value) }
    val tv = LocalFormFactor.current.isTv
    // The focus is in the more options (not on a row of focus): new or renamed options do not take it back.
    val inMore = remember { mutableStateOf(false) }
    // Back on a question goes back to the options (the owner, 2026-11-26: Back goes back to the previous panel).
    BoardPanel(onDismiss = { if (asking != null) asking = null else onDismiss() }) {
        BoardPanelTitle(title, Modifier.padding(bottom = if (subtitle == null) 6.bd else 2.bd), small = smallTitle)
        subtitle?.let { BoardPanelSubtitle(it, Modifier.padding(bottom = 8.bd)) }
        val confirm = asking
        if (confirm != null) {
            ConfirmQuestion(
                confirm,
                onNo = { asking = null },
                onDismiss = close,
                question = { BoardPanelText(it, Modifier.padding(bottom = 8.bd)) }
            ) { label, modifier, yes, click -> BoardOption(label, modifier = modifier, danger = yes, onClick = click) }
            return@BoardPanel
        }
        val labels = optionLabels(actions)
        // As rememberOptionFocus does, also each time the options come back after a question.
        LaunchedEffect(labels, tv) { if (tv && !inMore.value) focus.focus(labels) {} else focus.moving = false }
        actions.forEachIndexed { i, a ->
            if (a is ItemAction.Confirmed) {
                focus.Row(labels, i) { requester, _ ->
                    BoardOption(a.label, a.help, requester, danger = true) {
                        used?.value = optionPlace(labels, i)
                        focus.asking(labels, i)
                        asking = a
                    }
                }
            } else {
                focus.ActionRow(labels, i, a) {
                    used?.value = optionPlace(labels, i)
                    a.perform(nav, close)
                }
            }
        }
        more?.let { Column(Modifier.onFocusChanged { inMore.value = it.hasFocus }, Arrangement.spacedBy(BoardPanelGap)) { it() } }
    }
}

/**
 * [confirm]'s question on its own in a panel, under [title], for a button with no options panel to ask in (a series
 * with no episodes): its safe answer (with the remote) and its yes. Either answer, or Back, closes it ([onDismiss]).
 */
@Composable
fun ConfirmPanel(title: String, confirm: ItemAction.Confirmed, onDismiss: () -> Unit) = BoardPanel(onDismiss) {
    BoardPanelTitle(title, Modifier.padding(bottom = 6.bd))
    ConfirmQuestion(
        confirm,
        onNo = onDismiss,
        onDismiss = onDismiss,
        question = { BoardPanelText(it, Modifier.padding(bottom = 8.bd)) }
    ) { label, modifier, yes, click -> BoardOption(label, modifier = modifier, danger = yes, onClick = click) }
}

private val PillShape = RoundedCornerShape(50)

/** A link's logo in its pill: as tall as the board's IMDb logo there. */
private val LINK_LOGO = 18.bd
