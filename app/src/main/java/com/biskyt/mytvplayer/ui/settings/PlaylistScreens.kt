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
import com.biskyt.mytvplayer.ui.common.TvCard
import com.biskyt.mytvplayer.ui.common.TvTextField
import com.biskyt.mytvplayer.ui.common.formatDateTime
import com.biskyt.mytvplayer.ui.common.rememberFirstFocus
import com.biskyt.mytvplayer.ui.theme.LocalAccent
import com.biskyt.mytvplayer.ui.theme.Palette
import java.io.IOException
import java.text.DateFormat
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
}

@Composable
fun PlaylistsScreen() {
    val vm = appViewModel { PlaylistsViewModel(it) }
    val nav = LocalNavigator.current
    val (playlists, active) = vm.state.collectAsState().value
    val accent = LocalAccent.current
    SettingsPage("Playlists") {
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
                        }
                        if (isActive) Text("IN USE", style = MaterialTheme.typography.labelMedium, color = accent.color)
                    }
                }
                PillButton("Edit", { nav.push(Screen.PlaylistEdit(p.id)) })
            }
        }
        item {
            PillButton("+ Add playlist", {
                nav.push(Screen.PlaylistEdit(null))
            }, if (playlists.isEmpty()) rememberFirstFocus() else Modifier, primary = playlists.isEmpty())
        }
        item { Muted("Select a playlist to switch to it.") }
    }
}

private fun playlistDetails(p: PlaylistEntity): String {
    val parts = mutableListOf("Xtream")
    p.expiresAt?.takeIf { it > 0 }?.let { parts += "expires ${DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(it * 1000))}" }
    if (p.lastSyncAt > 0) parts += "updated ${formatDateTime(p.lastSyncAt)}"
    return parts.joinToString(" · ")
}

class PlaylistEditViewModel(private val c: AppContainer, private val id: Long?) : ViewModel() {
    var name by mutableStateOf("")
    var server by mutableStateOf("")
    var username by mutableStateOf("")
    var password by mutableStateOf("")
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
                    name = it.name
                    server = it.server
                    username = it.username
                    password = it.password
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
        c.playlists.testLogin(server, username, password)
        message = "Connected ✓"
    }

    /** Saves; [onSaved] gets the playlist id. Changing login details re-downloads the channels. */
    fun save(onSaved: (Long) -> Unit) = run {
        val old = original
        if (old == null) {
            message = "Checking login…"
            val newId = c.playlists.add(name, server, username, password)
            message = null
            onSaved(newId)
        } else {
            val updated = old.copy(
                name = name.ifBlank {
                    old.name
                },
                server = server.trim(),
                username = username.trim(),
                password = password
            )
            c.playlists.update(updated)
            if (updated.server != old.server || updated.username != old.username || updated.password != old.password) c.sync.refresh(old.id)
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
        if (firstRun) Muted("Enter the Xtream Codes details from your TV provider.")
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Field("Name", Modifier.weight(1f)) {
                TvTextField(vm.name, { vm.name = it }, "e.g. Home TV", rememberFirstFocus().fillMaxWidth())
            }
            Field("Server address", Modifier.weight(1f)) {
                TvTextField(vm.server, {
                    vm.server = it
                }, "http://example.com:8080", Modifier.fillMaxWidth(), keyboardType = KeyboardType.Uri)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Field("Username", Modifier.weight(1f)) { TvTextField(vm.username, { vm.username = it }, "Username", Modifier.fillMaxWidth()) }
            Field("Password", Modifier.weight(1f)) {
                TvTextField(vm.password, { vm.password = it }, "Password", Modifier.fillMaxWidth(), password = true)
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
                    vm.delete { any -> nav.reset(if (any) Screen.Home else Screen.PlaylistEdit(null, firstRun = true)) }
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
