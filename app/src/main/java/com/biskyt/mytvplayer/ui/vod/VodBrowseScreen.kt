package com.biskyt.mytvplayer.ui.vod

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import androidx.paging.map
import com.biskyt.mytvplayer.AppContainer
import com.biskyt.mytvplayer.R
import com.biskyt.mytvplayer.data.VodFilter
import com.biskyt.mytvplayer.data.db.CAT_MOVIE
import com.biskyt.mytvplayer.data.db.CategoryEntity
import com.biskyt.mytvplayer.data.db.PlaylistEntity
import com.biskyt.mytvplayer.data.db.VodCard
import com.biskyt.mytvplayer.ui.LocalNavigator
import com.biskyt.mytvplayer.ui.Screen
import com.biskyt.mytvplayer.ui.appViewModel
import com.biskyt.mytvplayer.ui.common.CategoryColumn
import com.biskyt.mytvplayer.ui.common.CategoryEntry
import com.biskyt.mytvplayer.ui.common.CategoryFilter
import com.biskyt.mytvplayer.ui.common.ListHeader
import com.biskyt.mytvplayer.ui.common.Muted
import com.biskyt.mytvplayer.ui.common.POSTER_WIDTH
import com.biskyt.mytvplayer.ui.common.PillButton
import com.biskyt.mytvplayer.ui.common.Poster
import com.biskyt.mytvplayer.ui.common.PosterCard
import com.biskyt.mytvplayer.ui.common.Tab
import com.biskyt.mytvplayer.ui.common.TabScaffold
import com.biskyt.mytvplayer.ui.common.categoryEntries
import com.biskyt.mytvplayer.ui.common.restoreFocusIf
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
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

/** Movies or series ([type] is CAT_MOVIE or CAT_SERIES): categories on the left, a grid of posters on the right. */
@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class VodBrowseViewModel(private val c: AppContainer, val type: String, initialFilter: VodFilter) : ViewModel() {
    private val activePlaylist = c.settings.activePlaylistId
    val isMovies = type == CAT_MOVIE

    val playlist: StateFlow<PlaylistEntity?> = activePlaylist.map { c.playlists.get(it) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val filters = CategoryFilter(viewModelScope, initialFilter)
    val query = MutableStateFlow("")

    val categories: StateFlow<List<CategoryEntity>> = activePlaylist.flatMapLatest { c.vod.observeCategories(it, type) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val total: StateFlow<Int?> = activePlaylist.flatMapLatest { c.vod.observeCount(type, it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val items: Flow<PagingData<VodCard>> =
        combine(activePlaylist, filters.value, query.debounce(250)) { pid, f, q -> Triple(pid, f, q) }
            .distinctUntilChanged()
            .flatMapLatest { (pid, f, q) -> c.vod.pageCards(type, pid, f, q) }
            .cachedIn(viewModelScope)

    /** The title last opened, to return focus to it when coming back. */
    var focusId: Long? = null
    var restoreFocusPending by mutableStateOf(false)

    /** Downloads the movie and series lists (progress shows in the top bar). */
    fun loadNow() {
        c.sync.refreshVod(playlist.value?.id ?: return)
    }

    /** Hides a category (from its options in the column); if it's the one shown, all titles show instead. */
    fun hideCategory(cat: CategoryEntity) = viewModelScope.launch {
        if (filters.value.value == VodFilter.Category(cat.categoryId)) filters.select(VodFilter.All)
        c.playlists.hideCategory(cat)
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
    val filter by vm.filters.value.collectAsState()
    val categories by vm.categories.collectAsState()
    val playlist by vm.playlist.collectAsState()
    val favourites = stringResource(R.string.common_favourites)
    val newest = stringResource(if (vm.isMovies) R.string.vod_recently_added else R.string.vod_recently_updated)
    val all = stringResource(if (vm.isMovies) R.string.vod_all_movies else R.string.vod_all_series)
    val entries = remember(categories, favourites, newest, all) {
        listOf<CategoryEntry<VodFilter>>(
            CategoryEntry(VodFilter.Favourites, favourites),
            CategoryEntry(VodFilter.Newest, newest),
            CategoryEntry(VodFilter.All, all)
        ) + categoryEntries<VodFilter>(categories) { VodFilter.Category(it.categoryId) }
    }
    TabScaffold(if (vm.isMovies) Tab.MOVIES else Tab.SERIES) {
        Row(Modifier.fillMaxSize().padding(horizontal = 56.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            CategoryColumn(entries, filter, vm.filters::focus, vm.filters::select, vm::hideCategory)
            Column(Modifier.weight(1f)) {
                Header(vm, entries.firstOrNull { it.filter == filter })
                val p = playlist
                when {
                    p != null && !p.hasVod -> Muted(
                        stringResource(R.string.vod_live_only),
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
    val count = entry?.count ?: total.takeIf { entry?.filter == VodFilter.All }
    ListHeader(entry?.label.orEmpty(), count, query, { vm.query.value = it }, stringResource(R.string.vod_filter_hint))
}

@Composable
private fun PosterGrid(vm: VodBrowseViewModel, pid: Long) {
    val nav = LocalNavigator.current
    val items = vm.items.collectAsLazyPagingItems()
    if (items.itemCount == 0) {
        if (vm.filters.value.collectAsState().value == VodFilter.Favourites) {
            Muted(stringResource(R.string.vod_no_favourites), Modifier.padding(top = 24.dp))
        } else {
            Column(Modifier.padding(top = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Muted(stringResource(R.string.vod_nothing_here), maxLines = 2)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    PillButton(stringResource(R.string.vod_load_now), vm::loadNow, primary = true)
                    PillButton(stringResource(R.string.vod_choose_categories), { nav.push(Screen.Categories(pid)) })
                }
            }
        }
        return
    }
    LazyVerticalGrid(
        columns = GridCells.Fixed(COLUMNS),
        state = rememberLazyGridState(),
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
                PosterCard(card, Modifier.restoreFocusIf(wantsFocus) { vm.restoreFocusPending = false }) { nav.push(vm.open(card, pid)) }
            }
        }
    }
}

private const val COLUMNS = 6
