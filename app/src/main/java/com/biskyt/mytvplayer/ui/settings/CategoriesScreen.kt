package com.biskyt.mytvplayer.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.biskyt.mytvplayer.AppContainer
import com.biskyt.mytvplayer.core.epg.CategoryLanguageDetector
import com.biskyt.mytvplayer.data.db.CAT_LIVE
import com.biskyt.mytvplayer.data.db.CAT_MOVIE
import com.biskyt.mytvplayer.data.db.CAT_SERIES
import com.biskyt.mytvplayer.data.db.CategoryEntity
import com.biskyt.mytvplayer.data.db.ChannelEntity
import com.biskyt.mytvplayer.ui.LocalNavigator
import com.biskyt.mytvplayer.ui.Screen
import com.biskyt.mytvplayer.ui.appViewModel
import com.biskyt.mytvplayer.ui.common.Muted
import com.biskyt.mytvplayer.ui.common.PillButton
import com.biskyt.mytvplayer.ui.common.Toggle
import com.biskyt.mytvplayer.ui.common.TvCard
import com.biskyt.mytvplayer.ui.common.formatCount
import com.biskyt.mytvplayer.ui.common.rememberFirstFocus
import com.biskyt.mytvplayer.ui.theme.LocalAccent
import com.biskyt.mytvplayer.ui.theme.Palette
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** A language/country group of categories, e.g. "English (UK)" with 34 categories. */
data class LanguageGroup(val code: String?, val label: String, val total: Int, val included: Int)

@OptIn(ExperimentalCoroutinesApi::class)
class CategoriesViewModel(private val c: AppContainer, private val playlistId: Long) : ViewModel() {
    /** Which list is being chosen: live channels, movies or series. */
    val type = MutableStateFlow(CAT_LIVE)
    val categories = type.flatMapLatest { c.db.categories().observe(playlistId, it) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Movies and series exist for Xtream accounts only. */
    var hasVod by mutableStateOf(false)
        private set
    val hidden = c.channels.observeHidden(playlistId).stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    var selectedGroup by mutableStateOf<String?>(UNSET)
    private var changed = false

    init {
        viewModelScope.launch {
            val p = c.playlists.get(playlistId) ?: return@launch
            hasVod = !p.isM3u
        }
    }

    fun showType(t: String) {
        type.value = t
        selectedGroup = UNSET
    }

    fun groups(list: List<CategoryEntity>): List<LanguageGroup> = list.groupBy { it.lang }
        .map { (code, items) -> LanguageGroup(code, CategoryLanguageDetector.label(code), items.size, items.count { it.included }) }
        .sortedWith(compareByDescending<LanguageGroup> { it.included > 0 }.thenBy { it.code == null }.thenBy { it.label })

    fun toggleGroup(g: LanguageGroup) = viewModelScope.launch {
        changed = true
        val ids = categories.value.filter { it.lang == g.code }.map { it.categoryId }
        c.db.categories().setIncluded(playlistId, type.value, ids, g.included < g.total)
    }

    fun toggleCategory(cat: CategoryEntity) = viewModelScope.launch {
        changed = true
        c.db.categories().setIncluded(playlistId, type.value, listOf(cat.categoryId), !cat.included)
    }

    fun unhide(ch: ChannelEntity) = viewModelScope.launch { c.channels.setHidden(ch.playlistId, ch.streamId, false) }

    /** Downloads channels again if the selection changed (always on first run). */
    fun finish(firstRun: Boolean) {
        if (changed || firstRun) c.sync.refresh(playlistId)
    }

    companion object {
        const val UNSET = "\u0000"
    }
}

@Composable
fun CategoriesScreen(playlistId: Long, firstRun: Boolean) {
    val vm = appViewModel(key = "cats-$playlistId") { CategoriesViewModel(it, playlistId) }
    val nav = LocalNavigator.current
    val categories by vm.categories.collectAsState()
    val type by vm.type.collectAsState()
    val hidden by vm.hidden.collectAsState()
    val groups = remember(categories) { vm.groups(categories) }
    if (vm.selectedGroup == CategoriesViewModel.UNSET && groups.isNotEmpty()) vm.selectedGroup = groups.first().code
    val done: () -> Unit = {
        vm.finish(firstRun)
        if (firstRun) nav.reset(Screen.Home) else nav.pop()
    }
    BackHandler(onBack = done)

    Column(
        Modifier.fillMaxSize().background(Palette.background).padding(horizontal = 72.dp, vertical = 36.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Languages & categories", style = MaterialTheme.typography.headlineLarge)
                Muted("Only the categories you tick are downloaded, so lists load faster and use less storage.", maxLines = 2)
            }
            Muted("Loading ${groups.sumOf { it.included }} of ${categories.size} categories", Modifier.padding(end = 16.dp))
            PillButton(if (firstRun) "Continue" else "Done", done, primary = true)
        }
        if (vm.hasVod) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(CAT_LIVE to "Live TV", CAT_MOVIE to "Movies", CAT_SERIES to "Series").forEach { (t, label) ->
                    PillButton(label, { vm.showType(t) }, selected = t == type)
                }
            }
        }
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            LazyColumn(
                Modifier.width(380.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                contentPadding = PaddingValues(bottom = 24.dp)
            ) {
                item { Text("DETECTED FROM CATEGORY NAMES", style = MaterialTheme.typography.labelMedium, color = Palette.textMuted) }
                items(groups, key = { it.code ?: "" }) { g ->
                    TvCard(
                        onClick = { vm.toggleGroup(g) },
                        selected = g.code == vm.selectedGroup,
                        color = Palette.surfaceLow,
                        modifier = (if (g == groups.first()) rememberFirstFocus() else Modifier).fillMaxWidth()
                            .onFocusChanged { if (it.isFocused) vm.selectedGroup = g.code }
                    ) {
                        Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(g.label, style = MaterialTheme.typography.titleSmall)
                                Muted("${g.included} of ${g.total} categories")
                            }
                            Toggle(g.included > 0)
                        }
                    }
                }
                if (type == CAT_LIVE && hidden.isNotEmpty()) {
                    item {
                        Text(
                            "HIDDEN CHANNELS",
                            style = MaterialTheme.typography.labelMedium,
                            color = Palette.textMuted,
                            modifier = Modifier.padding(top = 16.dp)
                        )
                    }
                    items(hidden, key = { "h${it.streamId}" }) { ch ->
                        TvCard(onClick = { vm.unhide(ch) }, color = Palette.surfaceLow, modifier = Modifier.fillMaxWidth()) {
                            Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                                Text(
                                    ch.name,
                                    style = MaterialTheme.typography.bodyLarge,
                                    modifier = Modifier.weight(1f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text("Unhide", style = MaterialTheme.typography.labelMedium, color = LocalAccent.current.color)
                            }
                        }
                    }
                }
            }
            val selected = vm.selectedGroup
            val shown = remember(categories, selected) { categories.filter { it.lang == selected } }
            LazyColumn(
                Modifier.weight(1f).background(Palette.surfaceLow).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(shown, key = { it.categoryId }) { cat ->
                    TvCard(onClick = { vm.toggleCategory(cat) }, color = Palette.surface, modifier = Modifier.fillMaxWidth()) {
                        Row(
                            Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Toggle(cat.included)
                            Text(
                                cat.name,
                                style = MaterialTheme.typography.bodyLarge,
                                color = if (cat.included) Palette.text else Palette.textDim,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (cat.channelCount > 0) Muted(formatCount(cat.channelCount))
                        }
                    }
                }
            }
        }
    }
}
