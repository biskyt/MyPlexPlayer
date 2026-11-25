package com.biskyt.myplexplayer.ui.settings

import android.content.pm.PackageManager
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.biskyt.myplexplayer.AppContainer
import com.biskyt.myplexplayer.AppText
import com.biskyt.myplexplayer.BuildConfig
import com.biskyt.myplexplayer.R
import com.biskyt.myplexplayer.container
import com.biskyt.myplexplayer.core.player.SubtitleMode
import com.biskyt.myplexplayer.core.ui.AccentPalette
import com.biskyt.myplexplayer.core.ui.BackdropLevel
import com.biskyt.myplexplayer.data.BufferSize
import com.biskyt.myplexplayer.data.LayoutMode
import com.biskyt.myplexplayer.data.Settings
import com.biskyt.myplexplayer.data.accentName
import com.biskyt.myplexplayer.launcher.TvLauncher
import com.biskyt.myplexplayer.player.Ac3Encoder
import com.biskyt.myplexplayer.ui.LocalNavigator
import com.biskyt.myplexplayer.ui.Screen
import com.biskyt.myplexplayer.ui.appViewModel
import com.biskyt.myplexplayer.ui.common.CycleSettingRow
import com.biskyt.myplexplayer.ui.common.Muted
import com.biskyt.myplexplayer.ui.common.SettingRow
import com.biskyt.myplexplayer.ui.common.Toggle
import com.biskyt.myplexplayer.ui.common.TvCard
import com.biskyt.myplexplayer.ui.common.cycle
import com.biskyt.myplexplayer.ui.common.displayLanguage
import com.biskyt.myplexplayer.ui.common.nativeLanguageName
import com.biskyt.myplexplayer.ui.common.rememberFirstFocus
import com.biskyt.myplexplayer.ui.theme.findsTv
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsViewModel(private val c: AppContainer) : ViewModel() {
    val settings = c.settings.settings.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    fun update(transform: (Settings) -> Settings) = viewModelScope.launch { c.settings.update(transform) }
}

/** Title + scrollable column used by every settings page. */
@Composable
fun SettingsPage(title: String, content: LazyListScope.() -> Unit) {
    Column(Modifier.fillMaxSize().padding(horizontal = 96.dp, vertical = 40.dp)) {
        Text(title, style = MaterialTheme.typography.headlineLarge, modifier = Modifier.padding(bottom = 20.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 40.dp), content = content)
    }
}

@Composable
fun SettingsScreen() {
    val nav = LocalNavigator.current
    val pid = LocalContext.current.container.settings.settings.collectAsState(initial = null).value?.activePlaylistId ?: 0
    AskToShowLauncherRows()
    SettingsPage(stringResource(R.string.common_settings)) {
        item {
            SettingRow(
                stringResource(R.string.settings_playlists_title),
                stringResource(R.string.settings_playlists_help),
                { nav.push(Screen.Playlists) },
                rememberFirstFocus(once = true)
            )
        }
        item { LanguageRow() }
        item {
            SettingRow(stringResource(R.string.settings_playback_title), stringResource(R.string.settings_playback_help), {
                nav.push(Screen.PlaybackSettings)
            })
        }
        item {
            SettingRow(stringResource(R.string.settings_categories_title), stringResource(R.string.settings_categories_help), {
                nav.push(Screen.Categories(pid))
            })
        }
        item {
            SettingRow(stringResource(R.string.settings_appearance_title), stringResource(R.string.settings_appearance_help), {
                nav.push(Screen.Appearance)
            })
        }
        item {
            SettingRow(stringResource(R.string.settings_misc_title), stringResource(R.string.settings_misc_help), {
                nav.push(Screen.MiscSettings)
            })
        }
        item { TmdbRow() }
        item {
            SettingRow(stringResource(R.string.settings_backup_title), stringResource(R.string.settings_backup_help), {
                nav.push(Screen.Backup)
            })
        }
        item {
            val help = stringResource(R.string.settings_about_help, BuildConfig.VERSION_NAME)
            SettingRow(stringResource(R.string.settings_about_title), help, { nav.push(Screen.About) })
        }
        item { SupportSection() }
    }
}

/** Whether the app runs on a TV: only there can its rows show on the home screen. */
@Composable
private fun onTv(): Boolean {
    val context = LocalContext.current
    return remember { context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK) }
}

/**
 * Asks the TV to show each of the app's rows on its home screen, the first time it is made (the TV can ask the user).
 * Both the main settings list and the Misc page do this, because a row can be made after the user leaves Misc.
 */
@Composable
private fun AskToShowLauncherRows() {
    if (!onTv()) return
    val context = LocalContext.current
    val activity = LocalActivity.current
    LaunchedEffect(activity) {
        activity ?: return@LaunchedEffect // (kept for later: no activity to ask from)
        val made = context.container.launcherSync.madeChannels
        made.collect { ids ->
            if (ids.isEmpty()) return@collect
            TvLauncher.askToShow(activity, ids)
            made.update { it - ids } // (after asking: kept if Settings closes first; not those made meanwhile)
        }
    }
}

/** "Show on the TV's home screen" (see LauncherSync), off by default: only on a TV. */
@Composable
private fun LauncherRow(modifier: Modifier = Modifier) {
    if (!onTv()) return
    val vm = appViewModel { SettingsViewModel(it) }
    val s = vm.settings.collectAsState().value ?: return
    SettingRow(stringResource(R.string.settings_launcher), stringResource(R.string.settings_launcher_help), {
        vm.update { it.copy(launcherRows = !it.launcherRows) }
    }, modifier) { Toggle(s.launcherRows) }
}

/**
 * "Warn if VPN is not connected" (see VpnMonitor): shown in the top bar. While it is on, also "Show your external IP
 * address" (see ExternalIp), next to it.
 */
@Composable
private fun VpnWarningRows(modifier: Modifier = Modifier) {
    val vm = appViewModel { SettingsViewModel(it) }
    val s = vm.settings.collectAsState().value ?: return
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SettingRow(stringResource(R.string.settings_warn_vpn), stringResource(R.string.settings_warn_vpn_help), {
            vm.update { it.copy(warnVpn = !it.warnVpn) }
        }, modifier) { Toggle(s.warnVpn) }
        if (s.warnVpn) {
            SettingRow(stringResource(R.string.settings_external_ip), stringResource(R.string.settings_external_ip_help), {
                vm.update { it.copy(showExternalIp = !it.showExternalIp) }
            }) { Toggle(s.showExternalIp) }
        }
    }
}

@Composable
fun PlaybackSettingsScreen() {
    val vm = appViewModel { SettingsViewModel(it) }
    val s = vm.settings.collectAsState().value ?: return
    val skipSteps = listOf(5, 10, 15, 20, 30, 45, 60, 90, 120)
    // The Dolby Digital encoder's library is loaded the first time: not on the main thread. The page waits for it, so
    // that its rows do not move under the focus.
    val dolby = produceState<Boolean?>(null) { value = withContext(Dispatchers.IO) { Ac3Encoder.available } }.value ?: return
    SettingsPage(stringResource(R.string.settings_playback_title)) {
        item {
            CycleSettingRow(
                stringResource(R.string.common_skip_back),
                stringResource(R.string.settings_skip_back_help),
                stringResource(R.string.settings_seconds, s.skipBackSec),
                { d -> vm.update { it.copy(skipBackSec = skipSteps.cycle(it.skipBackSec, d)) } },
                rememberFirstFocus(once = true)
            )
        }
        item {
            CycleSettingRow(
                stringResource(R.string.common_skip_forward),
                stringResource(R.string.settings_skip_forward_help),
                stringResource(R.string.settings_seconds, s.skipForwardSec),
                { d -> vm.update { it.copy(skipForwardSec = skipSteps.cycle(it.skipForwardSec, d)) } }
            )
        }
        item {
            AutoSkipRow(R.string.settings_auto_skip_intro, R.string.settings_auto_skip_intro_help, s.autoSkipIntroSec) { d ->
                vm.update { it.copy(autoSkipIntroSec = autoSkipSteps.cycle(it.autoSkipIntroSec, d)) }
            }
        }
        item {
            AutoSkipRow(R.string.settings_auto_skip_credits, R.string.settings_auto_skip_credits_help, s.autoSkipCreditsSec) { d ->
                vm.update { it.copy(autoSkipCreditsSec = autoSkipSteps.cycle(it.autoSkipCreditsSec, d)) }
            }
        }
        item {
            SettingRow(
                stringResource(R.string.settings_passthrough),
                stringResource(R.string.settings_passthrough_help),
                { vm.update { it.copy(audioPassthrough = !it.audioPassthrough) } }
            ) { Toggle(s.audioPassthrough) }
        }
        if (s.audioPassthrough && dolby) {
            item {
                SettingRow(
                    stringResource(R.string.settings_surround_dolby),
                    stringResource(R.string.settings_surround_dolby_help),
                    { vm.update { it.copy(surroundAsDolby = !it.surroundAsDolby) } }
                ) { Toggle(s.surroundAsDolby) }
            }
        }
        item {
            val mode = when (s.subtitleMode) {
                SubtitleMode.OFF -> R.string.common_subtitles_off
                SubtitleMode.FORCED_ONLY -> R.string.settings_subtitles_forced_only
                SubtitleMode.ALWAYS -> R.string.settings_subtitles_always
            }
            CycleSettingRow(
                stringResource(R.string.settings_subtitles),
                stringResource(R.string.settings_subtitles_help),
                stringResource(mode),
                { d -> vm.update { it.copy(subtitleMode = SubtitleMode.entries.cycle(it.subtitleMode, d)) } }
            )
        }
        item {
            val languages = listOf("", "en", "fr", "de", "es", "it", "pt", "nl", "pl", "tr", "ar")
            val label = if (s.subtitleLanguage.isEmpty()) {
                stringResource(R.string.settings_language_system, displayLanguage(Locale.getDefault().language))
            } else {
                displayLanguage(s.subtitleLanguage)
            }
            CycleSettingRow(stringResource(R.string.settings_subtitle_language), null, label, { d ->
                vm.update { it.copy(subtitleLanguage = languages.cycle(it.subtitleLanguage, d)) }
            })
        }
        item {
            val label = when (s.bufferSize) {
                BufferSize.SMALL -> R.string.settings_buffer_small
                BufferSize.NORMAL -> R.string.settings_buffer_normal
                BufferSize.LARGE -> R.string.settings_buffer_large
            }
            CycleSettingRow(
                stringResource(R.string.settings_buffer),
                stringResource(R.string.settings_buffer_help),
                stringResource(label),
                { d -> vm.update { it.copy(bufferSize = BufferSize.entries.cycle(it.bufferSize, d)) } }
            )
        }
        item {
            SettingRow(stringResource(R.string.settings_start_last), stringResource(R.string.settings_start_last_help), {
                vm.update { it.copy(startOnLastChannel = !it.startOnLastChannel) }
            }) {
                Toggle(s.startOnLastChannel)
            }
        }
        item {
            SettingRow(stringResource(R.string.settings_zap_up_down), stringResource(R.string.settings_zap_up_down_help), {
                vm.update { it.copy(zapWithUpDown = !it.zapWithUpDown) }
            }) {
                Toggle(s.zapWithUpDown)
            }
        }
    }
}

/** Misc settings (MyTVPlayer 1.35.3): the TV's home screen and the VPN warning. */
@Composable
fun MiscSettingsScreen() {
    // The settings first: the rows read them too, and the first one must be there when it asks for the focus.
    appViewModel { SettingsViewModel(it) }.settings.collectAsState().value ?: return
    AskToShowLauncherRows()
    val tv = onTv()
    SettingsPage(stringResource(R.string.settings_misc_title)) {
        // The first row takes the focus: on a phone, the home screen row is not there.
        if (tv) item { LauncherRow(rememberFirstFocus(once = true)) }
        item { VpnWarningRows(if (tv) Modifier else rememberFirstFocus(once = true)) }
        item { LayoutRow() }
    }
}

/**
 * The TV's or the phone's screens, whatever the device (the owner, 2026-11-25): Automatic finds them. A TV has no
 * touch layout to choose: with a remote, its screens could not be used to set this back.
 */
@Composable
private fun LayoutRow() {
    val vm = appViewModel { SettingsViewModel(it) }
    val s = vm.settings.collectAsState().value ?: return
    val context = LocalContext.current
    val tv = remember { context.findsTv() }
    val choices = if (tv) listOf(LayoutMode.AUTO, LayoutMode.TV) else LayoutMode.entries
    val label = when (s.layout) {
        LayoutMode.AUTO -> R.string.settings_layout_auto
        LayoutMode.TV -> R.string.settings_layout_tv
        LayoutMode.TOUCH -> R.string.settings_layout_touch
    }
    CycleSettingRow(
        stringResource(R.string.settings_layout),
        stringResource(if (tv) R.string.settings_layout_help_tv else R.string.settings_layout_help),
        stringResource(label),
        { d -> vm.update { it.copy(layout = choices.cycle(it.layout, d)) } }
    )
}

/** Off, then 0 to 10 seconds. */
private val autoSkipSteps = listOf(Settings.AUTO_SKIP_OFF) + (0..10)

/** An automatic skip's delay: Off, or the seconds its pill counts down. */
@Composable
private fun AutoSkipRow(title: Int, help: Int, sec: Int, step: (Int) -> Unit) {
    val label = when (sec) {
        Settings.AUTO_SKIP_OFF -> stringResource(R.string.settings_auto_skip_off)
        else -> stringResource(R.string.settings_seconds, sec)
    }
    CycleSettingRow(stringResource(title), stringResource(help), label, step)
}

/** The app's language: each press moves to the next, and the screens switch to it straight away. */
@Composable
private fun LanguageRow() {
    val context = LocalContext.current
    val chosen by AppText.chosen.collectAsState()
    val label = if (chosen.isEmpty()) {
        stringResource(R.string.settings_language_system, nativeLanguageName(AppText.locale.toLanguageTag(), withCountry = false))
    } else {
        nativeLanguageName(chosen)
    }
    CycleSettingRow(stringResource(R.string.settings_language), null, label, { d ->
        AppText.setLanguage(context, (listOf("") + AppText.languages).cycle(chosen, d))
    })
}

@Composable
fun AppearanceScreen() {
    val vm = appViewModel { SettingsViewModel(it) }
    val s = vm.settings.collectAsState().value ?: return
    // It scrolls: the rows under the colours can be below the screen's edge.
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 96.dp, vertical = 40.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(stringResource(R.string.settings_appearance_title), style = MaterialTheme.typography.headlineLarge)
        Text(stringResource(R.string.settings_accent), style = MaterialTheme.typography.titleLarge)
        Muted(stringResource(R.string.settings_accent_help), maxLines = 2)
        LazyVerticalGrid(
            GridCells.Fixed(4),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.height(140.dp)
        ) {
            items(AccentPalette.presets) { (id, argb) ->
                val selected = s.accent == argb
                TvCard(
                    onClick = { vm.update { it.copy(accent = argb) } },
                    selected = selected,
                    modifier = if (argb ==
                        AccentPalette.presets.first().argb
                    ) {
                        rememberFirstFocus(once = true)
                    } else {
                        Modifier
                    }
                ) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(30.dp).clip(CircleShape).background(Color(argb)))
                        Text(
                            stringResource(accentName(id)),
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.padding(start = 10.dp)
                        )
                    }
                }
            }
        }
        SettingRow(stringResource(R.string.settings_large_text), stringResource(R.string.settings_large_text_help), {
            vm.update { it.copy(largeText = !it.largeText) }
        }) { Toggle(s.largeText) }
        // How bright the browsing screens' background is (see AmbientBackground), as MyTVLauncher's setting.
        val level = s.backdropLevel
        CycleSettingRow(
            stringResource(R.string.settings_backdrop),
            stringResource(R.string.settings_backdrop_help),
            if (level == 0) stringResource(R.string.settings_backdrop_off) else stringResource(R.string.settings_backdrop_value, level),
            // Round from the last to the first, as the other settings: OK and a tap only step forward.
            { d -> vm.update { it.copy(backdropLevel = BackdropLevel.CHOICES.cycle(BackdropLevel.valid(it.backdropLevel), d)) } }
        )
    }
}

@Composable
fun AboutScreen() {
    val context = LocalContext.current
    val storage = remember {
        fun size(f: java.io.File?): Long = f?.walkTopDown()?.sumOf { it.length() } ?: 0
        size(context.getDatabasePath("myplexplayer.db").parentFile) to size(context.cacheDir.resolve("logos"))
    }
    fun mb(bytes: Long) = android.text.format.Formatter.formatShortFileSize(context, bytes)
    val nav = LocalNavigator.current
    SettingsPage(stringResource(R.string.settings_about_title)) {
        item { Text("MyPlexPlayer ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.titleLarge) }
        item { Muted(stringResource(R.string.settings_about_licence), maxLines = 2) }
        item {
            SettingRow(
                stringResource(R.string.settings_licences_title),
                stringResource(R.string.settings_licences_help),
                { nav.push(Screen.Licences) },
                rememberFirstFocus(once = true)
            )
        }
        item {
            Column(Modifier.width(700.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    stringResource(R.string.settings_storage),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 16.dp)
                )
                Muted(stringResource(R.string.settings_storage_database, mb(storage.first)))
                Muted(stringResource(R.string.settings_storage_logos, mb(storage.second)))
                Muted(stringResource(R.string.settings_storage_help), maxLines = 3)
            }
        }
        item { SupportSection() }
    }
}
