package com.biskyt.mytvplayer.ui.guide

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.cachedIn
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.biskyt.mytvplayer.AppContainer
import com.biskyt.mytvplayer.core.epg.EpgCandidate
import com.biskyt.mytvplayer.core.epg.EpgMatch
import com.biskyt.mytvplayer.data.FreeGuide
import com.biskyt.mytvplayer.data.FreeGuides
import com.biskyt.mytvplayer.data.SyncState
import com.biskyt.mytvplayer.data.db.ChannelEntity
import com.biskyt.mytvplayer.data.db.EpgSourceEntity
import com.biskyt.mytvplayer.data.db.MappingInfo
import com.biskyt.mytvplayer.data.db.MatchCounts
import com.biskyt.mytvplayer.data.db.MatchRow
import com.biskyt.mytvplayer.ui.appViewModel
import com.biskyt.mytvplayer.ui.common.AppIcons
import com.biskyt.mytvplayer.ui.common.ChannelLogo
import com.biskyt.mytvplayer.ui.common.Muted
import com.biskyt.mytvplayer.ui.common.PillButton
import com.biskyt.mytvplayer.ui.common.Toggle
import com.biskyt.mytvplayer.ui.common.TvCard
import com.biskyt.mytvplayer.ui.common.TvTextField
import com.biskyt.mytvplayer.ui.common.formatCount
import com.biskyt.mytvplayer.ui.common.formatDateTime
import com.biskyt.mytvplayer.ui.common.rememberFirstFocus
import com.biskyt.mytvplayer.ui.common.syncLabel
import com.biskyt.mytvplayer.ui.settings.SettingsPage
import com.biskyt.mytvplayer.ui.theme.LocalAccent
import com.biskyt.mytvplayer.ui.theme.Palette
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

// ---------------------------------------------------------------- sources

class GuideSourcesViewModel(private val c: AppContainer) : ViewModel() {
    val sources = c.epg.observeSources().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val sync = c.sync.state
    var name by mutableStateOf("")
    var url by mutableStateOf("")

    fun add() = viewModelScope.launch {
        if (url.isBlank()) return@launch
        c.epg.addSource(name, url)
        name = ""
        url = ""
        c.sync.refreshGuide()
    }

    fun toggle(s: EpgSourceEntity) = viewModelScope.launch { c.epg.setSourceEnabled(s, !s.enabled) }
    fun remove(s: EpgSourceEntity) = viewModelScope.launch { c.epg.removeSource(s.id) }
    fun refresh() = c.sync.refreshGuide()

    val freeGuides = FreeGuides.all()
    var showAllFree by mutableStateOf(false)

    /** Adds a free country guide (and loads it), or removes it if it's already there. */
    fun toggleFree(g: FreeGuide) = viewModelScope.launch {
        val existing = sources.value.firstOrNull { it.url == g.url }
        if (existing != null) {
            c.epg.removeSource(existing.id)
        } else {
            c.epg.addSource(g.sourceName, g.url)
            c.sync.refreshGuide()
        }
    }
}

@Composable
fun GuideSourcesScreen() {
    val vm = appViewModel { GuideSourcesViewModel(it) }
    val sources by vm.sources.collectAsState()
    val sync by vm.sync.collectAsState()
    val busy = sync !is SyncState.Idle && sync !is SyncState.Failed
    SettingsPage("TV guide sources") {
        item {
            Muted("Guides are updated every day. Programmes are only stored for channels that use them, for the next 3 days.", maxLines = 2)
        }
        // Updates run in the background and can take minutes for large guides: say what's happening.
        syncLabel(sync)?.let { label ->
            item {
                Text(
                    label,
                    style = MaterialTheme.typography.titleSmall,
                    color = if (sync is SyncState.Failed) Palette.warning else LocalAccent.current.color
                )
            }
        }
        items(sources, key = { it.id }) { s ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                TvCard(
                    onClick = { vm.toggle(s) },
                    color = Palette.surfaceLow,
                    modifier = (
                        if (s ==
                            sources.first()
                        ) {
                            rememberFirstFocus()
                        } else {
                            Modifier
                        }
                        ).weight(1f)
                ) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(s.name, style = MaterialTheme.typography.titleMedium)
                            Muted(sourceDetails(s))
                            s.lastError?.let {
                                Text(
                                    "Last update failed: $it",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Palette.warning,
                                    maxLines = 1
                                )
                            }
                        }
                        Toggle(s.enabled)
                    }
                }
                if (s.playlistId == null) PillButton("Remove", { vm.remove(s) })
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 12.dp)) {
                Text("Add an XMLTV guide", style = MaterialTheme.typography.titleMedium)
                val urlEdit = rememberSaveable { mutableStateOf(false) }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TvTextField(vm.name, { vm.name = it }, "Name (optional)", Modifier.weight(0.35f), onNext = { urlEdit.value = true })
                    TvTextField(
                        vm.url,
                        { vm.url = it },
                        "https://…/guide.xml.gz",
                        Modifier.weight(0.65f),
                        keyboardType = KeyboardType.Uri,
                        editing = urlEdit
                    )
                    PillButton("Add", vm::add, primary = true)
                }
            }
        }
        item { PillButton(if (busy) "Updating…" else "Update guide now", { if (!busy) vm.refresh() }, Modifier.padding(top = 12.dp)) }
        freeGuides(vm, sources)
    }
}

/** One-press free guides by country (the TV's own country first; the rest on request). */
private fun LazyListScope.freeGuides(vm: GuideSourcesViewModel, sources: List<EpgSourceEntity>) {
    val added = sources.mapTo(HashSet()) { it.url }
    item {
        Column(Modifier.padding(top = 20.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Free guides by country", style = MaterialTheme.typography.titleMedium)
            Muted("From iptv-epg.org. Large countries can take a few minutes to load on a TV box.", maxLines = 2)
        }
    }
    val shown = if (vm.showAllFree) vm.freeGuides else vm.freeGuides.take(1)
    items(shown, key = { "free-${it.code}" }) { g ->
        TvCard(onClick = { vm.toggleFree(g) }, color = Palette.surfaceLow, modifier = Modifier.fillMaxWidth()) {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(g.country, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                Toggle(g.url in added)
            }
        }
    }
    if (!vm.showAllFree) {
        item { PillButton("Show all ${vm.freeGuides.size} countries", { vm.showAllFree = true }) }
    }
}

private fun sourceDetails(s: EpgSourceEntity): String {
    val parts = mutableListOf(if (s.playlistId != null) "From your provider" else s.url)
    if (s.channelCount > 0) parts += "${formatCount(s.channelCount)} channels"
    if (s.lastImportAt > 0) parts += "updated ${formatDateTime(s.lastImportAt)}"
    if (!s.enabled) parts += "off"
    return parts.joinToString(" · ")
}

// ---------------------------------------------------------------- matching

enum class MatchTab(val label: String, val status: Int) {
    ALL(
        "All",
        0
    ),
    UNMATCHED("No guide", 1),
    AUTO("Automatic", 2),
    MANUAL("Set by you", 3)
}

/** One candidate in the right-hand panel: from the matcher (with a score) or from a name search. */
data class GuideChoice(val candidate: EpgCandidate, val score: Double?, val sourceName: String)

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class GuideMatchingViewModel(private val c: AppContainer, private val playlistId: Long, initial: Long?) : ViewModel() {
    val tab = MutableStateFlow(MatchTab.ALL)
    val filter = MutableStateFlow("")
    val counts = c.epg.observeMatchCounts(playlistId).stateIn(viewModelScope, SharingStarted.Eagerly, MatchCounts(0, 0, 0, 0))
    val channels = combine(tab, filter.debounce(250)) { t, f -> t to f }
        .flatMapLatest { (t, f) -> c.epg.pageForMatching(playlistId, f, t.status) }
        .cachedIn(viewModelScope)

    var selected by mutableStateOf<ChannelEntity?>(null)
        private set
    var mapping by mutableStateOf<MappingInfo?>(null)
        private set
    var search by mutableStateOf("")
    var choices by mutableStateOf<List<GuideChoice>>(emptyList())
        private set
    var busy by mutableStateOf(false)
        private set

    private var sources: Map<Long, String> = emptyMap()
    private var searchJob: Job? = null
    private var selectJob: Job? = null

    init {
        viewModelScope.launch {
            c.epg.observeSources().collect { list -> sources = list.associate { it.id to it.name } }
        }
        if (initial != null) viewModelScope.launch { c.channels.get(playlistId, initial)?.let { select(it) } }
    }

    fun select(ch: ChannelEntity) {
        selected = ch
        search = ""
        searchJob?.cancel()
        selectJob?.cancel() // quick changes of channel must not show an earlier channel's suggestions
        selectJob = viewModelScope.launch {
            mapping = c.epg.mappingInfo(playlistId, listOf(ch.streamId)).firstOrNull()
            choices = c.epg.suggestions(ch.name, ch.providerEpgId).map { it.toChoice() }
        }
    }

    fun onSearch(q: String) {
        search = q
        searchJob?.cancel()
        val ch = selected ?: return
        searchJob = viewModelScope.launch {
            delay(250)
            choices = if (q.isBlank()) {
                c.epg.suggestions(ch.name, ch.providerEpgId).map { it.toChoice() }
            } else {
                c.epg.searchGuideChannels(q).map {
                    GuideChoice(
                        EpgCandidate(it.sourceId, it.xmlId, it.displayName, it.nameNorm),
                        null,
                        sources[it.sourceId] ?: ""
                    )
                }
            }
        }
    }

    private fun EpgMatch.toChoice() = GuideChoice(candidate, score, sources[candidate.sourceId] ?: "")

    private fun change(block: suspend (ChannelEntity) -> Unit) {
        val ch = selected ?: return
        busy = true
        viewModelScope.launch {
            block(ch)
            mapping = c.epg.mappingInfo(playlistId, listOf(ch.streamId)).firstOrNull()
            busy = false
        }
    }

    fun use(choice: GuideChoice) = change {
        c.epg.setManual(
            playlistId,
            it.streamId,
            choice.candidate.sourceId,
            choice.candidate.xmlId,
            mapping?.offsetMinutes ?: 0
        )
    }
    fun noGuide() = change { c.epg.setNoGuide(playlistId, it.streamId) }
    fun reset() = change { c.epg.resetToAutomatic(playlistId, it.streamId, it.name, it.providerEpgId) }
    override fun onCleared() = c.epg.releaseMatcher()

    fun shiftTime() = change {
        val next = ((mapping?.offsetMinutes ?: 0) / 60 + 1).let { h -> if (h > 3) -3 else h } * 60
        c.epg.setOffset(playlistId, it.streamId, next)
    }
}

@Composable
fun GuideMatchingScreen(playlistId: Long, streamId: Long?) {
    val vm = appViewModel(key = "match-$playlistId-$streamId") { GuideMatchingViewModel(it, playlistId, streamId) }
    val tab by vm.tab.collectAsState()
    val counts by vm.counts.collectAsState()
    val filter by vm.filter.collectAsState()
    val items = vm.channels.collectAsLazyPagingItems()

    Column(
        Modifier.fillMaxSize().background(Palette.background).padding(horizontal = 56.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("TV guide matching", style = MaterialTheme.typography.headlineLarge)
                Muted("Pick which guide data each channel uses. Your choices are kept when the playlist updates.")
            }
            MatchTab.entries.forEach { t ->
                val n = when (t) {
                    MatchTab.ALL -> counts.total
                    MatchTab.UNMATCHED -> counts.unmatched
                    MatchTab.AUTO -> counts.auto
                    MatchTab.MANUAL -> counts.manual
                }
                PillButton("${t.label} ${formatCount(n)}", { vm.tab.value = t }, Modifier.padding(start = 8.dp), primary = t == tab)
            }
        }
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            Column(Modifier.width(480.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                TvTextField(
                    filter,
                    { vm.filter.value = it },
                    "Filter your channels by name",
                    rememberFirstFocus().fillMaxWidth(),
                    leading = { Icon(AppIcons.Filter, null, tint = Palette.textDim, modifier = Modifier.size(18.dp)) }
                )
                LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
                    items(count = items.itemCount, key = items.itemKey { it.channel.streamId }) { i ->
                        val row = items[i] ?: return@items
                        MatchRowCard(row, row.channel.streamId == vm.selected?.streamId) { vm.select(row.channel) }
                    }
                }
            }
            ChoicePanel(vm, Modifier.weight(1f))
        }
    }
}

@Composable
private fun MatchRowCard(row: MatchRow, selected: Boolean, onClick: () -> Unit) {
    val ch = row.channel
    TvCard(onClick = onClick, selected = selected, color = Palette.surfaceLow, modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            ChannelLogo(ch.logoUrl, ch.name, 52.dp, 30.dp)
            Column(Modifier.weight(1f)) {
                Text(ch.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Muted(row.guideName?.let { "$it (${row.sourceName})" } ?: "No guide data")
            }
            StatusChip(row)
        }
    }
}

@Composable
private fun StatusChip(row: MatchRow) {
    val (label, fg, bg) = when {
        row.mapSourceId == null -> Triple("NO GUIDE", Palette.danger, Palette.danger.copy(alpha = 0.15f))
        row.mapManual == true -> Triple("SET", LocalAccent.current.soft, LocalAccent.current.tint)
        else -> Triple("AUTO ${((row.mapScore ?: 0.0) * 100).toInt()}%", Palette.warning, Palette.warning.copy(alpha = 0.15f))
    }
    Text(
        label,
        style = MaterialTheme.typography.labelSmall,
        color = fg,
        modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(bg).padding(horizontal = 8.dp, vertical = 3.dp)
    )
}

@Composable
private fun ChoicePanel(vm: GuideMatchingViewModel, modifier: Modifier) {
    val ch = vm.selected
    Column(
        modifier.fillMaxSize().clip(RoundedCornerShape(16.dp)).background(Palette.surfaceLow).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        if (ch == null) {
            Muted("Select a channel on the left to choose its guide.", maxLines = 2)
            return@Column
        }
        Muted("Choose a guide for")
        Text(ch.name, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        val m = vm.mapping
        Muted(
            when {
                m == null || m.sourceId == null -> "Currently: no guide"
                else ->
                    "Currently: ${m.displayName ?: m.xmlId} (${m.sourceName ?: ""})" +
                        (if (m.offsetMinutes != 0) " · shifted ${m.offsetMinutes / 60}h" else "")
            }
        )
        TvTextField(
            vm.search,
            vm::onSearch,
            "Search all guide channels",
            Modifier.fillMaxWidth(),
            leading = { Icon(AppIcons.Search, null, tint = Palette.textDim, modifier = Modifier.size(18.dp)) }
        )
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(vm.choices, key = { "${it.candidate.sourceId}/${it.candidate.xmlId}" }) { choice ->
                val current = m?.sourceId == choice.candidate.sourceId && m.xmlId == choice.candidate.xmlId
                TvCard(onClick = { vm.use(choice) }, selected = current, color = Palette.surface, modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                choice.candidate.displayName,
                                style = MaterialTheme.typography.titleSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Muted("${choice.sourceName} · ${choice.candidate.xmlId}")
                        }
                        choice.score?.let {
                            Text(
                                "${(it * 100).toInt()}% match",
                                style = MaterialTheme.typography.labelMedium,
                                color = LocalAccent.current.soft
                            )
                        }
                    }
                }
            }
            if (vm.choices.isEmpty()) {
                item {
                    Muted("No guide channels found. Try a shorter search, or add another guide source.", maxLines = 2)
                }
            }
        }
        Box(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PillButton("Time shift: ${(m?.offsetMinutes ?: 0) / 60} h", vm::shiftTime)
            PillButton("No guide", vm::noGuide)
            PillButton("Reset to automatic", vm::reset)
        }
    }
}
