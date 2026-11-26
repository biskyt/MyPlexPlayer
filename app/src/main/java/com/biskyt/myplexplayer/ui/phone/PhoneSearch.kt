package com.biskyt.myplexplayer.ui.phone

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import com.biskyt.myplexplayer.R
import com.biskyt.myplexplayer.data.ChannelFilter
import com.biskyt.myplexplayer.data.RecordingMark
import com.biskyt.myplexplayer.data.db.CAT_MOVIE
import com.biskyt.myplexplayer.data.db.CAT_SERIES
import com.biskyt.myplexplayer.data.db.ProgrammeHit
import com.biskyt.myplexplayer.data.db.VodCard
import com.biskyt.myplexplayer.data.programmeTitle
import com.biskyt.myplexplayer.ui.LocalNavigator
import com.biskyt.myplexplayer.ui.Screen
import com.biskyt.myplexplayer.ui.common.AmbientSource
import com.biskyt.myplexplayer.ui.common.AppIcons
import com.biskyt.myplexplayer.ui.common.ChannelLogo
import com.biskyt.myplexplayer.ui.common.LocalAmbience
import com.biskyt.myplexplayer.ui.common.Muted
import com.biskyt.myplexplayer.ui.common.OnNowLabel
import com.biskyt.myplexplayer.ui.common.RecordingDot
import com.biskyt.myplexplayer.ui.common.TvTextField
import com.biskyt.myplexplayer.ui.common.collectIds
import com.biskyt.myplexplayer.ui.common.formatDayTime
import com.biskyt.myplexplayer.ui.common.isLive
import com.biskyt.myplexplayer.ui.common.rememberMinuteClock
import com.biskyt.myplexplayer.ui.common.rememberRecordings
import com.biskyt.myplexplayer.ui.common.vodDetails
import com.biskyt.myplexplayer.ui.search.Picked
import com.biskyt.myplexplayer.ui.search.ResultOptions
import com.biskyt.myplexplayer.ui.search.SearchViewModel
import com.biskyt.myplexplayer.ui.theme.FormFactor
import com.biskyt.myplexplayer.ui.theme.LocalFormFactor
import com.biskyt.myplexplayer.ui.theme.Palette

// Search on a phone or an unfolded foldable (the owner, 2026-11-26): the TV's results, sized for touch. A tap plays or
// opens a result, and a long press opens its options, as holding OK does on a TV.

/** The search box, then the series, films, channels and TV programmes found, from the TV's [SearchViewModel]. */
@Composable
internal fun PhoneSearch(vm: SearchViewModel) {
    val nav = LocalNavigator.current
    val query by vm.query.collectAsState()
    val found = vm.found.collectAsState().value
    val pid = found.playlistId
    val results = found.results
    val now by rememberMinuteClock()
    val favouriteChannels = vm.favourites.channels.collectIds()
    val favouriteSeries = vm.favourites.series.collectIds()
    val favouriteMovies = vm.favourites.movies.collectIds()
    // What's set to record (Plex's DVR), for the programmes' marks.
    val recordings by rememberRecordings(pid)
    val wide = LocalFormFactor.current == FormFactor.UNFOLDED
    // The keyboard is up on arrival, ready to type.
    val editing = rememberSaveable { mutableStateOf(true) }
    var picked by remember { mutableStateOf<Picked?>(null) }
    val play = { streamId: Long -> nav.push(Screen.Player(pid, streamId, ChannelFilter.All)) }
    // A new search starts at the top of its results; back from a result, the list is where it was.
    var searched by rememberSaveable { mutableStateOf(found.query) }
    LaunchedEffect(found.query) {
        if (found.query != searched) {
            searched = found.query
            vm.phoneList.scrollToItem(0)
        }
    }
    PhoneScaffold(null, stringResource(R.string.common_search)) {
        TvTextField(
            value = query,
            onValueChange = { vm.query.value = it },
            placeholder = stringResource(R.string.search_placeholder),
            modifier = Modifier.fillMaxWidth().padding(horizontal = PhoneGutter, vertical = 6.dp),
            leading = { Icon(AppIcons.Search, null, tint = Palette.textDim, modifier = Modifier.size(20.dp)) },
            editing = editing
        )
        LazyColumn(
            state = vm.phoneList,
            contentPadding = PaddingValues(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            val message = when {
                found.query.isEmpty() -> null
                found.failed -> R.string.search_failed
                results == null -> R.string.search_keep_typing
                results.isEmpty -> R.string.search_nothing_found
                else -> null
            }
            if (message != null) {
                item { Muted(stringResource(message, found.query), Modifier.padding(horizontal = PhoneGutter, vertical = 12.dp)) }
            }
            if (results == null || found.failed) return@LazyColumn
            // Series, films, then Live TV: channels, then TV programmes, as on a TV.
            vodResults(R.string.common_tab_series, CAT_SERIES, results.series, favouriteSeries, pid) { picked = it }
            vodResults(R.string.common_tab_movies, CAT_MOVIE, results.movies, favouriteMovies, pid) { picked = it }
            if (results.channels.isNotEmpty()) {
                item { PhoneSectionTitle(stringResource(R.string.common_channels)) }
                channelCards(
                    "channels",
                    results.channels,
                    wide,
                    vm.nowNext,
                    { picked = Picked.Channel(it.streamId, it.name) },
                    favouriteChannels
                ) { play(it.streamId) }
            }
            if (results.programmes.isNotEmpty()) {
                item { PhoneSectionTitle(stringResource(R.string.search_on_tv)) }
                items(results.programmes, key = { "p${it.streamId}:${it.startMs}" }) { p ->
                    ProgrammeRow(p, now, recordings.mark(p.guid, p.showGuid, p.startMs), {
                        // Its options can also record it.
                        picked = Picked.Channel(p.streamId, p.channelName, p)
                    }) { play(p.streamId) }
                }
            }
        }
    }
    picked?.let { ResultOptions(it, pid, vm) { picked = null } }
}

/** A row of the series or films found. */
@Suppress("LongParameterList") // the section's title and type, what was found, and what a poster does
private fun LazyListScope.vodResults(
    title: Int,
    type: String,
    cards: List<VodCard>,
    favourites: Set<Long>,
    pid: Long,
    onOptions: (Picked) -> Unit
) {
    if (cards.isEmpty()) return
    item(key = "vod-$type") {
        val nav = LocalNavigator.current
        PhoneSection(stringResource(title)) {
            PhonePosterRow(cards, VodCard::id) { card ->
                PhonePoster(
                    card.imageUrl,
                    card.title,
                    card.year?.toString().orEmpty(),
                    favourite = card.id in favourites,
                    onLongClick = { onOptions(Picked.Vod(type, card)) }
                ) { nav.push(vodDetails(pid, type, card.id)) }
            }
        }
    }
}

/** A programme found: its channel's logo, its title, the channel, its recording mark ([mark]), and when it is on. */
@Composable
private fun ProgrammeRow(p: ProgrammeHit, now: Long, mark: RecordingMark?, onLongClick: () -> Unit, onClick: () -> Unit) {
    val ambience = LocalAmbience.current
    Row(
        Modifier.padding(horizontal = PhoneGutter).fillMaxWidth().heightIn(min = PHONE_CHANNEL_HEIGHT)
            .clip(RoundedCornerShape(14.dp)).background(PhoneCard)
            .touchCard(ambience, AmbientSource.logo(p.logoUrl), onLongClick, onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        ChannelLogo(p.logoUrl, p.channelName, width = 64.dp, height = 40.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                programmeTitle(p.title),
                color = Palette.text,
                fontSize = 15.sp,
                fontWeight = FontWeight.ExtraBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                listOfNotNull(p.channelName, p.subtitle).joinToString(" · "),
                color = Palette.textDim,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        // MyPlexPlayer's own: set to record with Plex's DVR.
        mark?.let { RecordingDot(it) }
        if (isLive(p.startMs, p.stopMs, now)) OnNowLabel() else Muted(formatDayTime(p.startMs, now))
    }
}
