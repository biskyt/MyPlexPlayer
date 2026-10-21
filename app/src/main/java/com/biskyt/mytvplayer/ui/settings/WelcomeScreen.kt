package com.biskyt.mytvplayer.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.biskyt.mytvplayer.AppContainer
import com.biskyt.mytvplayer.ui.LocalNavigator
import com.biskyt.mytvplayer.ui.Screen
import com.biskyt.mytvplayer.ui.appViewModel
import com.biskyt.mytvplayer.ui.common.Muted
import com.biskyt.mytvplayer.ui.common.PillButton
import com.biskyt.mytvplayer.ui.common.rememberFirstFocus
import com.biskyt.mytvplayer.ui.theme.Palette
import kotlinx.coroutines.launch

/** First launch: the free channels, films and series, when the user picks them. */
class WelcomeViewModel(private val c: AppContainer) : ViewModel() {
    var error by mutableStateOf<String?>(null)
        private set
    var working by mutableStateOf(false)
        private set
    var addedId by mutableStateOf<Long?>(null)
        private set

    fun start() {
        if (working) return
        error = null
        working = true
        viewModelScope.launch {
            c.playlists.addFree().onSuccess { addedId = it }.onFailure { error = it.message }
            working = false
        }
    }
}

@Composable
fun WelcomeScreen() {
    val vm = appViewModel { WelcomeViewModel(it) }
    val nav = LocalNavigator.current
    vm.addedId?.let { id ->
        // Next: choose which countries' channels to load (the TV's own country is preselected).
        LaunchedEffect(id) { nav.replace(Screen.Categories(id, firstRun = true)) }
    }
    Column(
        Modifier.fillMaxSize().background(Palette.background).padding(horizontal = 96.dp, vertical = 72.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Text("Welcome to MyTVPlayer", style = MaterialTheme.typography.displaySmall)
        Muted(
            "Add the TV service you subscribe to (an Xtream Codes login or an M3U playlist address), or start with free " +
                "TV: channels streamed free by their broadcasters, and public-domain films and series. You can add the " +
                "other at any time in Settings › Playlists.",
            Modifier.widthIn(max = 820.dp),
            maxLines = 3,
        )
        val error = vm.error
        when {
            vm.working -> Text("Downloading the channel list…", style = MaterialTheme.typography.titleMedium)
            error != null -> Text(error, style = MaterialTheme.typography.titleMedium, color = Palette.warning)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            PillButton(
                "Add my TV provider",
                { nav.replace(Screen.PlaylistEdit(null, firstRun = true)) },
                if (error == null) rememberFirstFocus() else Modifier,
                primary = true,
            )
            // Stays on screen while setting up, so the button pressed keeps focus.
            PillButton(
                when {
                    vm.working -> "Setting up…"
                    error != null -> "Try the free channels again"
                    else -> "Free TV, films and series"
                },
                vm::start,
                if (error != null) rememberFirstFocus(error) else Modifier,
            )
            // After reinstalling: everything back from a backup file.
            PillButton("Restore a backup", { nav.push(Screen.Backup) })
        }
    }
}
