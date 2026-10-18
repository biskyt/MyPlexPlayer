package com.biskyt.mytvplayer.ui.vod

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import androidx.paging.map
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.biskyt.mytvplayer.AppContainer
import com.biskyt.mytvplayer.data.VodFilter
import com.biskyt.mytvplayer.data.db.CAT_MOVIE
import com.biskyt.mytvplayer.data.db.CategoryEntity
import com.biskyt.mytvplayer.data.db.PlaylistEntity
import com.biskyt.mytvplayer.ui.LocalNavigator
import com.biskyt.mytvplayer.ui.Screen
import com.biskyt.mytvplayer.ui.appViewModel
import com.biskyt.mytvplayer.ui.common.AppIcons
import com.biskyt.mytvplayer.ui.common.CategoryColumn
import com.biskyt.mytvplayer.ui.common.CategoryEntry
import com.biskyt.mytvplayer.ui.common.Muted
import com.biskyt.mytvplayer.ui.common.Poster
import com.biskyt.mytvplayer.ui.common.Tab
import com.biskyt.mytvplayer.ui.common.TabScaffold
import com.biskyt.mytvplayer.ui.common.TvCard
import com.biskyt.mytvplayer.ui.common.TvTextField
import com.biskyt.mytvplayer.ui.common.formatCount
import com.biskyt.mytvplayer.ui.theme.Palette
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** What a poster in the grid shows: the same for movies and series. */
data class VodCard(val id: Long, val title: String, val year: Int?, val imageUrl: String?)

/** Movies or series ([type] is CAT_MOVIE or CAT_SERIES): categories on the left, a grid of posters on the right. */
@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class VodBrowseViewModel(private val c: AppContainer, val type: String, initialFilter: VodFilter) : ViewModel() {
    private val activePlaylist = c.settings.activePlaylistId
    val isMovies = type == CAT_MOVIE

    val playlist: StateFlow<PlaylistEntity?> = activePlaylist.map { c.playlists.get(it) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val filter = MutableStateFlow(initialFilter)
    val query = MutableStateFlow("")

    val categories: StateFlow<List<CategoryEntity>> = activePlaylist.flatMapLatest { c.vod.observeCategories(it, type) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val total: StateFlow<Int?> = activePlaylist.flatMapLatest {
        if (isMovies) c.vod.observeMovieCount(it) else c.vod.observeSeriesCount(it)
    }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val items: Flow<PagingData<VodCard>> =
        combine(activePlaylist, filter, query.debounce(250)) { pid, f, q -> Triple(pid, f, q) }
            .distinctUntilChanged()
            .flatMapLatest { (pid, f, q) ->
                if (isMovies) {
                    c.vod.pageMovies(pid, f, q).map { page -> page.map { VodCard(it.streamId, it.title, it.year, it.posterUrl) } }
                } else {
                    c.vod.pageSeries(pid, f, q).map { page -> page.map { VodCard(it.seriesId, it.title, it.year, it.coverUrl) } }
                }
            }
            .cachedIn(viewModelScope)

    /** Kept here so coming back from a title returns to the same place in the grid. */
    val gridState = LazyGridState()
    var focusId: Long? = null
    var restoreFocusPending by mutableStateOf(false)

    private var filterJob: Job? = null

    /** Category focus changes the grid after a short pause, so moving through categories stays smooth. */
    fun focusFilter(f: VodFilter) {
        filterJob?.cancel()
        if (f == filter.value) return
        filterJob = viewModelScope.launch {
            delay(300)
            filter.value = f
        }
    }

    fun selectFilter(f: VodFilter) {
        filterJob?.cancel()
        filter.value = f
    }

    fun open(card: VodCard, pid: Long): Screen {
        focusId = card.id
        restoreFocusPending = true
        return if (isMovies) Screen.MovieDetails(pid, card.id) else Screen.SeriesDetails(pid, card.id)
    }
}

@Composable
fun VodBrowseScreen(type: String, initialFilter: VodFilter) {
    val vm = appViewModel(key = "vod-$type") { VodBrowseViewModel(it, type, initialFilter) }
    val filter by vm.filter.collectAsState()
    val categories by vm.categories.collectAsState()
    val playlist by vm.playlist.collectAsState()
    val entries = remember(categories) {
        listOf<CategoryEntry<VodFilter>>(
            CategoryEntry(VodFilter.Favourites, "Favourites"),
            CategoryEntry(VodFilter.Newest, if (vm.isMovies) "Recently added" else "Recently updated"),
            CategoryEntry(VodFilter.All, if (vm.isMovies) "All movies" else "All series")
        ) + categories.map { CategoryEntry<VodFilter>(VodFilter.Category(it.categoryId), it.name, it.channelCount) }
    }
    TabScaffold(if (vm.isMovies) Tab.MOVIES else Tab.SERIES) {
        Row(Modifier.fillMaxSize().padding(horizontal = 56.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            CategoryColumn(entries, filter, vm::focusFilter, vm::selectFilter)
            Column(Modifier.weight(1f)) {
                Header(vm, entries.firstOrNull { it.filter == filter })
                val p = playlist
                when {
                    p != null && p.isM3u -> Muted(
                        "M3U playlists only have live channels. Movies and series come with Xtream Codes accounts.",
                        Modifier.padding(top = 24.dp),
                        maxLines = 2
                    )
                    else -> PosterGrid(vm, p?.id ?: 0)
                }
            }
        }
    }
}

@Composable
private fun Header(vm: VodBrowseViewModel, entry: CategoryEntry<VodFilter>?) {
    val total by vm.total.collectAsState()
    val query by vm.query.collectAsState()
    Row(
        Modifier.padding(bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text(
            entry?.label ?: "",
            style = MaterialTheme.typography.headlineSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(0.45f, fill = false)
        )
        val count = entry?.count ?: total.takeIf { entry?.filter == VodFilter.All }
        if (count != null) Muted("· ${formatCount(count)}")
        TvTextField(
            value = query,
            onValueChange = { vm.query.value = it },
            placeholder = "Filter by title",
            modifier = Modifier.weight(0.55f),
            leading = { Icon(AppIcons.Filter, null, tint = Palette.textDim, modifier = Modifier.size(18.dp)) }
        )
    }
}

@Composable
private fun PosterGrid(vm: VodBrowseViewModel, pid: Long) {
    val nav = LocalNavigator.current
    val items = vm.items.collectAsLazyPagingItems()
    val restore = remember { FocusRequester() }
    if (items.itemCount == 0) {
        Muted(
            if (vm.filter.collectAsState().value == VodFilter.Favourites) {
                "No favourites yet. Open a title and choose “Add to favourites”."
            } else {
                "Nothing here yet. Movies and series load after channels; choose which categories to load in Settings › Languages & categories."
            },
            Modifier.padding(top = 24.dp),
            maxLines = 2
        )
        return
    }
    LazyVerticalGrid(
        columns = GridCells.Fixed(COLUMNS),
        state = vm.gridState,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        contentPadding = PaddingValues(bottom = 40.dp)
    ) {
        items(count = items.itemCount, key = items.itemKey { it.id }) { index ->
            val card = items[index]
            if (card == null) {
                Column {
                    Poster(null, "", POSTER_WIDTH)
                    Spacer(Modifier.height(40.dp))
                }
            } else {
                val wantsFocus = vm.restoreFocusPending && card.id == vm.focusId
                PosterCard(card, if (wantsFocus) Modifier.focusRequester(restore) else Modifier) { nav.push(vm.open(card, pid)) }
                if (wantsFocus) {
                    LaunchedEffect(Unit) { if (runCatching { restore.requestFocus() }.isSuccess) vm.restoreFocusPending = false }
                }
            }
        }
    }
}

@Composable
private fun PosterCard(card: VodCard, modifier: Modifier, onClick: () -> Unit) {
    TvCard(onClick = onClick, color = Palette.background, modifier = modifier) {
        Column(Modifier.padding(6.dp).width(POSTER_WIDTH), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Poster(card.imageUrl, card.title, POSTER_WIDTH)
            Text(
                card.title,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Muted(card.year?.toString() ?: " ")
        }
    }
}

private const val COLUMNS = 6
private val POSTER_WIDTH = 124.dp
