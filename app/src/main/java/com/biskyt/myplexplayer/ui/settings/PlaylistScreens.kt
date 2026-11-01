package com.biskyt.myplexplayer.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.biskyt.myplexplayer.AppContainer
import com.biskyt.myplexplayer.R
import com.biskyt.myplexplayer.appString
import com.biskyt.myplexplayer.data.GuideError
import com.biskyt.myplexplayer.data.db.PlaylistEntity
import com.biskyt.myplexplayer.ui.LocalNavigator
import com.biskyt.myplexplayer.ui.Screen
import com.biskyt.myplexplayer.ui.appViewModel
import com.biskyt.myplexplayer.ui.common.Muted
import com.biskyt.myplexplayer.ui.common.PillButton
import com.biskyt.myplexplayer.ui.common.StatusPanel
import com.biskyt.myplexplayer.ui.common.TvCard
import com.biskyt.myplexplayer.ui.common.WorkRunner
import com.biskyt.myplexplayer.ui.common.WorkStatus
import com.biskyt.myplexplayer.ui.common.formatDateTime
import com.biskyt.myplexplayer.ui.common.rememberFirstFocus
import com.biskyt.myplexplayer.ui.theme.LocalAccent
import com.biskyt.myplexplayer.ui.theme.Palette
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class PlaylistsViewModel(private val c: AppContainer) : ViewModel() {
    val state = combine(c.playlists.observePlaylists(), c.settings.settings) { list, s -> list to s.activePlaylistId }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList<PlaylistEntity>() to 0L)

    /** Why each source's TV guide couldn't be loaded at its last update, by source id (saved as what failed). */
    val guideErrors = c.epg.observeGuideErrors().stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

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
            { guideError ->
                if (guideError == null) {
                    WorkStatus(appString(R.string.settings_playlist_updated, p.name), appString(R.string.settings_playlist_updated_detail))
                } else {
                    // The channels updated, but say why the guide didn't.
                    WorkStatus(
                        appString(R.string.settings_playlist_updated_guides_failed, p.name),
                        guideError,
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
    val guideErrors by vm.guideErrors.collectAsState()
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
                            guideErrors[p.id]?.let {
                                Text(
                                    stringResource(R.string.settings_playlist_guide_failed, GuideError.text(it)),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = Palette.warning,
                                    maxLines = 2
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
                // Only the source in use is updated (see SyncManager).
                if (isActive) PillButton(stringResource(R.string.settings_playlist_update_now), { vm.update(p) })
                // A Plex server has nothing to type in (its address and token come from the account): only removing.
                PillButton(stringResource(R.string.common_remove), { vm.remove(p) { any -> if (!any) nav.reset(Screen.Welcome) } })
            }
        }
        item {
            PillButton(
                stringResource(R.string.settings_playlist_add_plex),
                { nav.push(Screen.PlexSignIn()) },
                if (playlists.isEmpty()) rememberFirstFocus() else Modifier,
                primary = playlists.isEmpty()
            )
        }
        item { Muted(stringResource(R.string.settings_playlist_select_hint)) }
    }
}

private fun playlistDetails(p: PlaylistEntity): String {
    val parts = mutableListOf("Plex")
    if (p.lastSyncAt > 0) parts += appString(R.string.common_updated_at, formatDateTime(p.lastSyncAt))
    return parts.joinToString(" · ")
}

/** An inactive playlist's initial: a mid grey, darker than the text, so the active one's accent stands out. */
private val INACTIVE_DISC = Color(0xFF868E9A)
