package com.biskyt.myplexplayer.ui.phone

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import com.biskyt.myplexplayer.R
import com.biskyt.myplexplayer.data.VodShow
import com.biskyt.myplexplayer.data.VodView
import com.biskyt.myplexplayer.data.db.VodCard
import com.biskyt.myplexplayer.ui.LocalNavigator
import com.biskyt.myplexplayer.ui.common.Muted
import com.biskyt.myplexplayer.ui.common.collectIds
import com.biskyt.myplexplayer.ui.theme.FormFactor
import com.biskyt.myplexplayer.ui.theme.LocalFormFactor
import com.biskyt.myplexplayer.ui.vod.VodBrowsePanels
import com.biskyt.myplexplayer.ui.vod.VodBrowseViewModel
import com.biskyt.myplexplayer.ui.vod.categoryPill
import com.biskyt.myplexplayer.ui.vod.emptyTitles
import com.biskyt.myplexplayer.ui.vod.showName
import com.biskyt.myplexplayer.ui.vod.sortName

// Movies and TV Shows on a phone or an unfolded foldable (the owner, 2026-11-25): the TV's Category, Filter and Sort
// pills, in a row that scrolls sideways, over a grid of posters (three across on a phone, as many as fit when unfolded).

/** Films or series, from the TV's [VodBrowseViewModel]: the same view, choices and paging. */
@Composable
fun PhoneBrowse(vm: VodBrowseViewModel) {
    val view by vm.view.collectAsState()
    val categories by vm.categories.collectAsState()
    val playlist by vm.playlist.collectAsState()
    val pid = playlist?.id ?: 0
    PhoneScaffold(vm.tab, stringResource(vm.tab.label)) {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = PhoneGutter, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            PhonePill(categoryPill(view, categories)) { vm.picking = VodBrowseViewModel.Pick.CATEGORY }
            PhonePill(stringResource(R.string.vod_pill_filter, showName(view.show))) { vm.picking = VodBrowseViewModel.Pick.SHOW }
            // Favourites keep the user's own order.
            if (view.show != VodShow.FAVOURITES) {
                PhonePill(stringResource(R.string.vod_pill_sort, sortName(view.sort, vm.isMovies))) {
                    vm.picking = VodBrowseViewModel.Pick.SORT
                }
            }
        }
        PhonePosterGrid(vm, pid, view)
    }
    VodBrowsePanels(vm, view, categories, pid)
}

/** The titles of the view chosen, paged as on a TV. */
@Composable
private fun PhonePosterGrid(vm: VodBrowseViewModel, pid: Long, view: VodView) {
    val items = vm.items.collectAsLazyPagingItems()
    val favourites = vm.favouriteIds.collectIds()
    // A poster taken off Favourites (from its options): a TV moves focus on from it; a touch screen has no focus to move.
    vm.removedId?.let { removed -> LaunchedEffect(removed) { vm.removedId = null } }
    if (items.itemCount == 0) {
        val empty = emptyTitles(vm, pid, view)
        Column(Modifier.padding(horizontal = PhoneGutter, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Muted(stringResource(empty.message), maxLines = 3)
            if (empty.actions.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    empty.actions.forEachIndexed { i, (label, action) ->
                        PhonePill(stringResource(label), primary = i == 0, onClick = action)
                    }
                }
            }
        }
        return
    }
    // A new view starts at its top; back from a title's page, the grid is where it was (as on a TV).
    key(view) { PhoneGrid(vm, pid, items, favourites) }
}

@Composable
private fun PhoneGrid(vm: VodBrowseViewModel, pid: Long, items: LazyPagingItems<VodCard>, favourites: Set<Long>) {
    val nav = LocalNavigator.current
    val wide = LocalFormFactor.current == FormFactor.UNFOLDED
    val state = rememberLazyGridState()
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val (columns, width) = gridColumns(maxWidth, wide)
        LazyVerticalGrid(
            columns = GridCells.Fixed(columns),
            state = state,
            contentPadding = PaddingValues(start = PhoneGutter, end = PhoneGutter, top = 4.dp, bottom = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(GRID_GAP),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            items(count = items.itemCount, key = items.itemKey { it.id }) { index ->
                val card = items[index]
                if (card == null) {
                    Spacer(Modifier.fillMaxWidth().aspectRatio(POSTER_AND_TEXT))
                } else {
                    PhonePoster(
                        card.imageUrl,
                        card.title,
                        card.year?.toString().orEmpty(),
                        width = width,
                        favourite = card.id in favourites,
                        onLongClick = { vm.optionsFor = card }
                    ) { nav.push(vm.open(card, pid)) }
                }
            }
        }
    }
}

/** How many posters fit across [width], and how wide each is: three on a phone, about [WIDE_POSTER] each unfolded. */
private fun gridColumns(width: Dp, wide: Boolean): Pair<Int, Dp> {
    val room = width - PhoneGutter * 2
    val columns = if (wide) ((room + GRID_GAP) / (WIDE_POSTER + GRID_GAP)).toInt().coerceAtLeast(PHONE_COLUMNS) else PHONE_COLUMNS
    return columns to (room - GRID_GAP * (columns - 1)) / columns
}

private const val PHONE_COLUMNS = 3
private val WIDE_POSTER = 130.dp
private val GRID_GAP = 12.dp

/** A poster not loaded yet: about as high as a poster with its two lines of text, for its width. */
private const val POSTER_AND_TEXT = 0.55f
