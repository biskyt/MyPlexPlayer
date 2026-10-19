package com.biskyt.mytvplayer.ui.search

import android.util.Log
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
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.biskyt.mytvplayer.AppContainer
import com.biskyt.mytvplayer.data.ChannelFilter
import com.biskyt.mytvplayer.data.SearchResults
import com.biskyt.mytvplayer.data.db.CAT_MOVIE
import com.biskyt.mytvplayer.data.db.CAT_SERIES
import com.biskyt.mytvplayer.data.db.ChannelEntity
import com.biskyt.mytvplayer.data.db.ProgrammeHit
import com.biskyt.mytvplayer.data.db.VodCard
import com.biskyt.mytvplayer.ui.LocalNavigator
import com.biskyt.mytvplayer.ui.Screen
import com.biskyt.mytvplayer.ui.appViewModel
import com.biskyt.mytvplayer.ui.common.AppIcons
import com.biskyt.mytvplayer.ui.common.ChannelLogo
import com.biskyt.mytvplayer.ui.common.MenuOption
import com.biskyt.mytvplayer.ui.common.Muted
import com.biskyt.mytvplayer.ui.common.OnNowLabel
import com.biskyt.mytvplayer.ui.common.OneLine
import com.biskyt.mytvplayer.ui.common.PosterCard
import com.biskyt.mytvplayer.ui.common.SectionTitle
import com.biskyt.mytvplayer.ui.common.SidePanel
import com.biskyt.mytvplayer.ui.common.TabScaffold
import com.biskyt.mytvplayer.ui.common.TvCard
import com.biskyt.mytvplayer.ui.common.TvTextField
import com.biskyt.mytvplayer.ui.common.formatDayTime
import com.biskyt.mytvplayer.ui.common.isLive
import com.biskyt.mytvplayer.ui.common.numberLabel
import com.biskyt.mytvplayer.ui.common.rememberFirstFocus
import com.biskyt.mytvplayer.ui.common.rememberMinuteClock
import com.biskyt.mytvplayer.ui.theme.Palette
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class SearchViewModel(private val c: AppContainer, initialQuery: String = "") : ViewModel() {
    val query = MutableStateFlow(initialQuery)

    /** What was searched, in which playlist, and what was found (null: too short to search; see [failed]). */
    data class Found(val playlistId: Long = 0, val query: String = "", val results: SearchResults? = null, val failed: Boolean = false)

    /** Results for the query once typing pauses; the previous results stay up meanwhile. */
    val found: StateFlow<Found> =
        combine(c.settings.activePlaylistId, query.debounce(300).map { it.trim() }.distinctUntilChanged()) { pid, q -> pid to q }
            .mapLatest { (pid, q) ->
                // A failed search (e.g. the database busy rebuilding) is shown, never allowed to end searching.
                try {
                    Found(pid, q, c.search.search(pid, q))
                } catch (e: CancellationException) {
                    throw e
                } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                    Log.w("Search", "Search failed", e)
                    Found(pid, q, failed = true)
                }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), Found())

    /** The active playlist's favourite channels, for the options panel. */
    val favouriteChannels: StateFlow<Set<Long>> = c.settings.activePlaylistId
        .flatMapLatest { c.channels.observeFavouriteIds(it) }.map { it.toSet() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    fun setChannelFavourite(pid: Long, streamId: Long, favourite: Boolean) =
        viewModelScope.launch { c.channels.setFavourite(pid, streamId, favourite) }

    fun vodFavourite(pid: Long, type: String, id: Long) = c.vod.observeFavourite(pid, type, id)

    fun setVodFavourite(pid: Long, type: String, id: Long, favourite: Boolean) =
        viewModelScope.launch { c.vod.setFavourite(pid, type, id, favourite) }

    /** The channel a programme is on (for catch-up in its options). */
    suspend fun channel(pid: Long, streamId: Long) = c.channels.get(pid, streamId)
}

/** A search result whose options panel is open. */
private sealed interface Picked {
    data class Channel(val streamId: Long, val name: String) : Picked
    data class Vod(val type: String, val card: VodCard) : Picked
}

/** One search box for channels, TV programmes, movies and series. */
@Composable
fun SearchScreen(initialQuery: String = "") {
    val vm = appViewModel { SearchViewModel(it, initialQuery) }
    val nav = LocalNavigator.current
    val query by vm.query.collectAsState()
    val found = vm.found.collectAsState().value
    val pid = found.playlistId
    val searched = found.query
    val results = found.results
    val now by rememberMinuteClock()
    // The keyboard is up on arrival, ready to type.
    val editing = rememberSaveable { mutableStateOf(true) }
    var picked by remember { mutableStateOf<Picked?>(null) }
    TabScaffold(null) {
        LazyColumn(
            contentPadding = PaddingValues(start = 56.dp, end = 56.dp, bottom = 40.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
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
                found.failed -> item { Muted("Search isn't working right now. Try again in a moment.") }
                results == null -> item { Muted("Keep typing: at least 2 letters, or a channel number.") }
                results.isEmpty -> item { Muted("Nothing found for “$searched”.") }
                else -> {
                    // Holding OK (or the remote's Menu button) on a result opens its options.
                    section("Channels", results.channels, { it.streamId }) {
                        ChannelCard(it, { picked = Picked.Channel(it.streamId, it.name) }) {
                            nav.push(Screen.Player(pid, it.streamId, ChannelFilter.All))
                        }
                    }
                    if (results.programmes.isNotEmpty()) {
                        item { SectionTitle("On TV", Modifier.padding(top = SECTION_GAP)) }
                        items(results.programmes, key = { "p${it.streamId}:${it.startMs}" }) { p ->
                            ProgrammeRow(p, now, { picked = Picked.Channel(p.streamId, p.channelName) }) {
                                nav.push(Screen.Player(pid, p.streamId, ChannelFilter.All))
                            }
                        }
                    }
                    section("Movies", results.movies, { it.id }) {
                        PosterCard(it, onLongClick = { picked = Picked.Vod(CAT_MOVIE, it) }) { nav.push(Screen.MovieDetails(pid, it.id)) }
                    }
                    section("Series", results.series, { it.id }) {
                        PosterCard(it, onLongClick = { picked = Picked.Vod(CAT_SERIES, it) }) { nav.push(Screen.SeriesDetails(pid, it.id)) }
                    }
                }
            }
        }
    }
    picked?.let { ResultOptions(it, pid, vm) { picked = null } }
}

/** What can be done with a result: watch or open it, favourites, and for channels catch-up and the guide source. */
@Composable
private fun ResultOptions(item: Picked, pid: Long, vm: SearchViewModel, onDismiss: () -> Unit) {
    val nav = LocalNavigator.current
    fun open(screen: Screen) {
        onDismiss()
        nav.push(screen)
    }
    SidePanel(onDismiss) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when (item) {
                is Picked.Channel -> {
                    val favourites by vm.favouriteChannels.collectAsState()
                    val channel by produceState<ChannelEntity?>(null, item) { value = vm.channel(pid, item.streamId) }
                    val favourite = item.streamId in favourites
                    Text(item.name, style = MaterialTheme.typography.titleLarge, maxLines = 2, modifier = Modifier.padding(bottom = 8.dp))
                    MenuOption("Watch", modifier = rememberFirstFocus()) { open(Screen.Player(pid, item.streamId, ChannelFilter.All)) }
                    MenuOption(if (favourite) "Remove from favourites" else "Add to favourites") {
                        vm.setChannelFavourite(pid, item.streamId, !favourite)
                    }
                    channel?.takeIf { it.archiveDays > 0 }?.let { ch ->
                        MenuOption("Catch-up", "Programmes from the last ${ch.archiveDays} days") { open(Screen.Catchup(pid, ch.streamId)) }
                    }
                    MenuOption("Change guide source…", "Pick which guide data this channel uses") {
                        open(Screen.GuideMatching(pid, item.streamId))
                    }
                }
                is Picked.Vod -> {
                    val favourite by remember(item) { vm.vodFavourite(pid, item.type, item.card.id) }.collectAsState(false)
                    val movie = item.type == CAT_MOVIE
                    Text(
                        item.card.title,
                        style = MaterialTheme.typography.titleLarge,
                        maxLines = 2,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    MenuOption(if (movie) "Open movie" else "Open series", modifier = rememberFirstFocus()) {
                        open(if (movie) Screen.MovieDetails(pid, item.card.id) else Screen.SeriesDetails(pid, item.card.id))
                    }
                    MenuOption(if (favourite) "Remove from favourites" else "Add to favourites") {
                        vm.setVodFavourite(pid, item.type, item.card.id, !favourite)
                    }
                }
            }
        }
    }
}

/** A titled row of results, left out when there are none. */
private fun <T> LazyListScope.section(title: String, items: List<T>, key: (T) -> Long, card: @Composable (T) -> Unit) {
    if (items.isEmpty()) return
    item {
        Column(Modifier.padding(top = SECTION_GAP)) {
            SectionTitle(title)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp)) { items(items, key = key) { card(it) } }
        }
    }
}

/** Long channel names are common ("UK: CHANNEL ONE HD FHD (backup)"): they scroll while the card is focused. */
@Composable
private fun ChannelCard(ch: ChannelEntity, onLongClick: () -> Unit, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    TvCard(onClick = onClick, onLongClick = onLongClick, modifier = Modifier.width(340.dp).onFocusChanged { focused = it.hasFocus }) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ChannelLogo(ch.logoUrl, ch.name, 48.dp, 30.dp)
            Column {
                Muted(ch.numberLabel)
                OneLine(ch.name, focused, MaterialTheme.typography.labelLarge)
            }
        }
    }
}

@Composable
private fun ProgrammeRow(p: ProgrammeHit, now: Long, onLongClick: () -> Unit, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    TvCard(
        onClick = onClick,
        onLongClick = onLongClick,
        color = Palette.surfaceLow,
        modifier = Modifier.fillMaxWidth().onFocusChanged { focused = it.hasFocus }
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            ChannelLogo(p.logoUrl, p.channelName, 56.dp, 34.dp)
            Column(Modifier.weight(1f).padding(start = 14.dp)) {
                OneLine(p.title, focused, MaterialTheme.typography.titleSmall)
                OneLine(
                    listOfNotNull(p.channelName, p.subtitle).joinToString(" · "),
                    focused,
                    MaterialTheme.typography.bodyMedium,
                    color = Palette.textMuted
                )
            }
            if (isLive(p.startMs, p.stopMs, now)) OnNowLabel() else Muted(formatDayTime(p.startMs, now))
        }
    }
}

private val SECTION_GAP = 18.dp
