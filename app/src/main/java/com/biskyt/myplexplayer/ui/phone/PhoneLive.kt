package com.biskyt.myplexplayer.ui.phone

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import androidx.tv.material3.Text
import com.biskyt.myplexplayer.R
import com.biskyt.myplexplayer.data.ChannelFilter
import com.biskyt.myplexplayer.data.db.CategoryEntity
import com.biskyt.myplexplayer.data.db.ChannelEntity
import com.biskyt.myplexplayer.ui.LocalNavigator
import com.biskyt.myplexplayer.ui.Screen
import com.biskyt.myplexplayer.ui.common.AppIcons
import com.biskyt.myplexplayer.ui.common.CategoryEntry
import com.biskyt.myplexplayer.ui.common.CategoryOptionsHost
import com.biskyt.myplexplayer.ui.common.FilterField
import com.biskyt.myplexplayer.ui.common.Muted
import com.biskyt.myplexplayer.ui.common.Tab
import com.biskyt.myplexplayer.ui.common.collectIds
import com.biskyt.myplexplayer.ui.common.formatCount
import com.biskyt.myplexplayer.ui.common.rememberMinuteClock
import com.biskyt.myplexplayer.ui.common.rememberRecordings
import com.biskyt.myplexplayer.ui.live.ChannelOptionsSheet
import com.biskyt.myplexplayer.ui.live.LiveViewModel
import com.biskyt.myplexplayer.ui.live.liveEntries
import com.biskyt.myplexplayer.ui.theme.FormFactor
import com.biskyt.myplexplayer.ui.theme.LocalAccent
import com.biskyt.myplexplayer.ui.theme.LocalFormFactor
import com.biskyt.myplexplayer.ui.theme.Palette

// Live TV on a phone or an unfolded foldable (the owner, 2026-11-25). The same ViewModel, lists and options as the TV
// screen (LiveScreen), drawn for touch. The guide stays the TV's screen.

/**
 * The channel list on a touch screen. A tap plays the channel ([play]), and a long press opens the channel's options,
 * as holding OK does on a TV.
 */
@Composable
fun PhoneLive(vm: LiveViewModel, play: (ChannelEntity) -> Unit) {
    val nav = LocalNavigator.current
    val filter by vm.filter.collectAsState()
    val unfolded = LocalFormFactor.current == FormFactor.UNFOLDED
    val entries = liveEntries(vm)
    val onHide: (CategoryEntity) -> Unit = { vm.hideCategory(it) }
    val openGuide = { nav.push(Screen.Live(filter, guide = true)) }
    // The options' "Switch to grid view" asks for the guide: here it opens the TV guide's screen.
    LaunchedEffect(vm.guideMode) {
        if (vm.guideMode) {
            vm.showGuide(false, moveFocus = false)
            openGuide()
        }
    }
    PhoneScaffold(Tab.LIVE, stringResource(Tab.LIVE.label), actions = { GuideButton(openGuide) }) {
        if (unfolded) {
            Row(Modifier.fillMaxSize()) {
                CategoryList(entries, filter, vm.filters::select, onHide, Modifier.width(240.dp).fillMaxHeight())
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    LiveFilter(vm, Modifier.padding(start = 8.dp, end = PhoneGutter, top = 6.dp, bottom = 10.dp))
                    Channels(vm, filter, play, PaddingValues(start = 8.dp, end = PhoneGutter, bottom = 24.dp))
                }
            }
        } else {
            LiveFilter(vm, Modifier.padding(horizontal = PhoneGutter, vertical = 6.dp))
            PhoneCategoryChips(entries, filter, vm.filters::select, onHide)
            Channels(vm, filter, play, PaddingValues(start = PhoneGutter, end = PhoneGutter, top = 4.dp, bottom = 16.dp))
        }
    }
    vm.optionsFor?.let { ch -> ChannelOptionsSheet(ch, vm, play) }
}

/** The header's button that opens the TV guide (drawn as on a TV). */
@Composable
private fun GuideButton(onClick: () -> Unit) {
    Row(
        Modifier.heightIn(min = PhoneTouch).clip(RoundedCornerShape(50)).background(PhoneChip)
            .clickable(role = Role.Button, onClick = onClick).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Image(AppIcons.Guide, null, Modifier.size(18.dp), colorFilter = ColorFilter.tint(Palette.text))
        Text(stringResource(R.string.live_view_guide), color = Palette.text, fontSize = 14.sp, fontWeight = FontWeight.ExtraBold)
    }
}

/** The box that filters the channels by name or number, as the TV's filter box does. */
@Composable
private fun LiveFilter(vm: LiveViewModel, modifier: Modifier) {
    val query by vm.query.collectAsState()
    FilterField(query, { vm.query.value = it }, stringResource(R.string.live_filter_hint), modifier.fillMaxWidth())
}

/**
 * The lists as a column at the side (an unfolded foldable), with their sizes, as on a TV but at touch size. A long press
 * on a category offers its options ([onHide]), as on a TV.
 */
@Composable
private fun CategoryList(
    entries: List<CategoryEntry<ChannelFilter>>,
    selected: ChannelFilter,
    select: (ChannelFilter) -> Unit,
    onHide: (CategoryEntity) -> Unit,
    modifier: Modifier
) {
    val state = rememberLazyListState()
    val index = entries.indexOfFirst { it.filter == selected }
    LaunchedEffect(index) {
        if (index >= 0 && state.layoutInfo.visibleItemsInfo.none { it.index == index }) state.scrollToItem(index)
    }
    var options by remember { mutableStateOf<CategoryEntity?>(null) }
    LazyColumn(
        modifier,
        state = state,
        contentPadding = PaddingValues(start = 12.dp, end = 8.dp, top = 6.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        itemsIndexed(entries, key = { _, e -> e.label + e.filter.hashCode() }) { _, entry ->
            val on = entry.filter == selected
            val category = entry.category
            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(12.dp))
                    .background(if (on) PhoneChip else Palette.background)
                    .semantics { this.selected = on }
                    .combinedClickable(
                        role = Role.Tab,
                        onLongClick = category?.let { { options = it } }
                    ) { select(entry.filter) }
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    entry.label,
                    color = if (on) LocalAccent.current.color else Palette.text,
                    fontSize = 15.sp,
                    fontWeight = if (on) FontWeight.ExtraBold else FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                entry.count?.let { Text(formatCount(it), color = Palette.textDim, fontSize = 13.sp) }
            }
        }
    }
    CategoryOptionsHost(options, entries, { options = null }, select, onHide, selected)
}

/** The channels of the list picked, as on a TV. */
@Composable
private fun Channels(vm: LiveViewModel, filter: ChannelFilter, play: (ChannelEntity) -> Unit, padding: PaddingValues) {
    val items = vm.channels.collectAsLazyPagingItems()
    // A channel taken off the list (from its options): a TV moves focus on from it; a touch screen has no focus to move.
    vm.removedStreamId?.let { removed -> LaunchedEffect(removed) { vm.removedStreamId = null } }
    if (vm.filters.settled) ChannelList(vm, items, filter, play, padding)
}

@Composable
private fun ChannelList(
    vm: LiveViewModel,
    items: LazyPagingItems<ChannelEntity>,
    filter: ChannelFilter,
    play: (ChannelEntity) -> Unit,
    padding: PaddingValues
) {
    val favs = vm.favouriteIds.collectIds()
    // What's recording now (Plex's DVR), for the channels' marks.
    val pid by vm.playlistId.collectAsState()
    val recordings by rememberRecordings(pid)
    val query by vm.query.collectAsState()
    val now by rememberMinuteClock()
    // The TV's list state, so the list is where it was back from the player; a new list starts at the top.
    val state = vm.listState
    var shown by remember { mutableStateOf(filter to query) }
    LaunchedEffect(filter, query) {
        if (shown != filter to query) {
            shown = filter to query
            state.scrollToItem(0)
        }
    }
    if (items.itemCount == 0) {
        Muted(stringResource(R.string.live_no_channels), Modifier.padding(horizontal = PhoneGutter, vertical = 24.dp))
        return
    }
    LazyColumn(state = state, contentPadding = padding, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        items(count = items.itemCount, key = items.itemKey { it.streamId }) { index ->
            val ch = items[index]
            if (ch == null) {
                Box(Modifier.fillMaxWidth().height(PHONE_CHANNEL_HEIGHT).clip(RoundedCornerShape(14.dp)).background(PhoneCard))
            } else {
                PhoneChannelCard(
                    ch,
                    vm.nowNext,
                    now,
                    onLongClick = {
                        vm.focusMoved(ch.streamId, index)
                        vm.openOptions(ch)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    number = true,
                    favourite = ch.streamId in favs,
                    recording = recordings.recordingOn(ch.providerEpgId) != null
                ) {
                    vm.focusMoved(ch.streamId, index)
                    play(ch)
                }
            }
        }
    }
}
