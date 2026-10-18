package com.biskyt.mytvplayer.ui.live

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import com.biskyt.mytvplayer.AppContainer
import com.biskyt.mytvplayer.data.ChannelFilter
import com.biskyt.mytvplayer.data.db.CategoryEntity
import com.biskyt.mytvplayer.data.db.ChannelEntity
import com.biskyt.mytvplayer.ui.common.NowNextLoader
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

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class LiveViewModel(private val c: AppContainer, initialFilter: ChannelFilter, initialGuide: Boolean) : ViewModel() {
    val playlistId: StateFlow<Long> = c.settings.settings.map { it.activePlaylistId }.distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, 0L)

    val filter = MutableStateFlow(initialFilter)
    val query = MutableStateFlow("")
    var guideMode by mutableStateOf(initialGuide)

    val categories: StateFlow<List<CategoryEntity>> = playlistId.flatMapLatest { c.db.categories().observeIncluded(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val totalCount: StateFlow<Int> = playlistId.flatMapLatest { c.channels.observeCount(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    val favouriteIds: StateFlow<Set<Long>> = playlistId.flatMapLatest { c.channels.observeFavouriteIds(it) }.map { it.toSet() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    val channels: Flow<PagingData<ChannelEntity>> =
        combine(playlistId, filter, query.debounce(250)) { pid, f, q -> Triple(pid, f, q) }
            .distinctUntilChanged()
            .flatMapLatest { (pid, f, q) -> c.channels.page(pid, f, q) }
            .cachedIn(viewModelScope)

    val nowNext = NowNextLoader(viewModelScope) { ids, from, to -> c.epg.programmes(playlistId.value, ids, from, to) }

    /** Channel whose options sheet is open. */
    var optionsFor by mutableStateOf<ChannelEntity?>(null)

    /** Row to give focus to when the list is shown again (e.g. after returning from the player). */
    var focusStreamId by mutableStateOf<Long?>(null)
    var restoreFocusPending by mutableStateOf(true)

    /** Digits typed on the remote, shown while waiting to jump to that channel number. */
    var typedNumber by mutableStateOf("")
        private set

    private var filterJob: Job? = null
    private var numberJob: Job? = null

    /** Category focus changes the list after a short pause, so moving through categories stays smooth. */
    fun focusFilter(f: ChannelFilter) {
        filterJob?.cancel()
        if (f == filter.value) return
        filterJob = viewModelScope.launch {
            delay(300)
            filter.value = f
        }
    }

    fun selectFilter(f: ChannelFilter) {
        filterJob?.cancel()
        filter.value = f
    }

    fun typeDigit(d: Int, onChannel: (ChannelEntity) -> Unit) {
        if (typedNumber.length >= 5) return
        typedNumber += d
        numberJob?.cancel()
        numberJob = viewModelScope.launch {
            delay(1_500)
            val n = typedNumber.toIntOrNull()
            typedNumber = ""
            if (n != null) c.channels.byNumber(playlistId.value, n)?.let(onChannel)
        }
    }

    fun toggleFavourite(ch: ChannelEntity) = viewModelScope.launch {
        c.channels.setFavourite(ch.playlistId, ch.streamId, ch.streamId !in favouriteIds.value)
    }

    fun hide(ch: ChannelEntity) = viewModelScope.launch {
        c.channels.setHidden(ch.playlistId, ch.streamId, true)
        optionsFor = null
    }

    fun move(ch: ChannelEntity, delta: Int) = viewModelScope.launch {
        c.channels.move(ch.playlistId, filter.value, ch.streamId, delta)
        focusStreamId = ch.streamId
        restoreFocusPending = true
    }
}
