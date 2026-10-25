package com.biskyt.myplexplayer.ui.settings

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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.biskyt.myplexplayer.AppContainer
import com.biskyt.myplexplayer.R
import com.biskyt.myplexplayer.appString
import com.biskyt.myplexplayer.data.db.PlaylistEntity
import com.biskyt.myplexplayer.ui.LocalNavigator
import com.biskyt.myplexplayer.ui.Screen
import com.biskyt.myplexplayer.ui.appViewModel
import com.biskyt.myplexplayer.ui.common.Muted
import com.biskyt.myplexplayer.ui.common.PillButton
import com.biskyt.myplexplayer.ui.common.StatusPanel
import com.biskyt.myplexplayer.ui.common.TvCard
import com.biskyt.myplexplayer.ui.common.TvTextField
import com.biskyt.myplexplayer.ui.common.WorkRunner
import com.biskyt.myplexplayer.ui.common.WorkStatus
import com.biskyt.myplexplayer.ui.common.formatDate
import com.biskyt.myplexplayer.ui.common.formatDateTime
import com.biskyt.myplexplayer.ui.common.rememberFirstFocus
import com.biskyt.myplexplayer.ui.theme.LocalAccent
import com.biskyt.myplexplayer.ui.theme.Palette
import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit
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

    /** Removes [p]; [onRemoved] is told whether any source is left. */
    fun remove(p: PlaylistEntity, onRemoved: (Boolean) -> Unit) = viewModelScope.launch {
        c.playlists.delete(p.id)
        onRemoved(c.playlists.allIds().isNotEmpty())
    }

    private val work = WorkRunner(c.appScope, viewModelScope, c.sync.state)

    /** Progress and the outcome of the last update from this page. */
    val status: WorkStatus? get() = work.status

    /** Downloads the playlist's channels, guide, movies and series again: its progress, then how it went. */
    fun update(p: PlaylistEntity) = work.run(appString(R.string.settings_playlist_updating, p.name)) {
        c.sync.refreshNow(p.id).fold(
            { guideErrors ->
                if (guideErrors.isEmpty()) {
                    WorkStatus(appString(R.string.settings_playlist_updated, p.name), appString(R.string.settings_playlist_updated_detail))
                } else {
                    // As on the TV guide page: the channels updated, but say which guide didn't.
                    WorkStatus(
                        appString(R.string.settings_playlist_updated_guides_failed, p.name),
                        guideErrors.values.first(),
                        failed = true
                    )
                }
            },
            { WorkRunner.failed(appString(R.string.settings_playlist_update_failed, p.name), it) }
        )
    }
}

@Composable
fun PlaylistsScreen() {
    val vm = appViewModel { PlaylistsViewModel(it) }
    val nav = LocalNavigator.current
    val (playlists, active) = vm.state.collectAsState().value
    val accent = LocalAccent.current
    SettingsPage(stringResource(R.string.settings_playlists_title)) {
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
                            Modifier.size(40.dp).clip(CircleShape).background(if (isActive) accent.color else INACTIVE_DISC),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(p.name.take(1).uppercase(), style = MaterialTheme.typography.titleMedium, color = Palette.background)
                        }
                        Column(Modifier.padding(start = 14.dp).weight(1f)) {
                            Text(p.name, style = MaterialTheme.typography.titleMedium)
                            Muted(playlistDetails(p))
                            expiry(p.expiresAt)?.let {
                                Text(
                                    expiryText(it),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = if (it.soon) Palette.warning else Palette.textMuted
                                )
                            }
                        }
                        if (isActive) {
                            Text(
                                stringResource(R.string.settings_playlist_in_use),
                                style = MaterialTheme.typography.labelMedium,
                                color = accent.color
                            )
                        }
                    }
                }
                PillButton(stringResource(R.string.settings_playlist_update_now), { vm.update(p) })
                // A Plex server has nothing to type in (its address and token come from the account): only removing.
                if (p.isPlex) {
                    PillButton(stringResource(R.string.common_remove), { vm.remove(p) { any -> if (!any) nav.reset(Screen.Welcome) } })
                } else {
                    PillButton(stringResource(R.string.settings_playlist_edit), { nav.push(Screen.PlaylistEdit(p.id)) })
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PillButton(
                    stringResource(R.string.settings_playlist_add_plex),
                    { nav.push(Screen.PlexSignIn()) },
                    if (playlists.isEmpty()) rememberFirstFocus() else Modifier,
                    primary = playlists.isEmpty()
                )
                PillButton(stringResource(R.string.settings_playlist_add), { nav.push(Screen.PlaylistEdit(null)) })
            }
        }
        item { Muted(stringResource(R.string.settings_playlist_select_hint)) }
    }
}

/** How near a provider's expiry date is: past, today, within [EXPIRY_WARNING_DAYS] days, or later. */
internal enum class ExpiryKind { EXPIRED, TODAY, SOON, LATER }

/** A provider's expiry time ([atMs]), [days] away in calendar days. */
internal data class Expiry(val kind: ExpiryKind, val atMs: Long, val days: Int) {
    /** Close or past: shown as a warning. */
    val soon: Boolean get() = kind != ExpiryKind.LATER
}

/**
 * The provider's expiry date (seconds), and how near it is. Null when there's none: Plex servers, and providers
 * that don't say.
 */
internal fun expiry(expiresAtSec: Long?, now: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()): Expiry? {
    val at = expiresAtSec?.takeIf { it > 0 }?.times(1000) ?: return null

    // Calendar days, as the date shown: tomorrow is "in 1 day" at any time of day.
    fun day(ms: Long) = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()
    val days = ChronoUnit.DAYS.between(day(now), day(at)).toInt()
    val kind = when {
        at <= now -> ExpiryKind.EXPIRED
        days < 1 -> ExpiryKind.TODAY
        days <= EXPIRY_WARNING_DAYS -> ExpiryKind.SOON
        else -> ExpiryKind.LATER
    }
    return Expiry(kind, at, days)
}

private const val EXPIRY_WARNING_DAYS = 14

/** "Expires 12 Mar 2027", "Expires 12 Mar 2027, in 5 days" and so on. */
@Composable
private fun expiryText(e: Expiry): String {
    val date = formatDate(e.atMs)
    return when (e.kind) {
        ExpiryKind.EXPIRED -> stringResource(R.string.settings_playlist_expired, date)
        ExpiryKind.TODAY -> stringResource(R.string.settings_playlist_expires_today, date)
        ExpiryKind.SOON -> pluralStringResource(R.plurals.settings_playlist_expires_in, e.days, date, e.days)
        ExpiryKind.LATER -> stringResource(R.string.settings_playlist_expires, date)
    }
}

private fun playlistDetails(p: PlaylistEntity): String {
    val parts = mutableListOf(if (p.isPlex) "Plex" else "Xtream")
    if (p.lastSyncAt > 0) parts += appString(R.string.common_updated_at, formatDateTime(p.lastSyncAt))
    return parts.joinToString(" · ")
}

/** A line under the playlist form: [ok] (it worked) shows it in the accent colour, else as a warning. */
data class FormMessage(val text: String, val ok: Boolean = false)

class PlaylistEditViewModel(private val c: AppContainer, private val id: Long?) : ViewModel() {
    var name by mutableStateOf("")
    var server by mutableStateOf("")
    var username by mutableStateOf("")
    var password by mutableStateOf("")

    var busy by mutableStateOf(false)
        private set
    var message by mutableStateOf<FormMessage?>(null)
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
                message = FormMessage(e.message ?: appString(R.string.common_something_went_wrong))
            } finally {
                busy = false
            }
        }
    }

    fun test() = run {
        c.playlists.testLogin(server, username, password)
        message = FormMessage(appString(R.string.settings_playlist_connected), ok = true)
    }

    /** Saves; [onSaved] gets the playlist id. Changing login details re-downloads the channels. */
    fun save(onSaved: (Long) -> Unit) = run {
        val old = original
        if (old == null) {
            message = FormMessage(appString(R.string.settings_playlist_checking))
            val newId = c.playlists.add(name, server, username, password)
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
                password = password
            )
            c.playlists.update(updated)
            if (updated.server != old.server || updated.username != old.username || updated.password != old.password) {
                c.sync.refresh(old.id)
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
            stringResource(
                when {
                    firstRun -> R.string.settings_welcome_title
                    id == null -> R.string.settings_playlist_add_title
                    else -> R.string.settings_playlist_edit_title
                }
            ),
            style = MaterialTheme.typography.headlineLarge
        )
        if (firstRun) Muted(stringResource(R.string.settings_playlist_first_run_hint))
        // The keyboard's Next key moves straight on to the next box, ready for typing.
        val nameEdit = rememberSaveable { mutableStateOf(false) }
        val serverEdit = rememberSaveable { mutableStateOf(false) }
        val userEdit = rememberSaveable { mutableStateOf(false) }
        val passwordEdit = rememberSaveable { mutableStateOf(false) }
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Field(stringResource(R.string.settings_playlist_name), Modifier.weight(1f)) {
                TvTextField(
                    vm.name,
                    { vm.name = it },
                    stringResource(R.string.settings_playlist_name_hint),
                    rememberFirstFocus().fillMaxWidth(),
                    editing = nameEdit,
                    onNext = { serverEdit.value = true }
                )
            }
            Field(stringResource(R.string.settings_playlist_server), Modifier.weight(1f)) {
                TvTextField(
                    vm.server,
                    { vm.server = it },
                    "http://example.com:8080",
                    Modifier.fillMaxWidth(),
                    keyboardType = KeyboardType.Uri,
                    editing = serverEdit,
                    onNext = { userEdit.value = true }
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Field(stringResource(R.string.settings_playlist_username), Modifier.weight(1f)) {
                TvTextField(
                    vm.username,
                    { vm.username = it },
                    stringResource(R.string.settings_playlist_username),
                    Modifier.fillMaxWidth(),
                    autoCorrect = false,
                    editing = userEdit,
                    onNext = { passwordEdit.value = true }
                )
            }
            Field(stringResource(R.string.settings_playlist_password), Modifier.weight(1f)) {
                TvTextField(
                    vm.password,
                    { vm.password = it },
                    stringResource(R.string.settings_playlist_password),
                    Modifier.fillMaxWidth(),
                    autoCorrect = false,
                    editing = passwordEdit
                )
            }
        }
        vm.message?.let {
            Text(it.text, style = MaterialTheme.typography.bodyLarge, color = if (it.ok) LocalAccent.current.color else Palette.warning)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            PillButton(
                stringResource(
                    if (vm.busy) {
                        R.string.settings_playlist_wait
                    } else if (firstRun) {
                        R.string.common_continue
                    } else {
                        R.string.settings_playlist_save
                    }
                ),
                {
                    vm.save { pid ->
                        if (id == null) nav.replace(Screen.Categories(pid, firstRun = true)) else nav.pop()
                    }
                },
                primary = true
            )
            PillButton(stringResource(R.string.settings_playlist_test), vm::test)
            if (id != null) {
                Box(Modifier.width(40.dp))
                PillButton(stringResource(R.string.settings_playlist_delete), {
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

/** An inactive playlist's initial: a mid grey, darker than the text, so the active one's accent stands out. */
private val INACTIVE_DISC = Color(0xFF868E9A)
