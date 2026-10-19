package com.biskyt.mytvplayer.ui.settings

import android.database.SQLException
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.biskyt.mytvplayer.AppContainer
import com.biskyt.mytvplayer.core.epg.CategoryLanguageDetector
import com.biskyt.mytvplayer.data.FreeGuides
import com.biskyt.mytvplayer.data.PlaylistRepository
import com.biskyt.mytvplayer.data.db.CAT_LIVE
import com.biskyt.mytvplayer.data.db.CAT_MOVIE
import com.biskyt.mytvplayer.data.db.CAT_SERIES
import com.biskyt.mytvplayer.data.db.CategoryEntity
import com.biskyt.mytvplayer.data.db.ChannelEntity
import com.biskyt.mytvplayer.ui.LocalNavigator
import com.biskyt.mytvplayer.ui.Screen
import com.biskyt.mytvplayer.ui.appViewModel
import com.biskyt.mytvplayer.ui.common.AppIcons
import com.biskyt.mytvplayer.ui.common.Muted
import com.biskyt.mytvplayer.ui.common.PillButton
import com.biskyt.mytvplayer.ui.common.Toggle
import com.biskyt.mytvplayer.ui.common.TvCard
import com.biskyt.mytvplayer.ui.common.TvTextField
import com.biskyt.mytvplayer.ui.common.formatCount
import com.biskyt.mytvplayer.ui.common.restoreFocusIf
import com.biskyt.mytvplayer.ui.theme.LocalAccent
import com.biskyt.mytvplayer.ui.theme.Palette
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** A language/country group of categories, e.g. "English (UK)" with 34 categories. */
data class LanguageGroup(val code: String?, val label: String, val total: Int, val included: Int)

@OptIn(ExperimentalCoroutinesApi::class)
class CategoriesViewModel(private val c: AppContainer, private val playlistId: Long) : ViewModel() {
    /** Which list is being chosen: live channels, movies or series. */
    val type = MutableStateFlow(CAT_LIVE)

    // Only ever the shown type's list: right after switching, the previous type's list is not shown or acted on.
    val categories = type.flatMapLatest { t -> c.db.categories().observe(playlistId, t).map { list -> list.takeIf { t == type.value } } }
        .map { it.orEmpty() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Movies and series exist for Xtream accounts only. */
    var hasVod by mutableStateOf(false)
        private set
    val hidden = c.channels.observeHidden(playlistId).stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    var selectedGroup by mutableStateOf<String?>(UNSET)

    /** Filters both lists: countries/languages by name, and the categories in them. */
    var query by mutableStateOf("")
        private set

    fun filter(q: String) {
        query = q
        selectedGroup = UNSET // the first group shown
    }
    private val changed = HashSet<String>()

    init {
        viewModelScope.launch {
            val p = c.playlists.get(playlistId) ?: return@launch
            hasVod = !p.isM3u
        }
    }

    fun showType(t: String) {
        if (t == type.value) return
        type.value = t
        selectedGroup = UNSET
    }

    fun groups(list: List<CategoryEntity>, q: String = ""): List<LanguageGroup> = list.groupBy { it.lang }
        .filter { (code, items) -> matches(CategoryLanguageDetector.label(code), q) || items.any { matches(it.name, q) } }
        .map { (code, items) -> LanguageGroup(code, CategoryLanguageDetector.label(code), items.size, items.count { it.included }) }
        .sortedWith(compareByDescending<LanguageGroup> { it.included > 0 }.thenBy { it.code == null }.thenBy { it.label })

    /** A group's categories, narrowed by [q] unless the group itself is what matched. */
    fun shown(list: List<CategoryEntity>, group: String?, q: String): List<CategoryEntity> {
        val inGroup = list.filter { it.lang == group }
        return if (matches(CategoryLanguageDetector.label(group), q)) inGroup else inGroup.filter { matches(it.name, q) }
    }

    /** Ticks or unticks a group: while filtering, only the categories shown for it. */
    fun toggleGroup(g: LanguageGroup) = viewModelScope.launch {
        val t = type.value
        val cats = shown(categories.value.filter { it.type == t }, g.code, query.trim())
        if (cats.isEmpty()) return@launch
        changed += t
        c.db.categories().setIncluded(playlistId, t, cats.map { it.categoryId }, cats.any { !it.included })
    }

    fun toggleCategory(cat: CategoryEntity) = viewModelScope.launch {
        changed += cat.type
        c.db.categories().setIncluded(playlistId, cat.type, listOf(cat.categoryId), !cat.included)
    }

    fun unhide(ch: ChannelEntity) = viewModelScope.launch { c.channels.setHidden(ch.playlistId, ch.streamId, false) }

    /** Downloads again what the change affects: everything on first run or for live TV, else just movies and series. */
    fun finish(firstRun: Boolean) {
        when {
            firstRun -> c.appScope.launch {
                // Optional: the channels still load if this fails.
                try {
                    addFreeGuides()
                } catch (e: SQLException) {
                    Log.w("Categories", "Couldn't add the free guides", e)
                }
                c.sync.refresh(playlistId)
            }
            CAT_LIVE in changed -> c.sync.refresh(playlistId)
            changed.isNotEmpty() -> c.sync.refreshVod(playlistId)
        }
    }

    /** Setting up the free channels: the free guides for the countries chosen (where there is one), before the first load. */
    private suspend fun addFreeGuides() {
        if (c.playlists.get(playlistId)?.server != PlaylistRepository.FREE_PLAYLIST_URL) return
        val countries = c.db.categories().observe(playlistId, CAT_LIVE).first().filter { it.included }.map { it.lang }
        val existing = c.epg.observeSources().first().mapTo(HashSet()) { it.url }
        FreeGuides.forGroups(countries).filter { it.url !in existing }.forEach { c.epg.addSource(it.sourceName, it.url) }
    }

    /** Focus goes to the first group once, when the screen opens (not each time filtering changes the first group). */
    var firstFocusPending = true

    companion object {
        const val UNSET = "\u0000"

        fun matches(text: String, q: String) = q.isBlank() || text.contains(q, ignoreCase = true)
    }
}

@Composable
fun CategoriesScreen(playlistId: Long, firstRun: Boolean) {
    val vm = appViewModel(key = "cats-$playlistId") { CategoriesViewModel(it, playlistId) }
    val nav = LocalNavigator.current
    val categories by vm.categories.collectAsState()
    val type by vm.type.collectAsState()
    val hidden by vm.hidden.collectAsState()
    val query = vm.query.trim()
    val groups = remember(categories, query) { vm.groups(categories, query) }
    val hiddenShown = remember(hidden, query) { hidden.filter { CategoriesViewModel.matches(it.name, query) } }
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
            Muted("Loading ${categories.count { it.included }} of ${categories.size} categories", Modifier.padding(end = 16.dp))
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
                item {
                    TvTextField(
                        vm.query,
                        vm::filter,
                        "Filter countries and categories",
                        Modifier.fillMaxWidth(),
                        leading = { Icon(AppIcons.Filter, null, tint = Palette.textDim, modifier = Modifier.size(18.dp)) }
                    )
                }
                item { Text("DETECTED FROM CATEGORY NAMES", style = MaterialTheme.typography.labelMedium, color = Palette.textMuted) }
                if (groups.isEmpty() && query.isNotEmpty()) item { Muted("Nothing matches \u201c$query\u201d.") }
                items(groups, key = { it.code ?: "" }) { g ->
                    TvCard(
                        onClick = { vm.toggleGroup(g) },
                        selected = g.code == vm.selectedGroup,
                        color = Palette.surfaceLow,
                        modifier = Modifier.restoreFocusIf(vm.firstFocusPending && g == groups.first()) { vm.firstFocusPending = false }
                            .fillMaxWidth()
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
                if (type == CAT_LIVE && hiddenShown.isNotEmpty()) {
                    item {
                        Text(
                            "HIDDEN CHANNELS",
                            style = MaterialTheme.typography.labelMedium,
                            color = Palette.textMuted,
                            modifier = Modifier.padding(top = 16.dp)
                        )
                    }
                    items(hiddenShown, key = { "h${it.streamId}" }) { ch ->
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
            val shown = remember(categories, selected, query) { vm.shown(categories, selected, query) }
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
