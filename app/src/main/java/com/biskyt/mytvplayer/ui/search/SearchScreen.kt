package com.biskyt.mytvplayer.ui.search

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.biskyt.mytvplayer.AppContainer
import com.biskyt.mytvplayer.data.ChannelFilter
import com.biskyt.mytvplayer.data.SearchResults
import com.biskyt.mytvplayer.data.db.ChannelEntity
import com.biskyt.mytvplayer.data.db.ProgrammeHit
import com.biskyt.mytvplayer.ui.LocalNavigator
import com.biskyt.mytvplayer.ui.Screen
import com.biskyt.mytvplayer.ui.appViewModel
import com.biskyt.mytvplayer.ui.common.AppIcons
import com.biskyt.mytvplayer.ui.common.ChannelLogo
import com.biskyt.mytvplayer.ui.common.Muted
import com.biskyt.mytvplayer.ui.common.SectionTitle
import com.biskyt.mytvplayer.ui.common.TabScaffold
import com.biskyt.mytvplayer.ui.common.TvCard
import com.biskyt.mytvplayer.ui.common.TvTextField
import com.biskyt.mytvplayer.ui.common.formatDayTime
import com.biskyt.mytvplayer.ui.common.numberLabel
import com.biskyt.mytvplayer.ui.theme.LocalAccent
import com.biskyt.mytvplayer.ui.theme.Palette
import com.biskyt.mytvplayer.ui.vod.PosterCard
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class SearchViewModel(private val c: AppContainer) : ViewModel() {
    val query = MutableStateFlow("")
    val playlistId: StateFlow<Long> = c.settings.activePlaylistId.stateIn(viewModelScope, SharingStarted.Eagerly, 0L)

    /** Results for the query once typing pauses; the previous results stay up meanwhile. */
    val results: StateFlow<Pair<String, SearchResults>> =
        combine(c.settings.activePlaylistId, query.debounce(300).distinctUntilChanged()) { pid, q -> pid to q }
            .mapLatest { (pid, q) -> q.trim() to c.search.search(pid, q) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "" to SearchResults())
}

/** One search box for channels, TV programmes, movies and series. */
@Composable
fun SearchScreen() {
    val vm = appViewModel { SearchViewModel(it) }
    val nav = LocalNavigator.current
    val query by vm.query.collectAsState()
    val (searched, results) = vm.results.collectAsState().value
    val pid by vm.playlistId.collectAsState()
    // The keyboard is up on arrival, ready to type.
    val editing = rememberSaveable { mutableStateOf(true) }
    TabScaffold(null) {
        LazyColumn(
            contentPadding = PaddingValues(start = 56.dp, end = 56.dp, bottom = 40.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            item {
                TvTextField(
                    value = query,
                    onValueChange = { vm.query.value = it },
                    placeholder = "Search channels, TV programmes, movies and series",
                    modifier = Modifier.fillMaxWidth(),
                    leading = { Icon(AppIcons.Search, null, tint = Palette.textDim, modifier = Modifier.size(20.dp)) },
                    editing = editing
                )
            }
            when {
                searched.isEmpty() -> Unit
                results.isEmpty -> item { Muted("Nothing found for “$searched”.") }
                else -> {
                    section("Channels", results.channels) {
                        ChannelCard(it) { nav.push(Screen.Player(pid, it.streamId, ChannelFilter.All)) }
                    }
                    if (results.programmes.isNotEmpty()) {
                        item {
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                SectionTitle("On TV")
                                results.programmes.forEach { p ->
                                    ProgrammeRow(p) { nav.push(Screen.Player(pid, p.streamId, ChannelFilter.All)) }
                                }
                            }
                        }
                    }
                    section("Movies", results.movies) { PosterCard(it) { nav.push(Screen.MovieDetails(pid, it.id)) } }
                    section("Series", results.series) { PosterCard(it) { nav.push(Screen.SeriesDetails(pid, it.id)) } }
                }
            }
        }
    }
}

/** A titled row of results, left out when there are none. */
private fun <T> LazyListScope.section(title: String, items: List<T>, card: @Composable (T) -> Unit) {
    if (items.isEmpty()) return
    item {
        Column {
            SectionTitle(title)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp)) { items(items) { card(it) } }
        }
    }
}

@Composable
private fun ChannelCard(ch: ChannelEntity, onClick: () -> Unit) {
    TvCard(onClick = onClick, modifier = Modifier.width(280.dp)) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ChannelLogo(ch.logoUrl, ch.name, 56.dp, 34.dp)
            Column {
                Muted(ch.numberLabel)
                Text(ch.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun ProgrammeRow(p: ProgrammeHit, onClick: () -> Unit) {
    TvCard(onClick = onClick, color = Palette.surfaceLow, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            ChannelLogo(p.logoUrl, p.channelName, 56.dp, 34.dp)
            Column(Modifier.weight(1f).padding(start = 14.dp)) {
                Text(p.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Muted(listOfNotNull(p.channelName, p.subtitle).joinToString(" · "))
            }
            if (System.currentTimeMillis() in p.startMs until p.stopMs) {
                Text("ON NOW", style = MaterialTheme.typography.labelMedium, color = LocalAccent.current.color)
            } else {
                Muted(formatDayTime(p.startMs))
            }
        }
    }
}
