package com.biskyt.mytvplayer.ui.live

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.biskyt.mytvplayer.data.ChannelFilter
import com.biskyt.mytvplayer.data.db.ChannelEntity
import com.biskyt.mytvplayer.ui.LocalNavigator
import com.biskyt.mytvplayer.ui.Screen
import com.biskyt.mytvplayer.ui.appViewModel
import com.biskyt.mytvplayer.ui.common.AppIcons
import com.biskyt.mytvplayer.ui.common.CatchupMark
import com.biskyt.mytvplayer.ui.common.CategoryColumn
import com.biskyt.mytvplayer.ui.common.CategoryEntry
import com.biskyt.mytvplayer.ui.common.ChannelLogo
import com.biskyt.mytvplayer.ui.common.FlashLabel
import com.biskyt.mytvplayer.ui.common.ListHeader
import com.biskyt.mytvplayer.ui.common.Muted
import com.biskyt.mytvplayer.ui.common.NowNext
import com.biskyt.mytvplayer.ui.common.PillButton
import com.biskyt.mytvplayer.ui.common.ProgressLine
import com.biskyt.mytvplayer.ui.common.Tab
import com.biskyt.mytvplayer.ui.common.TabScaffold
import com.biskyt.mytvplayer.ui.common.TvCard
import com.biskyt.mytvplayer.ui.common.digit
import com.biskyt.mytvplayer.ui.common.formatTime
import com.biskyt.mytvplayer.ui.common.isGuide
import com.biskyt.mytvplayer.ui.common.numberLabel
import com.biskyt.mytvplayer.ui.common.rememberMinuteClock
import com.biskyt.mytvplayer.ui.common.restoreFocusIf
import com.biskyt.mytvplayer.ui.theme.LocalAccent
import com.biskyt.mytvplayer.ui.theme.Palette

@Composable
fun LiveScreen(initialFilter: ChannelFilter?, initialGuide: Boolean) {
    val vm = appViewModel { LiveViewModel(it, initialFilter, initialGuide) }
    vm.nowNext.PauseWhenHidden()
    val nav = LocalNavigator.current
    val filter by vm.filter.collectAsState()
    val pid by vm.playlistId.collectAsState()
    val play: (ChannelEntity) -> Unit = { ch ->
        vm.focusStreamId = ch.streamId
        nav.push(Screen.Player(pid, ch.streamId, filter))
    }
    // In the Catch-up lists, OK opens the channel's past programmes (Watch, in its options, plays it live).
    val open: (ChannelEntity) -> Unit = { ch ->
        if (vm.catchupMode) {
            vm.focusStreamId = ch.streamId
            nav.push(Screen.Catchup(ch.playlistId, ch.streamId))
        } else {
            play(ch)
        }
    }

    LaunchedEffect(Unit) { vm.restoreFocusPending = true }

    TabScaffold(if (vm.catchupMode) Tab.CATCHUP else Tab.LIVE) {
        Row(
            Modifier.fillMaxSize().padding(horizontal = 56.dp).onPreviewKeyEvent { e ->
                // 0–9 on the remote jumps straight to that channel number; the Guide button switches list/guide.
                val digit = e.key.digit()
                when {
                    e.type != KeyEventType.KeyDown -> e.key.isGuide()
                    digit != null -> {
                        vm.playNumber = play
                        vm.numberEntry.type(digit)
                        true
                    }
                    e.key.isGuide() -> {
                        vm.showGuide(!vm.guideMode)
                        true
                    }
                    else -> false
                }
            },
            horizontalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            LiveCategories(vm, filter)
            Column(Modifier.weight(1f)) {
                val filterBox = remember { FocusRequester() }
                ListHeader(vm, filter, Modifier.focusRequester(filterBox))
                // One paged list for both views, so the one switched to has its rows (and somewhere for focus) at once.
                val items = vm.channels.collectAsLazyPagingItems()
                BackToFilter(filterBox) {
                    if (vm.guideMode) GuideGrid(vm, items, play) else ChannelList(vm, items, open)
                }
            }
        }
    }
    vm.optionsFor?.let { ch -> ChannelOptionsSheet(ch, vm, play) }
    if (vm.numberEntry.typed.isNotEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopEnd) { FlashLabel(vm.numberEntry.typed) }
    }
}

/** Back with focus in [content] (the list or guide) goes up to the filter box; anywhere else it leaves Live TV. */
@Composable
private fun BackToFilter(filterBox: FocusRequester, content: @Composable () -> Unit) {
    var inside by remember { mutableStateOf(false) }
    BackHandler(enabled = inside) { runCatching { filterBox.requestFocus() } }
    Box(Modifier.onFocusChanged { inside = it.hasFocus }) { content() }
}

@Composable
private fun LiveCategories(vm: LiveViewModel, selected: ChannelFilter) {
    if (vm.catchupMode) return CatchupCategories(vm, selected)
    val categories by vm.categories.collectAsState()
    val entries = remember(categories) {
        listOf<CategoryEntry<ChannelFilter>>(
            CategoryEntry(ChannelFilter.Favourites, "Favourites"),
            CategoryEntry(ChannelFilter.Recent, "Recently watched"),
            CategoryEntry(ChannelFilter.All, "All channels")
        ) + categories.map { CategoryEntry<ChannelFilter>(ChannelFilter.Category(it.categoryId), it.name, it.channelCount, it) }
    }
    CategoryColumn(entries, selected, vm.filters::focus, vm.filters::select, vm::hideCategory)
}

/** The Catch-up tab's column: all catch-up channels, then each category that has some. */
@Composable
private fun CatchupCategories(vm: LiveViewModel, selected: ChannelFilter) {
    val categories by vm.catchupCategories.collectAsState()
    val entries = remember(categories) {
        listOf<CategoryEntry<ChannelFilter>>(CategoryEntry(ChannelFilter.Catchup, "All catch-up", categories.sumOf { it.count })) +
            categories.map { CategoryEntry<ChannelFilter>(ChannelFilter.CatchupCategory(it.categoryId), it.name, it.count) }
    }
    CategoryColumn(entries, selected, vm.filters::focus, vm.filters::select)
}

@Composable
private fun ListHeader(vm: LiveViewModel, filter: ChannelFilter, filterModifier: Modifier) {
    val query by vm.query.collectAsState()
    // Each tab reads only its own category list.
    val (title, count) = if (vm.catchupMode) {
        val categories by vm.catchupCategories.collectAsState()
        val shown = (filter as? ChannelFilter.CatchupCategory)?.let { f -> categories.firstOrNull { it.categoryId == f.id } }
        (shown?.name ?: "Catch-up") to (shown?.count ?: categories.sumOf { it.count })
    } else {
        val categories by vm.categories.collectAsState()
        val total by vm.totalCount.collectAsState()
        val favs by vm.favouriteIds.collectAsState()
        when (filter) {
            ChannelFilter.All -> "All channels" to total
            ChannelFilter.Favourites -> "Favourites" to favs.size
            is ChannelFilter.Category -> categories.firstOrNull { it.categoryId == filter.id }.let {
                (it?.name ?: "Channels") to
                    it?.channelCount
            }
            else -> "Recently watched" to null
        }
    }
    ListHeader(title, count, query, { vm.query.value = it }, "Filter by name or number", filterModifier) {
        Row(Modifier.clip(RoundedCornerShape(12.dp)).background(Palette.surface).padding(4.dp)) {
            PillButton("List", { vm.showGuide(false) }, primary = !vm.guideMode)
            PillButton("TV guide", { vm.showGuide(true) }, primary = vm.guideMode)
        }
    }
}

@Composable
private fun ChannelList(vm: LiveViewModel, items: LazyPagingItems<ChannelEntity>, play: (ChannelEntity) -> Unit) {
    val favs by vm.favouriteIds.collectAsState()
    val listState = vm.listState
    LaunchedEffect(Unit) { if (vm.restoreFocusPending) vm.revealFocused() }
    val now by rememberMinuteClock()

    LazyColumn(state = listState, verticalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(bottom = 40.dp)) {
        items(count = items.itemCount, key = items.itemKey { it.streamId }) { index ->
            val ch = items[index]
            if (ch == null) {
                PlaceholderRow()
            } else {
                // Once per visit, focus goes back to the channel last played (or the first row the first time).
                val wantsFocus = vm.restoreFocusPending && (vm.focusStreamId?.let { it == ch.streamId } ?: (index == 0))
                LaunchedEffect(ch.streamId) { vm.nowNext.request(ch.streamId) }
                ChannelRow(
                    ch = ch,
                    nn = vm.nowNext.entries[ch.streamId],
                    favourite = ch.streamId in favs,
                    nowMs = now,
                    onClick = { play(ch) },
                    onLongClick = { vm.openOptions(ch) },
                    modifier = Modifier.restoreFocusIf(wantsFocus) { vm.restoreFocusPending = false }
                        .onFocusChanged { if (it.isFocused) vm.focusMoved(ch.streamId, index) }
                )
            }
        }
    }
    if (items.itemCount == 0) {
        Muted("No channels here yet.", Modifier.padding(top = 24.dp))
    }
}

@Composable
fun ChannelRow(
    ch: ChannelEntity,
    nn: NowNext?,
    favourite: Boolean,
    nowMs: Long,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    TvCard(onClick = onClick, onLongClick = onLongClick, modifier = modifier.fillMaxWidth().height(64.dp), color = Palette.surfaceLow) {
        Row(
            Modifier.fillMaxSize().padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                ch.numberLabel,
                style = MaterialTheme.typography.titleSmall,
                color = Palette.textDim,
                textAlign = TextAlign.End,
                modifier = Modifier.width(48.dp)
            )
            ChannelLogo(ch.logoUrl, ch.name)
            // Provider names are long ("US: NBC 4 (WDIV) DETROIT HD"), so the name gets the larger share of the row.
            Row(Modifier.weight(1.2f), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    ch.name,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                if (favourite) {
                    Icon(
                        AppIcons.Heart,
                        "Favourite",
                        tint = LocalAccent.current.color,
                        modifier = Modifier.padding(start = 6.dp).size(16.dp)
                    )
                }
                CatchupMark(ch, Modifier.padding(start = 6.dp))
            }
            Column(Modifier.weight(1f)) {
                when {
                    nn == null -> SkeletonLines()
                    nn.now == null -> Muted("No guide information")
                    else -> {
                        Text(nn.now.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            nn.next?.let { "Next ${formatTime(it.startMs)} ${it.title}" } ?: "Until ${formatTime(nn.now.stopMs)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = Palette.textDim,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
            if (nn?.now != null) ProgressLine(nn.progress(nowMs), Modifier.width(80.dp))
        }
    }
}

@Composable
private fun SkeletonLines() {
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Box(Modifier.fillMaxWidth(0.46f).height(12.dp).clip(RoundedCornerShape(6.dp)).background(Palette.surfaceHigh))
        Box(Modifier.fillMaxWidth(0.28f).height(9.dp).clip(RoundedCornerShape(5.dp)).background(Palette.surface))
    }
}

@Composable
private fun PlaceholderRow() {
    Box(Modifier.fillMaxWidth().height(64.dp).clip(RoundedCornerShape(14.dp)).background(Palette.surfaceLow))
}
