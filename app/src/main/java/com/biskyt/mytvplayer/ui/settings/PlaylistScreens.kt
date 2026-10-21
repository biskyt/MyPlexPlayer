package com.biskyt.mytvplayer.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.biskyt.mytvplayer.AppContainer
import com.biskyt.mytvplayer.data.db.PlaylistEntity
import com.biskyt.mytvplayer.ui.LocalNavigator
import com.biskyt.mytvplayer.ui.Screen
import com.biskyt.mytvplayer.ui.appViewModel
import com.biskyt.mytvplayer.ui.common.Muted
import com.biskyt.mytvplayer.ui.common.PillButton
import com.biskyt.mytvplayer.ui.common.StatusPanel
import com.biskyt.mytvplayer.ui.common.TvCard
import com.biskyt.mytvplayer.ui.common.TvTextField
import com.biskyt.mytvplayer.ui.common.WorkRunner
import com.biskyt.mytvplayer.ui.common.WorkStatus
import com.biskyt.mytvplayer.ui.common.formatDateTime
import com.biskyt.mytvplayer.ui.common.rememberFirstFocus
import com.biskyt.mytvplayer.ui.theme.LocalAccent
import com.biskyt.mytvplayer.ui.theme.Palette
import java.io.IOException
import java.text.DateFormat
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.Date
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class PlaylistsViewModel(private val c: AppContainer) : ViewModel() {
    val state = combine(c.playlists.observePlaylists(), c.settings.settings) { list, s -> list to s.activePlaylistId }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList<PlaylistEntity>() to 0L)

    fun activate(p: PlaylistEntity, then: () -> Unit) = viewModelScope.launch {
        c.settings.update { it.copy(activePlaylistId = p.id) }
        if (p.isStale()) c.sync.refresh(p.id)
        then()
    }

    private val work = WorkRunner(c.appScope, viewModelScope, c.sync.state)

    /** Progress and the outcome of the last update from this page. */
    val status: WorkStatus? get() = work.status

    /** Downloads the playlist's channels, guide, movies and series again: its progress, then how it went. */
    fun update(p: PlaylistEntity) = work.run("Updating ${p.name}") {
        c.sync.refreshNow(p.id).fold(
            { guideErrors ->
                if (guideErrors.isEmpty()) {
                    WorkStatus("${p.name} updated", "Its channels, TV guide, films and series are up to date.")
                } else {
                    // As on the TV guide page: the channels updated, but say which guide didn't.
                    WorkStatus("${p.name} updated, but some guides couldn't be loaded", guideErrors.values.first(), failed = true)
                }
            },
            { WorkRunner.failed("Couldn't update ${p.name}", it) }
        )
    }

    /** Adding the free channels, films and series: true while downloading; [freeError] if it failed. */
    var addingFree by mutableStateOf(false)
        private set
    var freeError by mutableStateOf<String?>(null)
        private set

    /** Sets up the free channels, films and series (as on first launch), then [then] gets the new playlist's id. */
    fun addFree(then: (Long) -> Unit) {
        if (addingFree) return
        addingFree = true
        freeError = null
        viewModelScope.launch {
            val added = c.playlists.addFree()
            addingFree = false
            added.onSuccess(then).onFailure { freeError = it.message }
        }
    }
}

@Composable
fun PlaylistsScreen() {
    val vm = appViewModel { PlaylistsViewModel(it) }
    val nav = LocalNavigator.current
    val (playlists, active) = vm.state.collectAsState().value
    val accent = LocalAccent.current
    SettingsPage("Playlists") {
        // Above the playlists, so "Update now" on any of them shows its progress and outcome in the same place.
        item(key = "status") { StatusPanel(vm.status) }
        items(playlists.size) { i ->
            val p = playlists[i]
            val isActive = p.id == active
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                TvCard(
                    onClick = { vm.activate(p) { nav.reset(Screen.Home) } },
                    selected = isActive,
                    modifier = (if (i == 0) rememberFirstFocus() else Modifier).weight(1f)
                ) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier.size(40.dp).clip(CircleShape).background(if (isActive) accent.color else Palette.textDim),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(p.name.take(1).uppercase(), style = MaterialTheme.typography.titleMedium, color = Palette.background)
                        }
                        Column(Modifier.padding(start = 14.dp).weight(1f)) {
                            Text(p.name, style = MaterialTheme.typography.titleMedium)
                            Muted(playlistDetails(p))
                            expiry(p.expiresAt)?.let { (text, soon) ->
                                Text(
                                    text,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = if (soon) Palette.warning else Palette.textMuted
                                )
                            }
                        }
                        if (isActive) Text("IN USE", style = MaterialTheme.typography.labelMedium, color = accent.color)
                    }
                }
                PillButton("Update now", { vm.update(p) })
                PillButton("Edit", { nav.push(Screen.PlaylistEdit(p.id)) })
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PillButton("+ Add playlist", {
                    nav.push(Screen.PlaylistEdit(null))
                }, if (playlists.isEmpty()) rememberFirstFocus() else Modifier, primary = playlists.isEmpty())
                // The free channels can be typed in as an M3U address too, but that's a long one to enter with a remote.
                if (playlists.none { it.isFree }) {
                    PillButton(
                        if (vm.addingFree) "Downloading free channels…" else "+ Add free TV, films and series",
                        // First-run style: Continue downloads the channels and goes to Home.
                        { vm.addFree { id -> nav.push(Screen.Categories(id, firstRun = true)) } }
                    )
                }
            }
        }
        vm.freeError?.let { item { Text(it, style = MaterialTheme.typography.bodyLarge, color = Palette.warning) } }
        item { Muted("Select a playlist to switch to it.") }
    }
}

/**
 * "Expires 12 Mar 2027" from the provider's expiry date (seconds), and whether it's soon or past (shown as a warning).
 * Null when there's none: free playlists, and providers that don't say.
 */
internal fun expiry(
    expiresAtSec: Long?,
    now: Long = System.currentTimeMillis(),
    zone: ZoneId = ZoneId.systemDefault()
): Pair<String, Boolean>? {
    val at = expiresAtSec?.takeIf { it > 0 }?.times(1000) ?: return null
    val date = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(at))

    // Calendar days, as the date shown: tomorrow is "in 1 day" at any time of day.
    fun day(ms: Long) = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()
    val days = ChronoUnit.DAYS.between(day(now), day(at))
    return when {
        at <= now -> "Expired on $date" to true
        days < 1 -> "Expires today ($date)" to true
        days <= EXPIRY_WARNING_DAYS -> "Expires $date, in $days ${if (days == 1L) "day" else "days"}" to true
        else -> "Expires $date" to false
    }
}

private const val EXPIRY_WARNING_DAYS = 14

private fun playlistDetails(p: PlaylistEntity): String {
    val parts = mutableListOf(if (p.isM3u) "M3U" else "Xtream")
    if (p.lastSyncAt > 0) parts += "updated ${formatDateTime(p.lastSyncAt)}"
    return parts.joinToString(" · ")
}

class PlaylistEditViewModel(private val c: AppContainer, private val id: Long?) : ViewModel() {
    var m3u by mutableStateOf(false)
    var name by mutableStateOf("")
    var server by mutableStateOf("")
    var username by mutableStateOf("")
    var password by mutableStateOf("")

    /** M3U: optional movie and series playlist addresses. */
    var moviesUrl by mutableStateOf("")
    var seriesUrl by mutableStateOf("")
    var busy by mutableStateOf(false)
        private set
    var message by mutableStateOf<String?>(null)
        private set
    private var original: PlaylistEntity? = null

    init {
        if (id != null) {
            viewModelScope.launch {
                c.playlists.get(id)?.let {
                    original = it
                    m3u = it.isM3u
                    name = it.name
                    server = it.server
                    username = it.username
                    password = it.password
                    moviesUrl = it.moviesUrl.orEmpty()
                    seriesUrl = it.seriesUrl.orEmpty()
                }
            }
        }
    }

    private fun run(block: suspend () -> Unit) {
        if (busy) return
        busy = true
        message = null
        viewModelScope.launch {
            try {
                block()
            } catch (e: IOException) {
                message = e.message ?: "Something went wrong"
            } finally {
                busy = false
            }
        }
    }

    fun test() = run {
        c.playlists.testConnection(m3u, server, username, password)
        message = "Connected ✓"
    }

    /** Saves; [onSaved] gets the playlist id. Changing login details re-downloads the channels. */
    fun save(onSaved: (Long) -> Unit) = run {
        val old = original
        if (old == null) {
            message = if (m3u) "Downloading playlist…" else "Checking login…"
            val newId = if (m3u) {
                c.playlists.addM3u(
                    name,
                    server,
                    moviesUrl,
                    seriesUrl
                )
            } else {
                c.playlists.add(name, server, username, password)
            }
            message = null
            onSaved(newId)
        } else {
            // From what's stored now, not when the screen opened: a sync may have recorded something since.
            val current = c.playlists.get(old.id) ?: old
            val updated = current.copy(
                name = name.ifBlank {
                    old.name
                },
                server = server.trim(),
                username = username.trim(),
                password = password,
                moviesUrl = moviesUrl.trim().ifEmpty { null }.takeIf { old.isM3u },
                seriesUrl = seriesUrl.trim().ifEmpty { null }.takeIf { old.isM3u }
            )
            c.playlists.update(updated)
            if (updated.server != old.server || updated.username != old.username || updated.password != old.password) {
                c.sync.refresh(old.id)
            } else if (updated.moviesUrl != old.moviesUrl || updated.seriesUrl != old.seriesUrl) {
                c.sync.refreshVod(old.id)
            }
            onSaved(old.id)
        }
    }

    /** [onDeleted] is told whether any playlist is left. */
    fun delete(onDeleted: (Boolean) -> Unit) = run {
        id?.let { c.playlists.delete(it) }
        onDeleted(c.playlists.allIds().isNotEmpty())
    }
}

@Composable
fun PlaylistEditScreen(id: Long?, firstRun: Boolean) {
    val vm = appViewModel(key = "edit-$id") { PlaylistEditViewModel(it, id) }
    val nav = LocalNavigator.current
    Column(
        Modifier.fillMaxSize().background(Palette.background).padding(horizontal = 96.dp, vertical = 40.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text(
            when {
                firstRun -> "Welcome to MyTVPlayer"
                id == null -> "Add playlist"
                else -> "Edit playlist"
            },
            style = MaterialTheme.typography.headlineLarge
        )
        if (firstRun) Muted("Enter the details from your TV provider.")
        if (id == null) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PillButton("Xtream Codes", { vm.m3u = false }, primary = !vm.m3u)
                PillButton("M3U playlist URL", { vm.m3u = true }, primary = vm.m3u)
            }
        }
        // The keyboard's Next key moves straight on to the next box, ready for typing.
        val nameEdit = rememberSaveable { mutableStateOf(false) }
        val serverEdit = rememberSaveable { mutableStateOf(false) }
        val userEdit = rememberSaveable { mutableStateOf(false) }
        val passwordEdit = rememberSaveable { mutableStateOf(false) }
        val moviesEdit = rememberSaveable { mutableStateOf(false) }
        val seriesEdit = rememberSaveable { mutableStateOf(false) }
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Field("Name", Modifier.weight(1f)) {
                TvTextField(
                    vm.name,
                    { vm.name = it },
                    "e.g. Home TV",
                    rememberFirstFocus().fillMaxWidth(),
                    editing = nameEdit,
                    onNext = { serverEdit.value = true }
                )
            }
            Field(if (vm.m3u) "Playlist address" else "Server address", Modifier.weight(1f)) {
                TvTextField(
                    vm.server,
                    { vm.server = it },
                    if (vm.m3u) "https://example.com/playlist.m3u" else "http://example.com:8080",
                    Modifier.fillMaxWidth(),
                    keyboardType = KeyboardType.Uri,
                    editing = serverEdit,
                    onNext = { (if (vm.m3u) moviesEdit else userEdit).value = true }
                )
            }
        }
        if (vm.m3u) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Field("Movies playlist (optional)", Modifier.weight(1f)) {
                    TvTextField(
                        vm.moviesUrl,
                        { vm.moviesUrl = it },
                        "https://example.com/movies.m3u",
                        Modifier.fillMaxWidth(),
                        keyboardType = KeyboardType.Uri,
                        editing = moviesEdit,
                        onNext = { seriesEdit.value = true }
                    )
                }
                Field("Series playlist (optional)", Modifier.weight(1f)) {
                    TvTextField(
                        vm.seriesUrl,
                        { vm.seriesUrl = it },
                        "https://example.com/series.m3u",
                        Modifier.fillMaxWidth(),
                        keyboardType = KeyboardType.Uri,
                        editing = seriesEdit
                    )
                }
            }
            Muted("One playlist with both is fine: episodes (\"Show S01E02\") go to Series, the rest to Movies.", maxLines = 2)
        }
        if (!vm.m3u) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Field("Username", Modifier.weight(1f)) {
                    TvTextField(
                        vm.username,
                        { vm.username = it },
                        "Username",
                        Modifier.fillMaxWidth(),
                        autoCorrect = false,
                        editing = userEdit,
                        onNext = { passwordEdit.value = true }
                    )
                }
                Field("Password", Modifier.weight(1f)) {
                    TvTextField(
                        vm.password,
                        { vm.password = it },
                        "Password",
                        Modifier.fillMaxWidth(),
                        autoCorrect = false,
                        editing = passwordEdit
                    )
                }
            }
        }
        vm.message?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodyLarge,
                color = if (it.endsWith("✓")) LocalAccent.current.color else Palette.warning
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            PillButton(
                if (vm.busy) {
                    "Please wait…"
                } else if (firstRun) {
                    "Continue"
                } else {
                    "Save"
                },
                {
                    vm.save { pid ->
                        if (id == null) nav.replace(Screen.Categories(pid, firstRun = true)) else nav.pop()
                    }
                },
                primary = true
            )
            PillButton("Test connection", vm::test)
            if (id != null) {
                Box(Modifier.width(40.dp))
                PillButton("Delete playlist", {
                    vm.delete { any -> nav.reset(if (any) Screen.Home else Screen.Welcome) }
                })
            }
        }
    }
}

@Composable
private fun Field(label: String, modifier: Modifier, content: @Composable () -> Unit) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = Palette.textMuted)
        content()
    }
}
