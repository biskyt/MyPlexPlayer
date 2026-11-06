package com.biskyt.myplexplayer.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.em
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.biskyt.myplexplayer.core.plex.TitleLink
import com.biskyt.myplexplayer.ui.LocalNavigator
import com.biskyt.myplexplayer.ui.theme.BOLD
import com.biskyt.myplexplayer.ui.theme.BoardStyle
import com.biskyt.myplexplayer.ui.theme.EXTRA_BOLD
import com.biskyt.myplexplayer.ui.theme.LocalAccent
import com.biskyt.myplexplayer.ui.theme.MEDIUM
import com.biskyt.myplexplayer.ui.theme.Palette
import com.biskyt.myplexplayer.ui.theme.SEMI_BOLD
import com.biskyt.myplexplayer.ui.theme.bd
import com.biskyt.myplexplayer.ui.vod.SourceLogo
import com.biskyt.myplexplayer.ui.vod.linkLogo

// The side panels of the film and series pages (boards "PlexFilmMore", "PlexFilmAbout", "PlexFilmTech" and
// "PlexSeriesMore", 2026-11-05). Sizes are the boards' (see Board.kt).

/**
 * A side panel as the boards draw it: [width] wide (400 on most boards), with the boards' padding, and [content] in a
 * column [gap] apart that scrolls when it is taller than the screen.
 */
@Composable
fun BoardPanel(
    onDismiss: () -> Unit,
    width: Dp = 400.bd,
    padding: PaddingValues = PaddingValues(horizontal = 26.bd, vertical = 24.bd),
    gap: Dp = 5.bd,
    content: @Composable ColumnScope.() -> Unit
) = SidePanel(onDismiss, width = width, padding = padding) {
    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(gap), content = content)
}

/** A panel's title: 20, extra bold (18 for an episode's, which is longer). */
@Composable
fun BoardPanelTitle(text: String, modifier: Modifier = Modifier, small: Boolean = false) = Text(
    text,
    style = if (small) PanelTitleSmall.current else PanelTitle.current,
    maxLines = 2,
    overflow = TextOverflow.Ellipsis,
    modifier = modifier
)

/**
 * A board's focusable surface: transparent, and filled with the accent colour while it has focus or is pressed, with
 * no ring and no zoom. Its content colour is the text's, or [content] (Delete's red), and the accent's own while
 * filled. [onLongClick] is holding OK, or a long press.
 */
@Composable
fun AccentFillSurface(
    onClick: () -> Unit,
    shape: Shape,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    content: Color = Palette.text,
    body: @Composable BoxScope.() -> Unit
) {
    val accent = LocalAccent.current
    Surface(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = modifier.onTap(onLongClick, onClick),
        shape = ClickableSurfaceDefaults.shape(shape),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.Transparent,
            focusedContainerColor = accent.color,
            pressedContainerColor = accent.color,
            contentColor = content,
            focusedContentColor = accent.onAccent
        ),
        content = body
    )
}

/**
 * A panel's option: a rounded row that fills with the accent colour while it has focus. [help] is a smaller line under
 * it; [danger] (Delete) is in red.
 */
@Composable
fun BoardOption(label: String, help: String? = null, modifier: Modifier = Modifier, danger: Boolean = false, onClick: () -> Unit) {
    AccentFillSurface(onClick, OptionShape, modifier.fillMaxWidth(), content = if (danger) Palette.danger else Palette.text) {
        Column(Modifier.padding(horizontal = 14.bd, vertical = 8.bd), verticalArrangement = Arrangement.spacedBy(1.bd)) {
            Text(label, style = OptionText.current, color = LocalContentColor.current)
            if (help != null) Text(help, style = HelpText.current, color = Palette.textDim)
        }
    }
}

/**
 * A panel's choice changed where it is (board "PlexSeriesMore": "Delete watched episodes"): its [label], then
 * "◀ [value] ▶" on the right; left and right step it, as OK does.
 */
@Composable
fun BoardCycle(label: String, value: String, onStep: (Int) -> Unit, modifier: Modifier = Modifier) {
    AccentFillSurface({ onStep(1) }, OptionShape, modifier.fillMaxWidth().cycleKeys(onStep)) {
        Row(Modifier.padding(horizontal = 14.bd, vertical = 8.bd), horizontalArrangement = Arrangement.spacedBy(10.bd)) {
            Text(label, style = OptionText.current, color = LocalContentColor.current, modifier = Modifier.weight(1f))
            Text("◀ $value ▶", style = OptionText.current, color = LocalContentColor.current, maxLines = 1)
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
        Text(label, style = OptionText.current, modifier = Modifier.weight(1f))
        links.forEachIndexed { i, link ->
            Box(Modifier.clip(PillShape).background(Palette.surfaceHigh)) {
                AccentFillSurface({ open(link) }, PillShape, if (i == 0) modifier else Modifier) {
                    val padding = Modifier.padding(horizontal = 14.bd, vertical = 8.bd)
                    linkLogo(link.name)?.let { SourceLogo(it, link.name, padding, height = LINK_LOGO) }
                        ?: Text(link.name, style = OptionText.current, color = LocalContentColor.current, modifier = padding)
                }
            }
        }
    }
}

/**
 * The film and series pages' options panel (boards "PlexFilmMore" and "PlexSeriesMore"): [title], [subtitle] under it
 * if given, then [actions] in order, the first with focus. A [ItemAction.Confirmed] asks its question first, with "no"
 * focused, as [ItemActionsPanel] does.
 */
@Composable
fun BoardActionsPanel(title: String, subtitle: String?, actions: List<ItemAction>, onDismiss: () -> Unit) {
    val nav = LocalNavigator.current
    var asking by remember { mutableStateOf<ItemAction.Confirmed?>(null) }
    BoardPanel(onDismiss) {
        BoardPanelTitle(title, Modifier.padding(bottom = if (subtitle == null) 6.bd else 2.bd), small = subtitle == null)
        subtitle?.let { Text(it, style = PanelSubtitle.current, color = Palette.textDim, modifier = Modifier.padding(bottom = 8.bd)) }
        val confirm = asking
        if (confirm != null) {
            ConfirmQuestion(
                confirm,
                onNo = { asking = null },
                onDismiss = onDismiss,
                question = { Text(it, style = PanelSubtitle.current, modifier = Modifier.padding(bottom = 8.bd)) }
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
    }
}

/** A heading over a group of a panel's options ("This episode"): 12, extra bold, upper case, in the accent colour. */
@Composable
fun BoardSection(text: String) = Text(
    text.uppercase(),
    style = SectionText.current,
    color = LocalAccent.current.color,
    modifier = Modifier.padding(start = 14.bd, end = 14.bd, top = 8.bd)
)

/**
 * A heading over a group of facts ("Video"): 13, extra bold, upper case, in the accent colour, with the boards' 10 above
 * it and 2 below.
 */
@Composable
fun BoardFactsHeading(text: String) = Text(
    text.uppercase(),
    style = FactsHeading.current,
    color = LocalAccent.current.color,
    modifier = Modifier.padding(top = 10.bd, bottom = 2.bd)
)

/**
 * A fact: its [label] in a column [labelWidth] wide, muted, then its [value] in bold, 13 each. It takes the remote, so
 * that Up and Down scroll a long list, and shows it quietly: a dark rounded fill behind it, no ring.
 */
@Composable
fun BoardFact(label: String, value: String, labelWidth: Dp, modifier: Modifier = Modifier) {
    var focused by remember { mutableStateOf(false) }
    Row(
        modifier.onFocusChanged { focused = it.isFocused }.focusable()
            .background(if (focused) Palette.surfaceHigh else Color.Transparent, FactShape),
        horizontalArrangement = Arrangement.spacedBy(10.bd)
    ) {
        Text(label, style = FactText.current, color = Palette.textDim, modifier = Modifier.width(labelWidth))
        Text(value, style = FactValue.current)
    }
}

private val PanelTitle = BoardStyle(20, EXTRA_BOLD)
private val PanelTitleSmall = BoardStyle(18, EXTRA_BOLD)

private val OptionShape = RoundedCornerShape(10.bd)
private val FactShape = RoundedCornerShape(4.bd)
private val PillShape = RoundedCornerShape(50)

/** A link's logo in its pill: as tall as the board's IMDb logo there. */
private val LINK_LOGO = 18.bd

/** A panel's options: 15, extra bold; their help 12, semi-bold. */
private val OptionText = BoardStyle(15, EXTRA_BOLD)
private val HelpText = BoardStyle(12, SEMI_BOLD)

/** Under a panel's title ("2021 · Drama, Mystery"): 13, muted. */
private val PanelSubtitle = BoardStyle(13, MEDIUM)
private val SectionText = BoardStyle(12, EXTRA_BOLD, letterSpacing = 0.04f.em)
private val FactsHeading = BoardStyle(13, EXTRA_BOLD, letterSpacing = 0.04f.em)
private val FactText = BoardStyle(13, MEDIUM, 1.35f)
private val FactValue = BoardStyle(13, BOLD, 1.35f)
