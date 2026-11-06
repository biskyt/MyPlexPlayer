package com.biskyt.myplexplayer.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.biskyt.myplexplayer.R
import com.biskyt.myplexplayer.core.plex.TitleLink
import com.biskyt.myplexplayer.core.vod.CastMember
import com.biskyt.myplexplayer.ui.theme.bd

/**
 * Opens a title's link here, or, where this device can't (no browser), shows it as a QR code to open on a phone: what
 * every row of links does.
 */
@Composable
fun rememberLinkOpener(): (TitleLink) -> Unit {
    val context = LocalContext.current
    var shown by remember { mutableStateOf<TitleLink?>(null) }
    shown?.let { LinkPanel(it.name, it.url) { shown = null } }
    return remember(context) { { link -> if (openLink(context, link.url) != null) shown = link } }
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

/**
 * The cast in a side panel: from a page's options, or over the player (which goes on behind it). [heading] is "Cast",
 * or "Cast and crew" where [cast] also lists who directed and wrote it. OK on someone the server gave an id to opens
 * their page ([open], with their place in [cast]). [focus] is the place that has the remote as the panel opens (back
 * from that page, the same person).
 */
@Composable
fun CastPanel(
    title: String,
    cast: List<CastMember>,
    heading: String = stringResource(R.string.vod_cast_title),
    focus: Int = 0,
    open: (Int) -> Unit = {},
    onDismiss: () -> Unit
) {
    // The cast is a list of its own, which does not go in a scrolling column.
    BoardPanel(onDismiss, dim = false, scroll = false) {
        BoardPanelSubtitle(title)
        BoardPanelTitle(heading, Modifier.padding(bottom = 6.bd))
        if (cast.isEmpty()) BoardPanelSubtitle(stringResource(R.string.vod_no_cast))
        val first = focus.coerceIn(0, (cast.size - 1).coerceAtLeast(0))
        LazyColumn(state = rememberLazyListState(first), verticalArrangement = Arrangement.spacedBy(BoardPanelGap)) {
            itemsIndexed(cast) { i, person ->
                val modifier = if (i == first) rememberFirstFocus() else Modifier
                BoardRow({ if (person.id != null) open(i) }, modifier) {
                    CastPerson(person, 48.dp, Modifier.padding(horizontal = 10.bd, vertical = 6.bd))
                }
            }
        }
    }
}
