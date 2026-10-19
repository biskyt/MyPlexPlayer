package com.biskyt.mytvplayer.ui.live

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import com.biskyt.mytvplayer.AppContainer
import com.biskyt.mytvplayer.data.ChannelFilter
import com.biskyt.mytvplayer.data.db.CAT_LIVE
import com.biskyt.mytvplayer.data.db.CategoryEntity
import com.biskyt.mytvplayer.data.db.ChannelEntity
import com.biskyt.mytvplayer.ui.common.CategoryFilter
import com.biskyt.mytvplayer.ui.common.NowNextLoader
import com.biskyt.mytvplayer.ui.common.NumberEntry
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class LiveViewModel(private val c: AppContainer, initialFilter: ChannelFilter?, initialGuide: Boolean) : ViewModel() {
    private val activePlaylist = c.settings.activePlaylistId
    val playlistId: StateFlow<Long> = activePlaylist.stateIn(viewModelScope, SharingStarted.Eagerly, 0L)

    /** Whether the user has picked a list here yet (the remembered one mustn't replace their choice). */
    private var picked = false

    // A different list: the remembered channel may not be in it, so focus starts from the top.
    val filters = CategoryFilter(viewModelScope, initialFilter ?: ChannelFilter.All) {
        picked = true
        focusStreamId = null
    }
    val filter = filters.value

    init {
        viewModelScope.launch {
            // Opens on the list chosen last time, unless a particular one was asked for (e.g. Favourites).
            var saved = c.settings.current().lastLiveFilter
            val remembered = ChannelFilter.of(saved)
            // A category since unticked (or from another playlist) would show an empty list.
            val gone = remembered is ChannelFilter.Category &&
                c.db.categories().observeIncluded(activePlaylist.first(), CAT_LIVE).first().none { it.categoryId == remembered.id }
            if (initialFilter == null && !gone && !picked) {
                filters.select(remembered)
                picked = false
            }
            // Remembered once the user settles on a list (not for every category passed on the way).
            filter.drop(1).debounce(SAVE_AFTER_MS).collect { f ->
                if (f.key != saved) {
                    saved = f.key
                    c.settings.update { it.copy(lastLiveFilter = f.key) }
                }
            }
        }
    }

    val query = MutableStateFlow("")
    var guideMode by mutableStateOf(initialGuide)
        private set

    /** Shared by the list and the guide grid, so switching between them keeps the same channels on screen. */
    val listState = LazyListState()

    val categories: StateFlow<List<CategoryEntity>> = activePlaylist.flatMapLatest { c.db.categories().observeIncluded(it, CAT_LIVE) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val totalCount: StateFlow<Int> = activePlaylist.flatMapLatest { c.channels.observeCount(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    val favouriteIds: StateFlow<Set<Long>> = activePlaylist.flatMapLatest { c.channels.observeFavouriteIds(it) }.map { it.toSet() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    val channels: Flow<PagingData<ChannelEntity>> =
        combine(activePlaylist, filter, query.debounce(250)) { pid, f, q -> Triple(pid, f, q) }
            .distinctUntilChanged()
            .flatMapLatest { (pid, f, q) -> c.channels.page(pid, f, q) }
            .cachedIn(viewModelScope)

    val nowNext =
        NowNextLoader(viewModelScope, c.epg.changes) { ids, from, to -> c.epg.programmes(playlistId.value, ids, from, to, details = false) }

    /** Channel whose options sheet is open. */
    var optionsFor by mutableStateOf<ChannelEntity?>(null)

    /**
     * Channel to give focus to when the list or guide is shown again (after the player, or after switching view).
     * Updated as focus moves; a plain field, so moving focus never recomposes anything.
     */
    var focusStreamId: Long? = null
        set(value) {
            if (value != field) focusIndex = null
            field = value
        }

    // Position of [focusStreamId] in the list, when known (it isn't for a channel picked by number).
    private var focusIndex: Int? = null

    /** Called as focus moves in the list or guide. */
    fun focusMoved(streamId: Long, index: Int) {
        focusStreamId = streamId
        focusIndex = index
    }

    /**
     * Before restoring focus: scrolls the remembered channel fully into view, as row heights differ between the list
     * and the guide. Call from a composition effect: it first waits a frame so the view now shown has been laid out.
     */
    suspend fun revealFocused() {
        withFrameNanos { }
        val id = focusStreamId ?: return listState.scrollToItem(0) // nothing remembered: focus goes to the first row
        // Unknown position (e.g. a channel picked by number): look it up, unless a search makes the list different.
        val index = focusIndex ?: run {
            if (query.value.isNotBlank()) return
            c.channels.ids(playlistId.value, filter.value).indexOf(id).takeIf { it >= 0 } ?: return
        }
        val info = listState.layoutInfo
        val item = info.visibleItemsInfo.firstOrNull { it.index == index }
        if (item == null || item.offset + item.size > info.viewportEndOffset) listState.scrollToItem(index)
    }
    var restoreFocusPending by mutableStateOf(true)

    /** Set by the screen: what to do with the channel picked by number. */
    var playNumber: (ChannelEntity) -> Unit = {}

    /** Digits typed on the remote jump straight to that channel number. */
    val numberEntry = NumberEntry(viewModelScope) { n -> c.channels.byNumber(playlistId.value, n)?.let { playNumber(it) } }

    fun toggleFavourite(ch: ChannelEntity) = viewModelScope.launch {
        c.channels.setFavourite(ch.playlistId, ch.streamId, ch.streamId !in favouriteIds.value)
    }

    fun hide(ch: ChannelEntity) = viewModelScope.launch {
        c.channels.setHidden(ch.playlistId, ch.streamId, true)
        optionsFor = null
    }

    /** Switches between the channel list and the TV guide grid, keeping focus on the same channel. */
    fun showGuide(guide: Boolean) {
        if (guide == guideMode) return
        guideMode = guide
        restoreFocusPending = true
    }

    /** Reordering only makes sense in lists sorted by position (not Favourites by name or Recent by time). */
    val canMove: Boolean get() = filter.value == ChannelFilter.All || filter.value is ChannelFilter.Category

    fun move(ch: ChannelEntity, delta: Int) = viewModelScope.launch {
        c.channels.move(ch.playlistId, filter.value, ch.streamId, delta)
        focusMoved(ch.streamId, ((focusIndex ?: 0) + delta).coerceAtLeast(0))
        restoreFocusPending = true
    }

    private companion object {
        const val SAVE_AFTER_MS = 1_500L
    }
}
