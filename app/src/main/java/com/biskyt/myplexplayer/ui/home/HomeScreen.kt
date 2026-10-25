package com.biskyt.myplexplayer.ui.home

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.biskyt.myplexplayer.AppContainer
import com.biskyt.myplexplayer.R
import com.biskyt.myplexplayer.data.ChannelFilter
import com.biskyt.myplexplayer.data.db.CAT_LIVE
import com.biskyt.myplexplayer.data.db.CAT_MOVIE
import com.biskyt.myplexplayer.data.db.ChannelEntity
import com.biskyt.myplexplayer.data.db.VOD_TYPES
import com.biskyt.myplexplayer.data.db.VodCard
import com.biskyt.myplexplayer.data.db.WatchProgressEntity
import com.biskyt.myplexplayer.ui.LocalNavigator
import com.biskyt.myplexplayer.ui.Screen
import com.biskyt.myplexplayer.ui.appViewModel
import com.biskyt.myplexplayer.ui.common.ChannelCard
import com.biskyt.myplexplayer.ui.common.FavouriteMover
import com.biskyt.myplexplayer.ui.common.ItemAction
import com.biskyt.myplexplayer.ui.common.ItemActionsPanel
import com.biskyt.myplexplayer.ui.common.KeepInView
import com.biskyt.myplexplayer.ui.common.MovableRow
import com.biskyt.myplexplayer.ui.common.MovableTile
import com.biskyt.myplexplayer.ui.common.MoveBackHandler
import com.biskyt.myplexplayer.ui.common.MoveHint
import com.biskyt.myplexplayer.ui.common.MoveLayout
import com.biskyt.myplexplayer.ui.common.Muted
import com.biskyt.myplexplayer.ui.common.NowNextLoader
import com.biskyt.myplexplayer.ui.common.PillButton
import com.biskyt.myplexplayer.ui.common.Poster
import com.biskyt.myplexplayer.ui.common.PosterCard
import com.biskyt.myplexplayer.ui.common.ProgressLine
import com.biskyt.myplexplayer.ui.common.SectionTitle
import com.biskyt.myplexplayer.ui.common.Tab
import com.biskyt.myplexplayer.ui.common.TabScaffold
import com.biskyt.myplexplayer.ui.common.TvCard
import com.biskyt.myplexplayer.ui.common.channelActions
import com.biskyt.myplexplayer.ui.common.collectIds
import com.biskyt.myplexplayer.ui.common.formatDuration
import com.biskyt.myplexplayer.ui.common.guideNowNext
import com.biskyt.myplexplayer.ui.common.markWatched
import com.biskyt.myplexplayer.ui.common.moveAction
import com.biskyt.myplexplayer.ui.common.rememberFirstFocus
import com.biskyt.myplexplayer.ui.common.rememberMinuteClock
import com.biskyt.myplexplayer.ui.common.shortEpisodeLabel
import com.biskyt.myplexplayer.ui.common.titledRow
import com.biskyt.myplexplayer.ui.common.vodActions
import com.biskyt.myplexplayer.ui.common.vodDetails
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** How many favourite movies, and series, Home shows. */
private const val VOD_ROW_LIMIT = 20

/** How many favourite channels, and recently watched ones, Home shows. */
private const val CHANNEL_ROW_LIMIT = 12

class HomeViewModel(private val c: AppContainer) : ViewModel() {
    private val activePlaylist = c.settings.activePlaylistId
    val playlistId = activePlaylist.stateIn(viewModelScope, SharingStarted.Eagerly, 0L)

    @OptIn(ExperimentalCoroutinesApi::class)
    val favourites = activePlaylist.flatMapLatest {
        c.channels.observeFavourites(it, CHANNEL_ROW_LIMIT)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    @OptIn(ExperimentalCoroutinesApi::class)
    val recent = activePlaylist.flatMapLatest {
        c.channels.observeRecent(it, CHANNEL_ROW_LIMIT)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** The first favourite series and movies, by type ([VOD_TYPES]); null until read. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val favouriteVod: Map<String, StateFlow<List<VodCard>?>> = VOD_TYPES.associateWith { type ->
        activePlaylist.flatMapLatest { c.vod.observeFavouriteCards(it, type, VOD_ROW_LIMIT) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    }

    /** The series with the newest episodes, and the newest movies, by type ([VOD_TYPES]); null until read. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val newestVod: Map<String, StateFlow<List<VodCard>?>> = VOD_TYPES.associateWith { type ->
        activePlaylist.flatMapLatest { c.vod.observeNewestCards(it, type, VOD_ROW_LIMIT) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    }

    /** Moving a favourite within its row. */
    val mover = FavouriteMover(viewModelScope, c.favourites) { playlistId.value }

    fun setVodFavourite(type: String, id: Long, favourite: Boolean) = viewModelScope.launch {
        c.vod.setFavourite(playlistId.value, type, id, favourite)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    val carryOn = activePlaylist.flatMapLatest { c.vod.observeCarryOn(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Which channels, movies and series are favourites, for their options. */
    val favouriteSets = c.favourites

    fun setFavourite(ch: ChannelEntity, favourite: Boolean) = viewModelScope.launch {
        c.channels.setFavourite(ch.playlistId, ch.streamId, favourite)
    }

    fun removeRecent(ch: ChannelEntity) = viewModelScope.launch { c.channels.removeRecent(ch.playlistId, ch.streamId) }

    fun forget(p: WatchProgressEntity) = viewModelScope.launch { c.vod.forgetProgress(p) }

    /** Whether [p]'s source is a Plex server (where watched can be marked). */
    suspend fun isPlex(p: WatchProgressEntity) = c.playlists.get(p.playlistId)?.isPlex == true

    fun setWatched(p: WatchProgressEntity) = viewModelScope.launch { c.vod.setWatched(p.playlistId, p.itemId, p.seriesId, watched = true) }

    val nowNext = guideNowNext(viewModelScope, c) { playlistId.value }
}

@Composable
fun HomeScreen() {
    val vm = appViewModel { HomeViewModel(it) }
    vm.nowNext.PauseWhenHidden()
    val nav = LocalNavigator.current
    // States, read where used: shown() below must see a row change after its card is taken off, and Home recomposes
    // when what it shows changes, not with every row's update (as during a sync).
    val favourites by vm.favourites.collectAsState()
    val recent by vm.recent.collectAsState()
    val carryOn by vm.carryOn.collectAsState()
    val favouriteVod = VOD_TYPES.associateWith { vm.favouriteVod.getValue(it).collectAsState() }
    val newestVod = VOD_TYPES.associateWith { vm.newestVod.getValue(it).collectAsState() }
    val pid = vm.playlistId.collectAsState().value
    val ready by remember {
        derivedStateOf {
            listOf(favourites, recent, carryOn).all { it != null } && (favouriteVod.values + newestVod.values).all { it.value != null }
        }
    }
    val top by remember {
        derivedStateOf {
            topRow(
                carryOn.orEmpty(),
                newestVod.mapValues { it.value.value },
                favouriteVod.mapValues { it.value.value },
                favourites.orEmpty(),
                recent.orEmpty()
            )
        }
    }
    var options by remember { mutableStateOf<HomeTile?>(null) }
    // The row on top takes focus once every row is read, and again when focus has nowhere to be: after a card is
    // taken off its row, or the row it was on goes (a sync changes what's on top), but not away from the top bar.
    var refocus by remember { mutableIntStateOf(0) }
    var screenFocused by remember { mutableStateOf(false) }
    val first = rememberFirstFocus(ready to refocus)
    LaunchedEffect(top) { if (ready && !screenFocused && options == null) refocus++ }

    /** Focus for the first card of [row], when it's the row on top. */
    fun focusOn(row: HomeRow) = if (row == top) first else Modifier
    val scope = rememberCoroutineScope()
    fun shown(tile: HomeTile) = when (tile) {
        is HomeTile.Channel -> (
            if (tile.list ==
                ChannelFilter.Recent
            ) {
                recent
            } else {
                favourites
            }
            ).orEmpty().any { it.streamId == tile.ch.streamId }
        is HomeTile.CarryOn -> carryOn.orEmpty().any { it.kind == tile.p.kind && it.itemId == tile.p.itemId }
        is HomeTile.Vod -> favouriteVod.getValue(tile.type).value.orEmpty().any { it.id == tile.card.id }
        is HomeTile.New -> true // never taken off its row (its options don't)
    }

    MoveBackHandler(vm.mover)
    TabScaffold(Tab.HOME, Modifier.onFocusChanged { screenFocused = it.hasFocus }) {
        // Carry on watching, recently added series and movies, favourite series and movies, then Live TV last.
        LazyColumn(
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 56.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(28.dp)
        ) {
            val carryOnRow = carryOn.orEmpty()
            if (carryOnRow.isNotEmpty()) {
                item {
                    Column {
                        SectionTitle(stringResource(R.string.home_carry_on))
                        CarryOnRow(carryOnRow, { options = HomeTile.CarryOn(it) }, focusOn(HomeRow.CarryOn)) {
                            nav.push(Screen.VodPlayer(pid, it.itemId, it.seriesId, continueSeries = true))
                        }
                    }
                }
            }
            newestVod.forEach { (type, cards) ->
                val row = cards.value.orEmpty()
                titledRow(newestRowTitle(type), row, { it.id }) { card ->
                    val focus = if (card == row.first()) focusOn(HomeRow.Newest(type)) else Modifier
                    PosterCard(card, focus, onLongClick = {
                        options = HomeTile.New(type, card)
                    }) { nav.push(vodDetails(pid, type, card.id)) }
                }
            }
            favouriteVod.forEach { (type, cards) ->
                val row = cards.value.orEmpty()
                titledRow(favouriteRowTitle(type), row, { it.id }, move = MovableRow(vm.mover, type)) { card ->
                    MovableTile(vm.mover, type, card.id, MoveLayout.ROW) {
                        val focus = if (card == row.first()) focusOn(HomeRow.Favourites(type)) else Modifier
                        PosterCard(card, focus, onLongClick = {
                            options = HomeTile.Vod(type, card)
                        }) { nav.push(vodDetails(pid, type, card.id)) }
                    }
                }
            }
            channelRows(favourites, recent.orEmpty(), top, ::focusOn, vm) { options = it }
        }
    }
    options?.let { tile ->
        TileOptions(tile, vm, pid) { removed ->
            options = null
            if (removed) scope.launch { snapshotFlow { shown(tile) }.first { !it }.also { refocus++ } }
        }
    }
}

/**
 * A Home card whose options are open: a channel (from [list]: favourites or recently watched), a title carried on, or a
 * favourite movie or series.
 */
private sealed interface HomeTile {
    data class Channel(val ch: ChannelEntity, val list: ChannelFilter) : HomeTile

    data class CarryOn(val p: WatchProgressEntity) : HomeTile

    data class Vod(val type: String, val card: VodCard) : HomeTile

    /** A movie or series on a recently added row. */
    data class New(val type: String, val card: VodCard) : HomeTile
}

/** Home's rows, top to bottom (each shown only when it has something). */
private sealed interface HomeRow {
    data object CarryOn : HomeRow
    data class Newest(val type: String) : HomeRow
    data class Favourites(val type: String) : HomeRow
    data object Channels : HomeRow
    data object Recent : HomeRow
}

/** The first row with something in it (it takes focus); the favourite channels' (with a hint) when none has. */
private fun topRow(
    carryOn: List<WatchProgressEntity>,
    newest: Map<String, List<VodCard>?>,
    favouriteVod: Map<String, List<VodCard>?>,
    channels: List<ChannelEntity>,
    recent: List<ChannelEntity>,
): HomeRow = when {
    carryOn.isNotEmpty() -> HomeRow.CarryOn
    else -> VOD_TYPES.firstOrNull { !newest[it].isNullOrEmpty() }?.let(HomeRow::Newest)
        ?: VOD_TYPES.firstOrNull { !favouriteVod[it].isNullOrEmpty() }?.let(HomeRow::Favourites)
        ?: if (channels.isEmpty() && recent.isNotEmpty()) HomeRow.Recent else HomeRow.Channels
}

/**
 * Live TV, last: favourite channels with what's on, then those watched lately. With nothing else on Home, an empty
 * favourites row says how to add some.
 */
@Suppress("LongParameterList") // Home's state for its last rows, read where Home reads it
private fun LazyListScope.channelRows(
    favourites: List<ChannelEntity>?,
    recent: List<ChannelEntity>,
    top: HomeRow,
    focusOn: (HomeRow) -> Modifier,
    vm: HomeViewModel,
    onOptions: (HomeTile) -> Unit,
) {
    val favs = favourites ?: return
    if (favs.isNotEmpty() || top == HomeRow.Channels) {
        item {
            val nav = LocalNavigator.current
            Column {
                SectionTitle(stringResource(R.string.home_on_now))
                MoveHint(vm.mover, CAT_LIVE, MoveLayout.ROW)
                val focus = focusOn(HomeRow.Channels)
                if (favs.isEmpty()) {
                    EmptyFavourites(focus) { nav.reset(Screen.Live()) }
                } else {
                    ChannelCardRow(favs, vm.nowNext, { onOptions(HomeTile.Channel(it, ChannelFilter.Favourites)) }, focus, vm.mover) {
                        nav.push(Screen.Player(vm.playlistId.value, it.streamId, ChannelFilter.Favourites))
                    }
                }
            }
        }
    }
    if (recent.isNotEmpty()) {
        item {
            val nav = LocalNavigator.current
            Column {
                SectionTitle(stringResource(R.string.common_recent))
                val focus = focusOn(HomeRow.Recent)
                ChannelCardRow(recent, vm.nowNext, { onOptions(HomeTile.Channel(it, ChannelFilter.Recent)) }, focus) {
                    nav.push(Screen.Player(vm.playlistId.value, it.streamId, ChannelFilter.Recent))
                }
            }
        }
    }
}

/** Holding OK on a Home card: watch it, and favourites and the guide source, or take it off its row. */
@Composable
private fun TileOptions(tile: HomeTile, vm: HomeViewModel, pid: Long, onDismiss: (removed: Boolean) -> Unit) {
    val close = { onDismiss(false) }
    when (tile) {
        is HomeTile.Channel -> {
            val ch = tile.ch
            val favourites = vm.favouriteSets.channels.collectIds()
            val watch = stringResource(R.string.common_watch)
            val removeRecent = stringResource(R.string.home_remove_recent)
            val actions = buildList {
                add(ItemAction.Open(watch, Screen.Player(ch.playlistId, ch.streamId, tile.list)))
                addAll(
                    channelActions(ch, ch.streamId in favourites) { favourite ->
                        vm.setFavourite(ch, favourite)
                        if (!favourite && tile.list == ChannelFilter.Favourites) onDismiss(true)
                    }
                )
                if (tile.list == ChannelFilter.Favourites) add(moveAction(vm.mover, CAT_LIVE, ch.streamId, close, CHANNEL_ROW_LIMIT))
                if (tile.list == ChannelFilter.Recent) {
                    add(
                        ItemAction.Run(removeRecent) {
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
            val plex by produceState(false, p) { value = vm.isPlex(p) }
            val actions = listOfNotNull(
                ItemAction.Open(
                    stringResource(if (p.finished) R.string.home_play_next_episode else R.string.home_resume),
                    Screen.VodPlayer(p.playlistId, p.itemId, series, continueSeries = true)
                ),
                if (series == null) {
                    ItemAction.Open(stringResource(R.string.common_about_movie), Screen.MovieDetails(p.playlistId, p.itemId))
                } else {
                    ItemAction.Open(
                        stringResource(R.string.common_all_episodes),
                        Screen.SeriesDetails(p.playlistId, series, p.season, p.episode)
                    )
                },
                ItemAction.Run(
                    stringResource(R.string.home_remove_carry_on),
                    stringResource(if (series == null) R.string.home_forget_movie else R.string.home_forget_series)
                ) {
                    vm.forget(p)
                    onDismiss(true)
                },
                // Last: it's known once the menu is open, and mustn't move the entries above it.
                if (plex) {
                    markWatched(watched = false) {
                        vm.setWatched(p)
                        onDismiss(true)
                    }
                } else {
                    null
                }
            )
            ItemActionsPanel(p.title, actions, close)
        }
        is HomeTile.New -> {
            val favourite = tile.card.id in vm.favouriteSets.of(tile.type).collectIds()
            val actions = vodActions(pid, tile.type, tile.card.id, favourite) { vm.setVodFavourite(tile.type, tile.card.id, it) }
            ItemActionsPanel(tile.card.title, actions, close)
        }
        is HomeTile.Vod -> {
            // A card on these rows is a favourite.
            val actions = vodActions(pid, tile.type, tile.card.id, favourite = true) { favourite ->
                vm.setVodFavourite(tile.type, tile.card.id, favourite)
                if (!favourite) onDismiss(true)
            } + moveAction(vm.mover, tile.type, tile.card.id, close, VOD_ROW_LIMIT)
            ItemActionsPanel(tile.card.title, actions, close)
        }
    }
}

@Composable
private fun EmptyFavourites(modifier: Modifier, onBrowse: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Muted(stringResource(R.string.home_no_favourites), maxLines = 2)
        PillButton(stringResource(R.string.home_browse_channels), onBrowse, modifier, primary = true)
    }
}

@Composable
private fun ChannelCardRow(
    channels: List<ChannelEntity>,
    loader: NowNextLoader,
    onOptions: (ChannelEntity) -> Unit,
    modifier: Modifier = Modifier,
    mover: FavouriteMover? = null,
    onPlay: (ChannelEntity) -> Unit
) {
    val now by rememberMinuteClock()
    val state = rememberLazyListState()
    KeepInView(state) { mover?.movingId(CAT_LIVE)?.let { p -> channels.indexOfFirst { it.streamId == p } } }
    LazyRow(state = state, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        items(channels, key = { it.streamId }) { c ->
            MovableTile(mover, CAT_LIVE, c.streamId, MoveLayout.ROW) {
                ChannelCard(c, loader, now, { onOptions(c) }, if (c == channels.first()) modifier else Modifier) { onPlay(c) }
            }
        }
    }
}

/** Movies and episodes part-watched (or the next episode up): a poster-sized card each, with how much is left. */
@Composable
private fun CarryOnRow(
    items: List<WatchProgressEntity>,
    onOptions: (WatchProgressEntity) -> Unit,
    first: Modifier,
    onPlay: (WatchProgressEntity) -> Unit
) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        items(items, key = { "${it.kind}${it.itemId}" }) { p ->
            val focus = if (p == items.first()) first else Modifier
            TvCard(onClick = { onPlay(p) }, onLongClick = { onOptions(p) }, modifier = focus.width(272.dp)) {
                Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Poster(p.imageUrl, p.title, 64.dp)
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(p.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        if (p.finished) {
                            // A finished episode with another after it: the card leads on to that one.
                            Muted(stringResource(R.string.home_up_next_after, shortEpisodeLabel(p.season, p.episode)))
                        } else {
                            if (p.season != null) Muted(shortEpisodeLabel(p.season, p.episode) + (p.episodeTitle?.let { " · $it" } ?: ""))
                            Muted(stringResource(R.string.common_time_left, formatDuration(p.remainingMs)))
                            ProgressLine(p.fraction, Modifier.fillMaxWidth())
                        }
                    }
                }
            }
        }
    }
}

/** The title of Home's row of recently added movies or series ([type]). */
@StringRes
private fun newestRowTitle(type: String) =
    if (type == CAT_MOVIE) R.string.home_recently_added_movies else R.string.home_recently_added_series

/** The title of Home's row of favourite movies or series ([type]). */
@StringRes
private fun favouriteRowTitle(type: String) = if (type == CAT_MOVIE) R.string.home_favourite_movies else R.string.home_favourite_series
