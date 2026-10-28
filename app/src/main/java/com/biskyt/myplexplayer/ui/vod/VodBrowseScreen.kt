package com.biskyt.myplexplayer.ui.vod

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.biskyt.myplexplayer.AppContainer
import com.biskyt.myplexplayer.R
import com.biskyt.myplexplayer.appString
import com.biskyt.myplexplayer.data.VodShow
import com.biskyt.myplexplayer.data.VodSort
import com.biskyt.myplexplayer.data.VodView
import com.biskyt.myplexplayer.data.db.CAT_MOVIE
import com.biskyt.myplexplayer.data.db.CAT_SERIES
import com.biskyt.myplexplayer.data.db.CategoryEntity
import com.biskyt.myplexplayer.data.db.PlaylistEntity
import com.biskyt.myplexplayer.data.db.VodCard
import com.biskyt.myplexplayer.data.label
import com.biskyt.myplexplayer.ui.LocalNavigator
import com.biskyt.myplexplayer.ui.PickedLists
import com.biskyt.myplexplayer.ui.Screen
import com.biskyt.myplexplayer.ui.appViewModel
import com.biskyt.myplexplayer.ui.common.AppIcons
import com.biskyt.myplexplayer.ui.common.FavouriteMover
import com.biskyt.myplexplayer.ui.common.ItemAction
import com.biskyt.myplexplayer.ui.common.ItemActionsPanel
import com.biskyt.myplexplayer.ui.common.KeepInView
import com.biskyt.myplexplayer.ui.common.LocalCardFocused
import com.biskyt.myplexplayer.ui.common.MovableTile
import com.biskyt.myplexplayer.ui.common.MoveBackHandler
import com.biskyt.myplexplayer.ui.common.MoveHint
import com.biskyt.myplexplayer.ui.common.MoveLayout
import com.biskyt.myplexplayer.ui.common.Muted
import com.biskyt.myplexplayer.ui.common.OneLine
import com.biskyt.myplexplayer.ui.common.PillButton
import com.biskyt.myplexplayer.ui.common.Poster
import com.biskyt.myplexplayer.ui.common.ProgressLine
import com.biskyt.myplexplayer.ui.common.Tab
import com.biskyt.myplexplayer.ui.common.TabScaffold
import com.biskyt.myplexplayer.ui.common.TvCard
import com.biskyt.myplexplayer.ui.common.collectIds
import com.biskyt.myplexplayer.ui.common.formatRuntime
import com.biskyt.myplexplayer.ui.common.markWatched
import com.biskyt.myplexplayer.ui.common.moveAction
import com.biskyt.myplexplayer.ui.common.restoreFocusIf
import com.biskyt.myplexplayer.ui.common.vodActions
import com.biskyt.myplexplayer.ui.common.vodDetails
import com.biskyt.myplexplayer.ui.theme.LocalAccent
import com.biskyt.myplexplayer.ui.theme.Palette
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Movies or series ([type] is CAT_MOVIE or CAT_SERIES): a grid of posters, with its category, filter and order in pills. */
@OptIn(ExperimentalCoroutinesApi::class)
class VodBrowseViewModel(
    private val c: AppContainer,
    val type: String,
    initialView: VodView?,
    private val picked: PickedLists
) : ViewModel() {
    private val activePlaylist = c.settings.activePlaylistId
    val isMovies = type == CAT_MOVIE

    val playlist: StateFlow<PlaylistEntity?> = activePlaylist.map { c.playlists.get(it) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** The view asked for, else the one picked earlier while the app has been open, else everything, newest first. */
    val view = MutableStateFlow(initialView ?: picked.vod[type] ?: VodView())

    /** Shows [v], from its first title. */
    fun show(v: VodView) {
        view.value = v
        picked.vod[type] = v
        focusId = null
        restoreFocusPending = true
    }

    val categories: StateFlow<List<CategoryEntity>> = activePlaylist.flatMapLatest { c.vod.observeCategories(it, type) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** The favourites among the titles, marked on their posters. */
    val favouriteIds = c.favourites.of(type)

    val items: Flow<PagingData<VodCard>> =
        combine(activePlaylist, view) { pid, v -> pid to v }
            .distinctUntilChanged()
            .flatMapLatest { (pid, v) -> c.vod.pageCards(type, pid, v) }
            .cachedIn(viewModelScope)

    /** A poster whose options are open. */
    var optionsFor by mutableStateOf<VodCard?>(null)

    /** A pill whose choices are open. */
    var picking by mutableStateOf<Pick?>(null)

    /** A pill, and its choices' title. */
    enum class Pick(
        @StringRes val title: Int
    ) {
        CATEGORY(R.string.vod_pick_category),
        SHOW(R.string.vod_pick_filter),
        SORT(R.string.vod_pick_sort)
    }

    /**
     * A poster leaving the list with the remote on it: one taken off Favourites while they're shown, or the film last
     * opened, deleted on its page. Focus moves on once it has gone.
     */
    var removedId by mutableStateOf<Long?>(null)

    init {
        // The film last opened, deleted on its page: the list catches up only after the remote has gone back to it.
        if (isMovies) {
            viewModelScope.launch {
                c.vod.deletedMovies.collect { (pid, id) -> if (pid == playlist.value?.id && id == focusId) removedId = id }
            }
        }
    }

    /** Moving a favourite within Favourites. */
    val mover = FavouriteMover(viewModelScope, c.favourites) { playlist.value?.id ?: 0 }

    /** Whether [card] has been watched, for its menu; null where it can't be marked. */
    suspend fun watched(card: VodCard): Boolean? = playlist.value?.let { c.vod.watched(it.id, type, card) }

    fun setWatched(pid: Long, card: VodCard, watched: Boolean) = viewModelScope.launch {
        c.vod.setWatched(pid, card.id, series = card.id.takeIf { type == CAT_SERIES }, watched)
    }

    /** Whether a film can be deleted here (the server allows it), for its menu. */
    suspend fun canDelete(): Boolean = type == CAT_MOVIE && playlist.value?.let { c.vod.canDelete(it.id) } == true

    /** Deletes film [card] on the server; the grid follows (refused or out of reach, a notice says so). */
    fun delete(pid: Long, card: VodCard) = viewModelScope.launch { c.vod.deleteMovie(pid, card.id) }

    /** A series' "Delete watched episodes", for its menu. */
    fun autoDelete(pid: Long, card: VodCard): AutoDeleteChoice? =
        if (type == CAT_SERIES) AutoDeleteChoice(viewModelScope, c.vod, pid, card.id) else null

    fun setFavourite(card: VodCard, favourite: Boolean) = viewModelScope.launch {
        c.vod.setFavourite(playlist.value?.id ?: return@launch, type, card.id, favourite)
    }

    /** The title last opened, to return focus to it when coming back. */
    var focusId: Long? = null
    var restoreFocusPending by mutableStateOf(false)

    /** Downloads the movie and series lists (progress shows in the top bar). */
    fun loadNow() {
        c.sync.refreshVod(playlist.value?.id ?: return)
    }

    fun open(card: VodCard, pid: Long): Screen {
        focusId = card.id
        restoreFocusPending = true
        return vodDetails(pid, type, card.id)
    }
}

@Composable
fun VodBrowseScreen(type: String, initialView: VodView?) {
    val nav = LocalNavigator.current
    val vm = appViewModel(key = "vod-$type") { VodBrowseViewModel(it, type, initialView, nav.picked) }
    val view by vm.view.collectAsState()
    val categories by vm.categories.collectAsState()
    val playlist by vm.playlist.collectAsState()
    val tab = remember { FocusRequester() }
    val pills = remember { FocusRequester() }
    // Where the pills start across the screen: posters reaching under them go up to them, not to the menu.
    var pillsLeft by remember { mutableFloatStateOf(Float.MAX_VALUE) }
    TabScaffold(if (vm.isMovies) Tab.MOVIES else Tab.SERIES, tabFocus = tab) {
        Column(Modifier.fillMaxSize().padding(horizontal = 56.dp)) {
            Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), horizontalArrangement = Arrangement.End) {
                Row(
                    Modifier.onGloballyPositioned { pillsLeft = it.boundsInRoot().left },
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    val category = view.category?.let { id -> categories.firstOrNull { it.categoryId == id }?.label }
                    PillButton(
                        stringResource(R.string.vod_pill_category, category ?: stringResource(R.string.vod_all)),
                        { vm.picking = VodBrowseViewModel.Pick.CATEGORY },
                        Modifier.focusRequester(pills)
                    )
                    PillButton(stringResource(R.string.vod_pill_filter, showName(view.show)), { vm.picking = VodBrowseViewModel.Pick.SHOW })
                    // Favourites keep the user's own order.
                    if (view.show != VodShow.FAVOURITES) {
                        PillButton(
                            stringResource(R.string.vod_pill_sort, sortName(view.sort, vm.isMovies)),
                            { vm.picking = VodBrowseViewModel.Pick.SORT }
                        )
                    }
                }
            }
            MoveHint(vm.mover, type, MoveLayout.GRID, Modifier.padding(bottom = 8.dp))
            PosterGrid(vm, playlist?.id ?: 0, GridRoutes(tab, pills) { pillsLeft })
        }
    }
    // A category no longer listed (hidden in Settings, gone from the server): back to all of them.
    LaunchedEffect(categories, view.category) {
        if (categories.isNotEmpty() && view.category != null && categories.none { it.categoryId == view.category }) {
            vm.show(view.copy(category = null))
        }
    }
    vm.optionsFor?.let { card -> PosterOptions(vm, card, view, playlist?.id ?: 0) }
    vm.picking?.let { Picker(vm, it, view, categories) }
}

/** Where the remote goes from the grid's top row: up to the menu's [tab] or the [pills], right to them from a short row. */
private class GridRoutes(val tab: FocusRequester, val pills: FocusRequester, val pillsLeft: () -> Float)

private fun showName(show: VodShow) = appString(
    when (show) {
        VodShow.ALL -> R.string.vod_all
        VodShow.UNWATCHED -> R.string.vod_unwatched
        VodShow.FAVOURITES -> R.string.common_favourites
    }
)

private fun sortName(sort: VodSort, movies: Boolean) = appString(
    when (sort) {
        VodSort.NEWEST -> if (movies) R.string.vod_recently_added else R.string.vod_newest_episode
        VodSort.TITLE -> R.string.vod_sort_title
        VodSort.YEAR -> R.string.vod_sort_year
    }
)

/** A pill's choices, the current one first (ticked, with the remote on it). */
@Composable
private fun Picker(vm: VodBrowseViewModel, pick: VodBrowseViewModel.Pick, view: VodView, categories: List<CategoryEntity>) {
    val close = { vm.picking = null }
    val choices: List<Pair<String, VodView>> = when (pick) {
        VodBrowseViewModel.Pick.CATEGORY -> listOf(appString(R.string.vod_all) to view.copy(category = null)) +
            categories.map { it.label to view.copy(category = it.categoryId) }
        VodBrowseViewModel.Pick.SHOW -> VodShow.entries.map { showName(it) to view.copy(show = it) }
        VodBrowseViewModel.Pick.SORT -> VodSort.entries.map { sortName(it, vm.isMovies) to view.copy(sort = it) }
    }
    val actions = choices.sortedByDescending { it.second == view }.map { (label, v) ->
        ItemAction.Run(if (v == view) "✓ $label" else label) {
            close()
            if (v != view) vm.show(v)
        }
    }
    ItemActionsPanel(appString(pick.title), actions, close)
}

/** Holding OK on a poster: open it, favourites, and in Favourites, move it. */
@Composable
private fun PosterOptions(vm: VodBrowseViewModel, card: VodCard, view: VodView, pid: Long) {
    val close = { vm.optionsFor = null }
    val favourites by vm.favouriteIds.collectAsState()
    val favourite = favourites?.let { card.id in it }
    val inFavourites = view.show == VodShow.FAVOURITES
    val watched by produceState<Boolean?>(null, card) { value = vm.watched(card) }
    val canDelete by produceState(false, card) { value = vm.canDelete() }
    val autoDelete = remember(card) { vm.autoDelete(pid, card) }
    val actions = vodActions(pid, vm.type, card.id, favourite) {
        vm.setFavourite(card, it)
        if (!it && inFavourites) {
            close()
            vm.removedId = card.id
        }
    } + listOfNotNull(
        if (inFavourites && favourite == true) moveAction(vm.mover, vm.type, card.id, close) else null,
        // Last: it's read once the menu is open, and mustn't move the entries above it.
        watched?.let { w ->
            markWatched(w) {
                close()
                vm.setWatched(pid, card, it)
            }
        },
        if (canDelete) deleteAction(card.title) { vm.delete(pid, card) } else null,
        autoDelete?.action()
    )
    ItemActionsPanel(card.title, actions, close)
}

@Composable
private fun PosterGrid(vm: VodBrowseViewModel, pid: Long, routes: GridRoutes) {
    val nav = LocalNavigator.current
    val items = vm.items.collectAsLazyPagingItems()
    val favourites = vm.favouriteIds.collectIds()
    val view by vm.view.collectAsState()
    if (items.itemCount == 0) {
        when {
            view.show == VodShow.FAVOURITES -> Muted(stringResource(R.string.vod_no_favourites), Modifier.padding(top = 24.dp))
            view.category != null || view.show != VodShow.ALL -> Muted(
                stringResource(R.string.vod_none_shown),
                Modifier.padding(top = 24.dp)
            )
            else -> Column(Modifier.padding(top = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Muted(stringResource(R.string.vod_nothing_here), maxLines = 2)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    PillButton(stringResource(R.string.vod_load_now), vm::loadNow, primary = true)
                    PillButton(stringResource(R.string.vod_choose_categories), { nav.push(Screen.Categories(pid)) })
                }
            }
        }
        return
    }
    MoveBackHandler(vm.mover, view)
    val state = rememberLazyGridState()
    vm.removedId?.let { removed ->
        LaunchedEffect(removed) {
            snapshotFlow { items.itemSnapshotList.none { it?.id == removed } }.first { it }
            vm.focusId = null
            vm.restoreFocusPending = true
            vm.removedId = null
        }
    }
    KeepInView(state) { vm.mover.movingId(vm.type)?.let { p -> items.itemSnapshotList.indexOfFirst { it?.id == p } } }
    // Each poster's width, worked out once for the grid rather than measured in every cell.
    BoxWithConstraints {
        PosterGridCells(
            vm,
            pid,
            routes,
            state,
            items,
            favourites,
            (maxWidth - GAP * (COLUMNS - 1)) / COLUMNS - CARD_PADDING * 2
        )
    }
}

@Composable
@Suppress("LongParameterList") // the grid's state, passed down rather than read again
private fun PosterGridCells(
    vm: VodBrowseViewModel,
    pid: Long,
    routes: GridRoutes,
    state: androidx.compose.foundation.lazy.grid.LazyGridState,
    items: androidx.paging.compose.LazyPagingItems<VodCard>,
    favourites: Set<Long>,
    posterWidth: androidx.compose.ui.unit.Dp
) {
    val nav = LocalNavigator.current
    // Back from a title that's no longer listed (deleted from its page, say): the first takes the remote instead.
    val focusGone = vm.focusId.let { id -> id == null || items.itemSnapshotList.none { it?.id == id } }
    LazyVerticalGrid(
        columns = GridCells.Fixed(COLUMNS),
        state = state,
        horizontalArrangement = Arrangement.spacedBy(GAP),
        verticalArrangement = Arrangement.spacedBy(GAP),
        contentPadding = PaddingValues(bottom = 40.dp)
    ) {
        items(count = items.itemCount, key = items.itemKey { it.id }) { index ->
            val card = items[index]
            if (card == null) {
                Box(Modifier.fillMaxWidth().height(PLACEHOLDER_HEIGHT))
            } else {
                // Back from a title, on it; after a change of view or one taken off Favourites, on the first.
                val wantsFocus = vm.restoreFocusPending && (if (focusGone) index == 0 else vm.focusId == card.id)
                val lastOfTopRow = index == minOf(items.itemCount, COLUMNS) - 1
                MovableTile(vm.mover, vm.type, card.id, MoveLayout.GRID, COLUMNS) {
                    GridCard(
                        card,
                        vm.isMovies,
                        posterWidth,
                        Modifier.restoreFocusIf(wantsFocus) { vm.restoreFocusPending = false }
                            .then(if (index < COLUMNS) topRow(routes, lastOfTopRow) else Modifier),
                        favourite = card.id in favourites,
                        onLongClick = { vm.optionsFor = card }
                    ) { nav.push(vm.open(card, pid)) }
                }
            }
        }
    }
}

/**
 * A top-row poster's routes (the owner's rule): up goes to the pills only from a poster reaching under them, otherwise
 * to the menu; right from the row's last poster goes to the pills only when it doesn't reach under them (a short row),
 * and otherwise nowhere.
 */
@Composable
private fun topRow(routes: GridRoutes, last: Boolean): Modifier {
    var right by remember { mutableFloatStateOf(0f) }
    return Modifier.onGloballyPositioned { right = it.boundsInRoot().right }.focusProperties {
        val underPills = right > routes.pillsLeft()
        up = if (underPills) routes.pills else routes.tab
        if (last) this.right = if (underPills) FocusRequester.Cancel else routes.pills
    }
}

/**
 * A poster in a grid: watched films and series ticked, a film part-watched with its progress, a series with its
 * unwatched episodes counted; its year and running time (or episodes) show while it has focus.
 */
@Composable
@Suppress("LongParameterList") // a card: what it shows and what it does
private fun GridCard(
    card: VodCard,
    movie: Boolean,
    width: androidx.compose.ui.unit.Dp,
    modifier: Modifier,
    favourite: Boolean,
    onLongClick: () -> Unit,
    onClick: () -> Unit
) {
    TvCard(onClick = onClick, onLongClick = onLongClick, color = Palette.background, modifier = modifier) {
        Column(Modifier.padding(CARD_PADDING), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Box {
                Poster(card.imageUrl, card.title, width)
                Marks(card, favourite)
            }
            OneLine(card.title, MaterialTheme.typography.labelMedium)
            // Only the focused card says more, so the grid stays calm; the line is kept so nothing moves.
            Box(Modifier.height(16.dp)) {
                if (LocalCardFocused.current) Muted(cardLine(card, movie))
            }
        }
    }
}

/** A poster's marks: watched (a tick) or episodes unwatched (a count), favourite, and a film's progress. */
@Composable
private fun androidx.compose.foundation.layout.BoxScope.Marks(card: VodCard, favourite: Boolean) {
    val accent = LocalAccent.current
    val unwatched = card.unwatched
    if (card.watched) {
        Box(
            Modifier.align(Alignment.TopEnd).padding(5.dp).size(22.dp).clip(CircleShape).background(accent.color),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Rounded.Check, stringResource(R.string.vod_watched), tint = accent.onAccent, modifier = Modifier.size(15.dp))
        }
    } else if (unwatched != null && unwatched > 0) {
        val description = pluralStringResource(R.plurals.vod_unwatched_episodes, unwatched, unwatched)
        Text(
            unwatched.toString(),
            style = MaterialTheme.typography.labelMedium,
            color = accent.onAccent,
            modifier = Modifier.align(Alignment.TopEnd).padding(5.dp).clip(RoundedCornerShape(50)).background(accent.color)
                .padding(horizontal = 7.dp, vertical = 1.dp).semantics { contentDescription = description }
        )
    }
    if (favourite) {
        Icon(
            AppIcons.Heart,
            stringResource(R.string.common_favourite_mark),
            tint = accent.color,
            modifier = Modifier.align(Alignment.TopStart).padding(6.dp).size(16.dp)
        )
    }
    card.partWatched?.let { ProgressLine(it, Modifier.align(Alignment.BottomCenter).padding(6.dp).fillMaxWidth()) }
}

/** "2019 · 1 h 52 min", or a series' "2021 · 24 episodes". */
@Composable
private fun cardLine(card: VodCard, movie: Boolean): String = listOfNotNull(
    card.year?.toString(),
    if (movie) {
        card.durationSec?.takeIf { it > 0 }?.let(::formatRuntime)
    } else {
        card.episodes?.let { pluralStringResource(R.plurals.vod_episode_count, it, it) }
    }
).joinToString(" · ")

private const val COLUMNS = 7
private val GAP = 14.dp
private val CARD_PADDING = 5.dp
private val PLACEHOLDER_HEIGHT = 190.dp
