package com.biskyt.mytvplayer.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.biskyt.mytvplayer.AppContainer
import com.biskyt.mytvplayer.data.ChannelFilter
import com.biskyt.mytvplayer.data.db.ChannelEntity
import com.biskyt.mytvplayer.data.db.WatchProgressEntity
import com.biskyt.mytvplayer.ui.LocalNavigator
import com.biskyt.mytvplayer.ui.Screen
import com.biskyt.mytvplayer.ui.appViewModel
import com.biskyt.mytvplayer.ui.common.CatchupMark
import com.biskyt.mytvplayer.ui.common.ChannelLogo
import com.biskyt.mytvplayer.ui.common.ItemAction
import com.biskyt.mytvplayer.ui.common.ItemActionsPanel
import com.biskyt.mytvplayer.ui.common.Muted
import com.biskyt.mytvplayer.ui.common.NowNextLoader
import com.biskyt.mytvplayer.ui.common.PillButton
import com.biskyt.mytvplayer.ui.common.Poster
import com.biskyt.mytvplayer.ui.common.ProgressLine
import com.biskyt.mytvplayer.ui.common.SectionTitle
import com.biskyt.mytvplayer.ui.common.Tab
import com.biskyt.mytvplayer.ui.common.TabScaffold
import com.biskyt.mytvplayer.ui.common.TvCard
import com.biskyt.mytvplayer.ui.common.channelActions
import com.biskyt.mytvplayer.ui.common.formatDuration
import com.biskyt.mytvplayer.ui.common.formatTime
import com.biskyt.mytvplayer.ui.common.rememberFirstFocus
import com.biskyt.mytvplayer.ui.common.rememberMinuteClock
import com.biskyt.mytvplayer.ui.player.shortEpisodeLabel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class HomeViewModel(private val c: AppContainer) : ViewModel() {
    private val activePlaylist = c.settings.activePlaylistId
    val playlistId = activePlaylist.stateIn(viewModelScope, SharingStarted.Eagerly, 0L)

    @OptIn(ExperimentalCoroutinesApi::class)
    val favourites = activePlaylist.flatMapLatest {
        c.channels.observeFavourites(it, 12)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    @OptIn(ExperimentalCoroutinesApi::class)
    val recent = activePlaylist.flatMapLatest {
        c.channels.observeRecent(it, 12)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    @OptIn(ExperimentalCoroutinesApi::class)
    val carryOn = activePlaylist.flatMapLatest { c.vod.observeCarryOn(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    @OptIn(ExperimentalCoroutinesApi::class)
    val favouriteIds = activePlaylist.flatMapLatest { c.channels.observeFavouriteIds(it) }.map { it.toSet() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    fun setFavourite(ch: ChannelEntity, favourite: Boolean) = viewModelScope.launch {
        c.channels.setFavourite(ch.playlistId, ch.streamId, favourite)
    }

    fun removeRecent(ch: ChannelEntity) = viewModelScope.launch { c.channels.removeRecent(ch.playlistId, ch.streamId) }

    fun forget(p: WatchProgressEntity) = viewModelScope.launch { c.vod.forgetProgress(p) }

    val nowNext =
        NowNextLoader(viewModelScope, c.epg.changes) { ids, from, to -> c.epg.programmes(playlistId.value, ids, from, to, details = false) }
}

@Composable
fun HomeScreen() {
    val vm = appViewModel { HomeViewModel(it) }
    vm.nowNext.PauseWhenHidden()
    val nav = LocalNavigator.current
    val favourites by vm.favourites.collectAsState()
    val recent by vm.recent.collectAsState()
    val carryOn by vm.carryOn.collectAsState()
    val pid = vm.playlistId.collectAsState().value
    // Again after a card is taken off its row (focus would otherwise go with it): back to the first card.
    var refocus by remember { mutableIntStateOf(0) }
    val first = rememberFirstFocus((favourites != null) to refocus)
    var options by remember { mutableStateOf<HomeTile?>(null) }
    val scope = rememberCoroutineScope()
    fun shown(tile: HomeTile) = when (tile) {
        is HomeTile.Channel -> (
            if (tile.list ==
                ChannelFilter.Recent
            ) {
                recent
            } else {
                favourites.orEmpty()
            }
            ).any { it.streamId == tile.ch.streamId }
        is HomeTile.CarryOn -> carryOn.any { it.kind == tile.p.kind && it.itemId == tile.p.itemId }
    }

    TabScaffold(Tab.HOME) {
        LazyColumn(
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 56.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(28.dp)
        ) {
            if (carryOn.isNotEmpty()) {
                item {
                    Column {
                        SectionTitle("Carry on watching")
                        CarryOnRow(carryOn, { options = HomeTile.CarryOn(it) }) {
                            nav.push(Screen.VodPlayer(pid, it.itemId, it.seriesId, continueSeries = true))
                        }
                    }
                }
            }
            item {
                Column {
                    SectionTitle("Your channels, on now")
                    val favs = favourites
                    when {
                        favs == null -> Unit
                        favs.isEmpty() -> EmptyFavourites(first) { nav.reset(Screen.Live()) }
                        else -> ChannelCardRow(favs, vm.nowNext, { options = HomeTile.Channel(it, ChannelFilter.Favourites) }, first) {
                            nav.push(Screen.Player(pid, it.streamId, ChannelFilter.Favourites))
                        }
                    }
                }
            }
            if (recent.isNotEmpty()) {
                item {
                    Column {
                        SectionTitle("Recently watched")
                        ChannelCardRow(recent, vm.nowNext, { options = HomeTile.Channel(it, ChannelFilter.Recent) }) {
                            nav.push(Screen.Player(pid, it.streamId, ChannelFilter.Recent))
                        }
                    }
                }
            }
        }
    }
    options?.let { tile ->
        TileOptions(tile, vm) { removed ->
            options = null
            if (removed) scope.launch { snapshotFlow { shown(tile) }.first { !it }.also { refocus++ } }
        }
    }
}

/** A Home card whose options are open: a channel (from [list]: favourites or recently watched), or a title carried on. */
private sealed interface HomeTile {
    data class Channel(val ch: ChannelEntity, val list: ChannelFilter) : HomeTile

    data class CarryOn(val p: WatchProgressEntity) : HomeTile
}

/** Holding OK on a Home card: watch it, and favourites, catch-up and the guide source, or take it off its row. */
@Composable
private fun TileOptions(tile: HomeTile, vm: HomeViewModel, onDismiss: (removed: Boolean) -> Unit) {
    val close = { onDismiss(false) }
    when (tile) {
        is HomeTile.Channel -> {
            val ch = tile.ch
            val favourites by vm.favouriteIds.collectAsState()
            val actions = buildList {
                add(ItemAction.Open("Watch", Screen.Player(ch.playlistId, ch.streamId, tile.list)))
                addAll(
                    channelActions(ch, ch.streamId in favourites) { favourite ->
                        vm.setFavourite(ch, favourite)
                        if (!favourite && tile.list == ChannelFilter.Favourites) onDismiss(true)
                    }
                )
                if (tile.list == ChannelFilter.Recent) {
                    add(
                        ItemAction.Run("Remove from Recently watched") {
                            vm.removeRecent(ch)
                            onDismiss(true)
                        }
                    )
                }
            }
            ItemActionsPanel(ch.name, actions, close)
        }
        is HomeTile.CarryOn -> {
            val p = tile.p
            val series = p.seriesId
            val actions = listOf(
                ItemAction.Open(
                    if (p.finished) "Play the next episode" else "Resume",
                    Screen.VodPlayer(p.playlistId, p.itemId, series, continueSeries = true)
                ),
                if (series == null) {
                    ItemAction.Open("About this movie", Screen.MovieDetails(p.playlistId, p.itemId))
                } else {
                    ItemAction.Open("All episodes", Screen.SeriesDetails(p.playlistId, series, p.season, p.episode))
                },
                ItemAction.Run(
                    "Remove from Carry on watching",
                    if (series == null) "Forgets how far you got" else "Forgets which episodes you've watched"
                ) {
                    vm.forget(p)
                    onDismiss(true)
                }
            )
            ItemActionsPanel(p.title, actions, close)
        }
    }
}

@Composable
private fun EmptyFavourites(modifier: Modifier, onBrowse: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Muted("No favourites yet. On Live TV, hold OK on a channel and choose “Add to favourites”.", maxLines = 2)
        PillButton("Browse channels", onBrowse, modifier, primary = true)
    }
}

@Composable
private fun ChannelCardRow(
    channels: List<ChannelEntity>,
    loader: NowNextLoader,
    onOptions: (ChannelEntity) -> Unit,
    modifier: Modifier = Modifier,
    onPlay: (ChannelEntity) -> Unit
) {
    val now by rememberMinuteClock()
    LazyRow(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        items(channels, key = { it.streamId }) { c ->
            LaunchedEffect(c.streamId) { loader.request(c.streamId) }
            val nn = loader.entries[c.streamId]
            TvCard(
                onClick = { onPlay(c) },
                onLongClick = { onOptions(c) },
                modifier = (if (c == channels.first()) modifier else Modifier).width(272.dp)
            ) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ChannelLogo(c.logoUrl, c.name, 64.dp, 38.dp)
                        Muted(c.name, Modifier.padding(start = 10.dp).weight(1f, fill = false))
                        CatchupMark(c, Modifier.padding(start = 6.dp))
                    }
                    Text(
                        nn?.now?.title ?: " ",
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Muted(
                        nn?.now?.let { "until ${formatTime(it.stopMs)}" + (nn.next?.let { n -> " · then ${n.title}" } ?: "") }
                            ?: "No guide information"
                    )
                    ProgressLine(nn?.progress(now) ?: 0f, Modifier.fillMaxWidth())
                }
            }
        }
    }
}

/** Movies and episodes part-watched (or the next episode up): a poster-sized card each, with how much is left. */
@Composable
private fun CarryOnRow(items: List<WatchProgressEntity>, onOptions: (WatchProgressEntity) -> Unit, onPlay: (WatchProgressEntity) -> Unit) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        items(items, key = { "${it.kind}${it.itemId}" }) { p ->
            TvCard(onClick = { onPlay(p) }, onLongClick = { onOptions(p) }, modifier = Modifier.width(272.dp)) {
                Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Poster(p.imageUrl, p.title, 64.dp)
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(p.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        if (p.finished) {
                            // A finished episode with another after it: the card leads on to that one.
                            Muted("Up next after ${shortEpisodeLabel(p.season, p.episode)}")
                        } else {
                            if (p.season != null) Muted(shortEpisodeLabel(p.season, p.episode) + (p.episodeTitle?.let { " · $it" } ?: ""))
                            Muted("${formatDuration(p.remainingMs)} left")
                            ProgressLine(p.fraction, Modifier.fillMaxWidth())
                        }
                    }
                }
            }
        }
    }
}
