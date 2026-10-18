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
import com.biskyt.mytvplayer.data.db.CategoryEntity
import com.biskyt.mytvplayer.data.db.ChannelEntity
import com.biskyt.mytvplayer.ui.common.NowNextLoader
import com.biskyt.mytvplayer.ui.common.NumberEntry
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
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class LiveViewModel(private val c: AppContainer, initialFilter: ChannelFilter, initialGuide: Boolean) : ViewModel() {
    private val activePlaylist = c.settings.activePlaylistId
    val playlistId: StateFlow<Long> = activePlaylist.stateIn(viewModelScope, SharingStarted.Eagerly, 0L)

    val filter = MutableStateFlow(initialFilter)
    val query = MutableStateFlow("")
    var guideMode by mutableStateOf(initialGuide)
        private set

    /** Shared by the list and the guide grid, so switching between them keeps the same channels on screen. */
    val listState = LazyListState()

    val categories: StateFlow<List<CategoryEntity>> = activePlaylist.flatMapLatest { c.db.categories().observeIncluded(it) }
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

    // Position of [focusStreamId] in the list, when known (it isn't for a channel picked by number).
    private var focusIndex = 0
    private var focusIndexOf: Long? = null

    /** Called as focus moves in the list or guide. */
    fun focusMoved(streamId: Long, index: Int) {
        focusStreamId = streamId
        focusIndex = index
        focusIndexOf = streamId
    }

    /**
     * Before restoring focus: scrolls the remembered channel fully into view, as row heights differ between the list
     * and the guide. Call from a composition effect: it first waits a frame so the view now shown has been laid out.
     */
    suspend fun revealFocused() {
        withFrameNanos { }
        val id = focusStreamId ?: return listState.scrollToItem(0) // nothing remembered: focus goes to the first row
        val index = if (focusIndexOf == id) {
            focusIndex
        } else if (query.value.isBlank()) {
            c.channels.ids(playlistId.value, filter.value).indexOf(id).takeIf { it >= 0 } ?: return
        } else {
            return
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

    private var filterJob: Job? = null

    /** Category focus changes the list after a short pause, so moving through categories stays smooth. */
    fun focusFilter(f: ChannelFilter) {
        filterJob?.cancel()
        if (f == filter.value) return
        filterJob = viewModelScope.launch {
            delay(300)
            setFilter(f)
        }
    }

    fun selectFilter(f: ChannelFilter) {
        filterJob?.cancel()
        setFilter(f)
    }

    private fun setFilter(f: ChannelFilter) {
        if (f == filter.value) return
        filter.value = f
        // A different list: the remembered channel may not be in it, so focus starts from the top.
        focusStreamId = null
        focusIndexOf = null
    }

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
        focusMoved(ch.streamId, (focusIndex + delta).coerceAtLeast(0))
        restoreFocusPending = true
    }
}
