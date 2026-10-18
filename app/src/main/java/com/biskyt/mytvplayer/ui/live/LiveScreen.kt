package com.biskyt.mytvplayer.ui.live

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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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
import com.biskyt.mytvplayer.ui.common.ChannelLogo
import com.biskyt.mytvplayer.ui.common.Muted
import com.biskyt.mytvplayer.ui.common.NowNext
import com.biskyt.mytvplayer.ui.common.PillButton
import com.biskyt.mytvplayer.ui.common.ProgressLine
import com.biskyt.mytvplayer.ui.common.Tab
import com.biskyt.mytvplayer.ui.common.TabScaffold
import com.biskyt.mytvplayer.ui.common.TvCard
import com.biskyt.mytvplayer.ui.common.TvTextField
import com.biskyt.mytvplayer.ui.common.formatCount
import com.biskyt.mytvplayer.ui.common.formatTime
import com.biskyt.mytvplayer.ui.theme.LocalAccent
import com.biskyt.mytvplayer.ui.theme.Palette

@Composable
fun LiveScreen(initialFilter: ChannelFilter, initialGuide: Boolean) {
    val vm = appViewModel { LiveViewModel(it, initialFilter, initialGuide) }
    val nav = LocalNavigator.current
    val filter by vm.filter.collectAsState()
    val pid by vm.playlistId.collectAsState()
    val play: (ChannelEntity) -> Unit = { ch ->
        vm.focusStreamId = ch.streamId
        nav.push(Screen.Player(pid, ch.streamId, filter))
    }

    LaunchedEffect(Unit) { vm.restoreFocusPending = true }

    TabScaffold(if (initialFilter == ChannelFilter.Favourites) Tab.FAVOURITES else Tab.LIVE) {
        Row(
            Modifier.fillMaxSize().padding(horizontal = 56.dp).onPreviewKeyEvent { e ->
                // 0–9 on the remote jumps straight to that channel number.
                val digit = e.digit()
                if (digit != null && e.type == KeyEventType.KeyDown) {
                    vm.typeDigit(digit, play)
                    true
                } else {
                    false
                }
            },
            horizontalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            CategoryColumn(vm, filter)
            Column(Modifier.weight(1f)) {
                ListHeader(vm, filter)
                if (vm.guideMode) GuideGrid(vm, play) else ChannelList(vm, play)
            }
        }
    }
    vm.optionsFor?.let { ch -> ChannelOptionsSheet(ch, vm, play) }
    if (vm.typedNumber.isNotEmpty()) NumberEntry(vm.typedNumber)
}

private fun androidx.compose.ui.input.key.KeyEvent.digit(): Int? = when (key) {
    Key.Zero, Key.NumPad0 -> 0
    Key.One, Key.NumPad1 -> 1
    Key.Two, Key.NumPad2 -> 2
    Key.Three, Key.NumPad3 -> 3
    Key.Four, Key.NumPad4 -> 4
    Key.Five, Key.NumPad5 -> 5
    Key.Six, Key.NumPad6 -> 6
    Key.Seven, Key.NumPad7 -> 7
    Key.Eight, Key.NumPad8 -> 8
    Key.Nine, Key.NumPad9 -> 9
    else -> null
}

@Composable
private fun CategoryColumn(vm: LiveViewModel, selected: ChannelFilter) {
    val categories by vm.categories.collectAsState()
    val entries = remember(categories) {
        listOf(
            Triple<ChannelFilter, String, Int?>(ChannelFilter.Favourites, "Favourites", null),
            Triple(ChannelFilter.Recent, "Recently watched", null),
            Triple(ChannelFilter.All, "All channels", null),
        ) + categories.map { Triple(ChannelFilter.Category(it.categoryId), it.name, it.channelCount) }
    }
    LazyColumn(Modifier.width(250.dp), verticalArrangement = Arrangement.spacedBy(4.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
        items(entries, key = { it.second + it.first.hashCode() }) { (f, label, count) ->
            val isSelected = f == selected
            TvCard(
                onClick = { vm.selectFilter(f) },
                selected = isSelected,
                color = Palette.background,
                modifier = Modifier.fillMaxWidth().onFocusChanged { if (it.isFocused) vm.focusFilter(f) },
            ) {
                Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        label,
                        style = MaterialTheme.typography.titleSmall,
                        color = if (isSelected) LocalAccent.current.color else Palette.text,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (count != null) Text(formatCount(count), style = MaterialTheme.typography.bodySmall, color = Palette.textDim)
                }
            }
        }
    }
}

@Composable
private fun ListHeader(vm: LiveViewModel, filter: ChannelFilter) {
    val categories by vm.categories.collectAsState()
    val total by vm.totalCount.collectAsState()
    val favs by vm.favouriteIds.collectAsState()
    val query by vm.query.collectAsState()
    val (title, count) = when (filter) {
        ChannelFilter.All -> "All channels" to total
        ChannelFilter.Favourites -> "Favourites" to favs.size
        ChannelFilter.Recent -> "Recently watched" to null
        is ChannelFilter.Category -> categories.firstOrNull { it.categoryId == filter.id }.let { (it?.name ?: "Channels") to it?.channelCount }
    }
    Row(Modifier.padding(bottom = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(title, style = MaterialTheme.typography.headlineSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(0.45f, fill = false))
        if (count != null) Muted("· ${formatCount(count)}")
        TvTextField(
            value = query,
            onValueChange = { vm.query.value = it },
            placeholder = "Filter by name or number",
            modifier = Modifier.weight(0.55f),
            leading = { Icon(AppIcons.Filter, null, tint = Palette.textDim, modifier = Modifier.size(18.dp)) },
        )
        Row(Modifier.clip(RoundedCornerShape(12.dp)).background(Palette.surface).padding(4.dp)) {
            PillButton("List", { vm.guideMode = false }, primary = !vm.guideMode)
            PillButton("TV guide", { vm.guideMode = true }, primary = vm.guideMode)
        }
    }
}

@Composable
private fun ChannelList(vm: LiveViewModel, play: (ChannelEntity) -> Unit) {
    val items = vm.channels.collectAsLazyPagingItems()
    val favs by vm.favouriteIds.collectAsState()
    val listState = rememberLazyListState()
    val restore = remember { FocusRequester() }
    val now = System.currentTimeMillis()

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
                    onLongClick = { vm.optionsFor = ch },
                    modifier = if (wantsFocus) Modifier.focusRequester(restore) else Modifier,
                )
                if (wantsFocus) {
                    LaunchedEffect(Unit) {
                        if (runCatching { restore.requestFocus() }.isSuccess) vm.restoreFocusPending = false
                    }
                }
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
    modifier: Modifier = Modifier,
) {
    TvCard(onClick = onClick, onLongClick = onLongClick, modifier = modifier.fillMaxWidth().height(64.dp), color = Palette.surfaceLow) {
        Row(Modifier.fillMaxSize().padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(ch.number.takeIf { it > 0 }?.toString() ?: "", style = MaterialTheme.typography.titleSmall, color = Palette.textDim, textAlign = TextAlign.End, modifier = Modifier.width(48.dp))
            ChannelLogo(ch.logoUrl, ch.name)
            Row(Modifier.width(220.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(ch.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (favourite) Icon(AppIcons.Heart, "Favourite", tint = LocalAccent.current.color, modifier = Modifier.padding(start = 6.dp).size(16.dp))
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
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            if (nn?.now != null) ProgressLine(nn.progress(nowMs), Modifier.width(110.dp))
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

@Composable
private fun NumberEntry(number: String) {
    Box(Modifier.fillMaxSize().padding(48.dp), contentAlignment = Alignment.TopEnd) {
        Text(
            number,
            style = MaterialTheme.typography.displaySmall,
            modifier = Modifier.clip(RoundedCornerShape(16.dp)).background(Palette.surfaceHigh).padding(horizontal = 28.dp, vertical = 12.dp),
        )
    }
}
