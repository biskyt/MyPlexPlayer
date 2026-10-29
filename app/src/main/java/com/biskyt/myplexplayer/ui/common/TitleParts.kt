package com.biskyt.myplexplayer.ui.common

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.biskyt.myplexplayer.R
import com.biskyt.myplexplayer.core.plex.TitleLink
import com.biskyt.myplexplayer.core.vod.CastMember
import com.biskyt.myplexplayer.ui.theme.Palette
import com.biskyt.myplexplayer.ui.vod.SourceLogo
import com.biskyt.myplexplayer.ui.vod.linkLogo

/** A small heading over a group of options or details ("This episode", "File info"). */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = Palette.textDim,
        modifier = modifier.padding(top = 10.dp)
    )
}

/**
 * [links] side by side after [label]: each opens its page here, or, where this device can't (no browser), shows it as a
 * QR code to open on a phone. [modifier] (e.g. first focus) goes on the first; [inset] lines the row up with menu options.
 */
@Composable
fun LinksRow(label: String?, links: List<TitleLink>, modifier: Modifier = Modifier, inset: Dp = 0.dp) {
    val context = LocalContext.current
    var shown by remember { mutableStateOf<TitleLink?>(null) }
    Row(
        Modifier.padding(horizontal = inset),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        label?.let { Text(it, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f)) }
        links.forEachIndexed { i, link ->
            val open = { if (openLink(context, link.url) != null) shown = link }
            val first = if (i == 0) modifier else Modifier
            linkLogo(link.name)?.let { LogoPill(it, link.name, open, first) } ?: PillButton(link.name, open, first)
        }
    }
    shown?.let { LinkPanel(it.name, it.url) { shown = null } }
}

/** A pill showing a site's [logo] (named [name] for screen readers) in place of its name, as [PillButton] otherwise. */
@Composable
private fun LogoPill(@DrawableRes logo: Int, name: String, onClick: () -> Unit, modifier: Modifier) {
    Surface(
        onClick = onClick,
        modifier = modifier.onTap(onClick = onClick),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(50)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        colors = ClickableSurfaceDefaults.colors(containerColor = Palette.surfaceHigh, focusedContainerColor = Palette.surfaceHigh),
        border = ClickableSurfaceDefaults.border(focusedBorder = Border(FocusStroke))
    ) {
        SourceLogo(logo, name, Modifier.padding(horizontal = 20.dp, vertical = 10.dp))
    }
}

/** What a file is ("4K", "Dolby Vision", "Dolby Atmos 7.1"), as small chips. */
@Composable
fun BadgeRow(badges: List<String>, modifier: Modifier = Modifier) {
    if (badges.isEmpty()) return
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = modifier) {
        badges.forEach {
            Text(
                it,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                modifier = Modifier.clip(
                    RoundedCornerShape(5.dp)
                ).background(Palette.surfaceHigh).padding(horizontal = 8.dp, vertical = 2.dp)
            )
        }
    }
}

/** One of the cast: photo (loaded only when shown), name and part. */
@Composable
fun CastPerson(person: CastMember, photo: Dp, modifier: Modifier = Modifier) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically, modifier = modifier) {
        CirclePhoto(person.photoUrl, person.name, photo)
        Column(Modifier.width(150.dp)) {
            Text(person.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            person.role?.let { Muted(it) }
        }
    }
}

/** The cast in a row, as on a film's page. */
@Composable
fun CastRow(cast: List<CastMember>, modifier: Modifier = Modifier) {
    if (cast.isEmpty()) return
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionLabel(stringResource(R.string.vod_cast_title))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(cast) { person ->
                TvCard(onClick = {}, color = Palette.background) { CastPerson(person, 56.dp, Modifier.padding(8.dp)) }
            }
        }
    }
}

/** A title's text (an episode's description) in a side panel; focusable, so the remote's keys (Back) reach it. */
@Composable
fun TextPanel(heading: String, title: String, text: String, onDismiss: () -> Unit) {
    SidePanel(onDismiss, dim = false) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Muted(heading)
            Text(title, style = MaterialTheme.typography.titleLarge)
            Column(rememberFirstFocus().focusable().verticalScroll(rememberScrollState())) {
                Text(text, style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

/** The cast in a side panel: from a page's options, or over the player (which goes on behind it). */
@Composable
fun CastPanel(title: String, cast: List<CastMember>, onDismiss: () -> Unit) {
    SidePanel(onDismiss, dim = false) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Muted(title)
            Text(stringResource(R.string.vod_cast_title), style = MaterialTheme.typography.titleLarge)
            if (cast.isEmpty()) Muted(stringResource(R.string.vod_no_cast))
            LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                itemsIndexed(cast) { i, person ->
                    val focus = if (i == 0) rememberFirstFocus() else Modifier
                    TvCard(onClick = {}, color = Palette.surface, modifier = focus.fillMaxWidth()) {
                        CastPerson(person, 48.dp, Modifier.padding(horizontal = 10.dp, vertical = 6.dp))
                    }
                }
            }
        }
    }
}
