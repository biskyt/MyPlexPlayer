package com.biskyt.mytvplayer.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.biskyt.mytvplayer.container
import com.biskyt.mytvplayer.data.ChannelFilter
import com.biskyt.mytvplayer.data.SyncState
import com.biskyt.mytvplayer.ui.LocalNavigator
import com.biskyt.mytvplayer.ui.Screen
import com.biskyt.mytvplayer.ui.theme.LocalAccent
import com.biskyt.mytvplayer.ui.theme.Palette
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class Tab(val label: String) { HOME("Home"), LIVE("Live TV"), MOVIES("Movies"), SERIES("Series"), FAVOURITES("Favourites") }

/** Top bar from the agreed design: logo, tabs, clock, search, settings and the playlist switcher. */
@Composable
fun TopBar(selected: Tab?) {
    val nav = LocalNavigator.current
    val accent = LocalAccent.current
    val container = LocalContext.current.container
    val settings by container.settings.settings.collectAsState(initial = null)
    val playlists by container.playlists.observePlaylists().collectAsState(initial = emptyList())
    val active = playlists.firstOrNull { it.id == settings?.activePlaylistId } ?: playlists.firstOrNull()
    Row(
        Modifier.padding(start = 56.dp, end = 56.dp, top = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(Modifier.padding(end = 28.dp)) {
            Text("My", style = MaterialTheme.typography.titleLarge)
            Text("TV", style = MaterialTheme.typography.titleLarge, color = accent.color)
            Text("Player", style = MaterialTheme.typography.titleLarge)
        }
        Tab.entries.forEach { tab ->
            PillButton(
                text = tab.label,
                selected = tab == selected,
                onClick = {
                    nav.reset(
                        when (tab) {
                            Tab.HOME -> Screen.Home
                            Tab.LIVE -> Screen.Live()
                            Tab.MOVIES -> Screen.Movies
                            Tab.SERIES -> Screen.Series
                            Tab.FAVOURITES -> Screen.Live(ChannelFilter.Favourites)
                        },
                    )
                },
            )
        }
        Box(Modifier.weight(1f))
        SyncStatus()
        Clock()
        IconPill(AppIcons.Search, "Search") { nav.push(Screen.Search) }
        IconPill(AppIcons.Settings, "Settings") { nav.push(Screen.Settings) }
        if (active != null) {
            TvCard(onClick = { nav.push(Screen.Playlists) }, shape = RoundedCornerShape(50)) {
                Row(Modifier.padding(start = 6.dp, end = 16.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(28.dp).clip(CircleShape).background(accent.color), contentAlignment = Alignment.Center) {
                        Text(active.name.take(1).uppercase(), style = MaterialTheme.typography.labelMedium, color = accent.onAccent)
                    }
                    Text(active.name, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(start = 8.dp), maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun IconPill(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    TvCard(onClick = onClick, shape = CircleShape, color = Palette.background) {
        Icon(icon, contentDescription = label, tint = Palette.textMuted, modifier = Modifier.padding(10.dp).size(22.dp))
    }
}

@Composable
private fun Clock() {
    val time by produceState(initialValue = now()) {
        while (true) {
            delay(60_000 - System.currentTimeMillis() % 60_000)
            value = now()
        }
    }
    Text(time, style = MaterialTheme.typography.titleSmall, color = Palette.textMuted, modifier = Modifier.padding(horizontal = 8.dp))
}

private fun now(): String = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())

@Composable
private fun SyncStatus() {
    val state by LocalContext.current.container.sync.state.collectAsState()
    val text = when (val s = state) {
        SyncState.Idle -> null
        is SyncState.Channels -> if (s.loaded == 0) "Updating channels…" else "Loading channels… ${"%,d".format(s.loaded)}"
        is SyncState.Guide -> s.message
        is SyncState.Failed -> s.message
    }
    if (text != null) Muted(text, Modifier.padding(end = 8.dp))
}

/** Screen with the top bar above [content]. */
@Composable
fun TabScaffold(tab: Tab?, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize().background(Palette.background)) {
        TopBar(tab)
        Column(Modifier.padding(top = 20.dp).fillMaxSize(), content = content)
    }
}

/**
 * Text entry for a remote: a normal focusable card that opens the on-screen keyboard only when OK is pressed,
 * so moving through a screen never pops the keyboard up.
 */
@Composable
fun TvTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    password: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
    leading: (@Composable () -> Unit)? = null,
    onDone: () -> Unit = {},
) {
    var editing by rememberSaveable { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    TvCard(onClick = { editing = true }, modifier = modifier, color = Palette.surface) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (leading != null) {
                leading()
                Box(Modifier.size(10.dp))
            }
            if (editing) {
                var hadFocus by remember { mutableStateOf(false) }
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge,
                    cursorBrush = SolidColor(LocalAccent.current.color),
                    visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
                    keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { editing = false; onDone() }),
                    modifier = Modifier.weight(1f).focusRequester(focus).onFocusChanged {
                        // Leaving the field (e.g. pressing Back or moving away) ends editing.
                        if (it.isFocused) hadFocus = true else if (hadFocus) editing = false
                    },
                )
                LaunchedEffect(Unit) { focus.requestFocus() }
            } else {
                val shown = when {
                    value.isEmpty() -> placeholder
                    password -> "•".repeat(value.length.coerceAtMost(12))
                    else -> value
                }
                Text(shown, style = MaterialTheme.typography.bodyLarge, color = if (value.isEmpty()) Palette.textDim else Palette.text, maxLines = 1)
            }
        }
    }
}
