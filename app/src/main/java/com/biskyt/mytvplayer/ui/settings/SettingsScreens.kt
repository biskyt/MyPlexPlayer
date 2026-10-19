package com.biskyt.mytvplayer.ui.settings

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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.biskyt.mytvplayer.AppContainer
import com.biskyt.mytvplayer.BuildConfig
import com.biskyt.mytvplayer.container
import com.biskyt.mytvplayer.core.player.SubtitleMode
import com.biskyt.mytvplayer.core.ui.AccentPalette
import com.biskyt.mytvplayer.data.BufferSize
import com.biskyt.mytvplayer.data.Settings
import com.biskyt.mytvplayer.data.StreamFormat
import com.biskyt.mytvplayer.ui.LocalNavigator
import com.biskyt.mytvplayer.ui.Screen
import com.biskyt.mytvplayer.ui.appViewModel
import com.biskyt.mytvplayer.ui.common.Muted
import com.biskyt.mytvplayer.ui.common.SettingRow
import com.biskyt.mytvplayer.ui.common.Toggle
import com.biskyt.mytvplayer.ui.common.TvCard
import com.biskyt.mytvplayer.ui.common.ValueChip
import com.biskyt.mytvplayer.ui.common.cycle
import com.biskyt.mytvplayer.ui.common.rememberFirstFocus
import com.biskyt.mytvplayer.ui.theme.Palette
import java.util.Locale
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(private val c: AppContainer) : ViewModel() {
    val settings = c.settings.settings.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    fun update(transform: (Settings) -> Settings) = viewModelScope.launch { c.settings.update(transform) }
}

/** Title + scrollable column used by every settings page. */
@Composable
fun SettingsPage(title: String, content: LazyListScope.() -> Unit) {
    Column(Modifier.fillMaxSize().background(Palette.background).padding(horizontal = 96.dp, vertical = 40.dp)) {
        Text(title, style = MaterialTheme.typography.headlineLarge, modifier = Modifier.padding(bottom = 20.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 40.dp), content = content)
    }
}

@Composable
fun SettingsScreen() {
    val nav = LocalNavigator.current
    val pid = LocalContext.current.container.settings.settings.collectAsState(initial = null).value?.activePlaylistId ?: 0
    SettingsPage("Settings") {
        item { SettingRow("Playlists", "Add, edit or switch IPTV accounts", { nav.push(Screen.Playlists) }, rememberFirstFocus()) }
        item { SettingRow("Playback", "Skip times, audio passthrough, subtitles, buffer", { nav.push(Screen.PlaybackSettings) }) }
        item {
            SettingRow("Languages & categories", "Choose which channels are loaded; unhide channels", { nav.push(Screen.Categories(pid)) })
        }
        item { SettingRow("TV guide sources", "Your provider's guide and extra XMLTV guides", { nav.push(Screen.GuideSources) }) }
        item { SettingRow("TV guide matching", "Pick the guide channel used by each channel", { nav.push(Screen.GuideMatching(pid)) }) }
        item { SettingRow("Appearance", "Accent colour and text size", { nav.push(Screen.Appearance) }) }
        item { SettingRow("Backup & restore", "Save your playlists and choices to a file, or restore them", { nav.push(Screen.Backup) }) }
        item { SettingRow("About", "Version, licences, storage", { nav.push(Screen.About) }) }
    }
}

@Composable
fun PlaybackSettingsScreen() {
    val vm = appViewModel { SettingsViewModel(it) }
    val s = vm.settings.collectAsState().value ?: return
    val skipSteps = listOf(5, 10, 15, 20, 30, 45, 60, 90, 120)
    SettingsPage("Playback") {
        item {
            SettingRow("Skip back", "Left on the remote during playback", {
                vm.update { it.copy(skipBackSec = skipSteps.cycle(it.skipBackSec)) }
            }, rememberFirstFocus()) {
                ValueChip("${s.skipBackSec} s")
            }
        }
        item {
            SettingRow("Skip forward", "Right on the remote during playback", {
                vm.update { it.copy(skipForwardSec = skipSteps.cycle(it.skipForwardSec)) }
            }) {
                ValueChip("${s.skipForwardSec} s")
            }
        }
        item {
            SettingRow(
                "Audio passthrough",
                "Send Dolby / DTS / Atmos to your soundbar or TV to decode. Turn off if you get no sound.",
                { vm.update { it.copy(audioPassthrough = !it.audioPassthrough) } }
            ) { Toggle(s.audioPassthrough) }
        }
        item {
            val mode = when (s.subtitleMode) {
                SubtitleMode.OFF -> "Off"
                SubtitleMode.FORCED_ONLY -> "Forced only"
                SubtitleMode.ALWAYS -> "Always"
            }
            SettingRow(
                "Default subtitles",
                "Forced only shows subtitles just for foreign-language lines",
                { vm.update { it.copy(subtitleMode = SubtitleMode.entries.cycle(it.subtitleMode)) } }
            ) { ValueChip(mode) }
        }
        item {
            val languages = listOf("", "en", "fr", "de", "es", "it", "pt", "nl", "pl", "tr", "ar")
            val label = if (s.subtitleLanguage.isEmpty()) {
                "System (${Locale.getDefault().displayLanguage})"
            } else {
                Locale.forLanguageTag(
                    s.subtitleLanguage
                ).displayLanguage
            }
            SettingRow("Subtitle language", null, {
                vm.update { it.copy(subtitleLanguage = languages.cycle(it.subtitleLanguage)) }
            }) { ValueChip(label) }
        }
        item {
            val label = when (s.bufferSize) {
                BufferSize.SMALL -> "Small"
                BufferSize.NORMAL -> "Normal"
                BufferSize.LARGE -> "Large"
            }
            SettingRow("Stream buffer", "Larger is smoother on weak Wi-Fi but slower to change channel", {
                vm.update { it.copy(bufferSize = BufferSize.entries.cycle(it.bufferSize)) }
            }) {
                ValueChip(label)
            }
        }
        item {
            SettingRow("Stream format", "Try HLS if channels stutter or won't start", {
                vm.update { it.copy(streamFormat = StreamFormat.entries.cycle(it.streamFormat)) }
            }) {
                ValueChip(if (s.streamFormat == StreamFormat.TS) "MPEG-TS" else "HLS")
            }
        }
        item {
            SettingRow("Start on last channel", "Open the app straight into the channel you watched last", {
                vm.update { it.copy(startOnLastChannel = !it.startOnLastChannel) }
            }) {
                Toggle(s.startOnLastChannel)
            }
        }
    }
}

@Composable
fun AppearanceScreen() {
    val vm = appViewModel { SettingsViewModel(it) }
    val s = vm.settings.collectAsState().value ?: return
    Column(
        Modifier.fillMaxSize().background(Palette.background).padding(horizontal = 96.dp, vertical = 40.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("Appearance", style = MaterialTheme.typography.headlineLarge)
        Text("Accent colour", style = MaterialTheme.typography.titleLarge)
        Muted("Used for highlights, progress bars and buttons everywhere. Lighter and darker shades are worked out for you.", maxLines = 2)
        LazyVerticalGrid(
            GridCells.Fixed(4),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.height(140.dp)
        ) {
            items(AccentPalette.presets) { (name, argb) ->
                val selected = s.accent == argb
                TvCard(
                    onClick = { vm.update { it.copy(accent = argb) } },
                    selected = selected,
                    modifier = if (argb ==
                        AccentPalette.presets.first().second
                    ) {
                        rememberFirstFocus()
                    } else {
                        Modifier
                    }
                ) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(30.dp).clip(CircleShape).background(Color(argb)))
                        Text(name, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(start = 10.dp))
                    }
                }
            }
        }
        HueSlider(s.accent) { argb -> vm.update { it.copy(accent = argb) } }
        SettingRow("Large text", "Bigger text is easier to read from the sofa", {
            vm.update { it.copy(largeText = !it.largeText) }
        }) { Toggle(s.largeText) }
    }
}

/** Custom colour: left/right moves around the colour wheel and the whole app updates immediately. */
@Composable
private fun HueSlider(current: Int, onChange: (Int) -> Unit) {
    val hue = remember(current) { hueOf(current) }
    TvCard(
        onClick = {},
        color = Palette.surface,
        modifier = Modifier.fillMaxWidth().onPreviewKeyEvent { e ->
            if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
            when (e.key) {
                Key.DirectionLeft -> {
                    onChange(AccentPalette.fromHue(hue - 10f))
                    true
                }
                Key.DirectionRight -> {
                    onChange(AccentPalette.fromHue(hue + 10f))
                    true
                }
                else -> false
            }
        }
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row {
                Text("Custom colour", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                Muted("◀ ▶ to change")
            }
            Box(
                Modifier.fillMaxWidth().height(16.dp).clip(RoundedCornerShape(8.dp))
                    .background(Brush.horizontalGradient((0..6).map { Color(AccentPalette.fromHue(it * 60f)) }))
            )
        }
    }
}

private fun hueOf(argb: Int): Float {
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(argb, hsv)
    return hsv[0]
}

@Composable
fun AboutScreen() {
    val context = LocalContext.current
    val storage = remember {
        fun size(f: java.io.File?): Long = f?.walkTopDown()?.sumOf { it.length() } ?: 0
        Triple(
            size(context.getDatabasePath("mytvplayer.db").parentFile),
            size(context.filesDir.resolve("epg")),
            size(context.cacheDir.resolve("logos"))
        )
    }
    fun mb(bytes: Long) = android.text.format.Formatter.formatShortFileSize(context, bytes)
    SettingsPage("About") {
        item { Text("MyTVPlayer ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.titleLarge) }
        item { Muted("Free and open source (GPL-3.0). No ads, no tracking. Made by biskyt.", maxLines = 2) }
        item {
            Column(Modifier.width(700.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Storage", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 16.dp))
                Muted("Channels & guide database: ${mb(storage.first)}")
                Muted("Downloaded guide files: ${mb(storage.second)}")
                Muted("Channel logos: ${mb(storage.third)} (limited to 30 MB)")
                Muted(
                    "Storage is managed automatically: old programmes are removed every day, logos are capped, and the database is compacted when needed.",
                    maxLines = 3
                )
            }
        }
        item {
            Muted(
                "Font: Nunito (SIL Open Font License). Playback: AndroidX Media3, FFmpeg (LGPL).",
                maxLines = 2,
                modifier = Modifier.padding(top = 16.dp)
            )
        }
    }
}
