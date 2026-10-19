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
import com.biskyt.mytvplayer.ui.common.ChannelLogo
import com.biskyt.mytvplayer.ui.common.Muted
import com.biskyt.mytvplayer.ui.common.NowNextLoader
import com.biskyt.mytvplayer.ui.common.PillButton
import com.biskyt.mytvplayer.ui.common.Poster
import com.biskyt.mytvplayer.ui.common.ProgressLine
import com.biskyt.mytvplayer.ui.common.SectionTitle
import com.biskyt.mytvplayer.ui.common.Tab
import com.biskyt.mytvplayer.ui.common.TabScaffold
import com.biskyt.mytvplayer.ui.common.TvCard
import com.biskyt.mytvplayer.ui.common.formatDuration
import com.biskyt.mytvplayer.ui.common.formatTime
import com.biskyt.mytvplayer.ui.common.rememberFirstFocus
import com.biskyt.mytvplayer.ui.common.rememberMinuteClock
import com.biskyt.mytvplayer.ui.player.shortEpisodeLabel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn

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

    val nowNext =
        NowNextLoader(viewModelScope, c.epg.changes) { ids, from, to -> c.epg.programmes(playlistId.value, ids, from, to, details = false) }
}

@Composable
fun HomeScreen() {
    val vm = appViewModel { HomeViewModel(it) }
    val nav = LocalNavigator.current
    val favourites by vm.favourites.collectAsState()
    val recent by vm.recent.collectAsState()
    val carryOn by vm.carryOn.collectAsState()
    val pid = vm.playlistId.collectAsState().value
    val first = rememberFirstFocus(favourites != null)

    TabScaffold(Tab.HOME) {
        LazyColumn(
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 56.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(28.dp)
        ) {
            if (carryOn.isNotEmpty()) {
                item {
                    Column {
                        SectionTitle("Carry on watching")
                        CarryOnRow(carryOn) { nav.push(Screen.VodPlayer(pid, it.itemId, it.seriesId, continueSeries = true)) }
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
                        else -> ChannelCardRow(favs, vm.nowNext, first) {
                            nav.push(Screen.Player(pid, it.streamId, ChannelFilter.Favourites))
                        }
                    }
                }
            }
            if (recent.isNotEmpty()) {
                item {
                    Column {
                        SectionTitle("Recently watched")
                        ChannelCardRow(recent, vm.nowNext) { nav.push(Screen.Player(pid, it.streamId, ChannelFilter.Recent)) }
                    }
                }
            }
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
    modifier: Modifier = Modifier,
    onPlay: (ChannelEntity) -> Unit
) {
    val now by rememberMinuteClock()
    LazyRow(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        items(channels, key = { it.streamId }) { c ->
            LaunchedEffect(c.streamId) { loader.request(c.streamId) }
            val nn = loader.entries[c.streamId]
            TvCard(onClick = { onPlay(c) }, modifier = (if (c == channels.first()) modifier else Modifier).width(272.dp)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ChannelLogo(c.logoUrl, c.name, 64.dp, 38.dp)
                        Muted(c.name, Modifier.padding(start = 10.dp))
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
private fun CarryOnRow(items: List<WatchProgressEntity>, onPlay: (WatchProgressEntity) -> Unit) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        items(items, key = { "${it.kind}${it.itemId}" }) { p ->
            TvCard(onClick = { onPlay(p) }, modifier = Modifier.width(272.dp)) {
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
