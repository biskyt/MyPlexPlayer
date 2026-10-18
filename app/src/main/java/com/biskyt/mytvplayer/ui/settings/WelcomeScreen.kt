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
import com.biskyt.mytvplayer.data.PlaylistRepository
import com.biskyt.mytvplayer.ui.LocalNavigator
import com.biskyt.mytvplayer.ui.Screen
import com.biskyt.mytvplayer.ui.appViewModel
import com.biskyt.mytvplayer.ui.common.Muted
import com.biskyt.mytvplayer.ui.common.PillButton
import com.biskyt.mytvplayer.ui.common.rememberFirstFocus
import com.biskyt.mytvplayer.ui.theme.Palette
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** First launch: sets up the free iptv-org channels so there is something to watch straight away. */
class WelcomeViewModel(private val c: AppContainer, auto: Boolean) : ViewModel() {
    var error by mutableStateOf<String?>(null)
        private set
    var working by mutableStateOf(false)
        private set
    var addedId by mutableStateOf<Long?>(null)
        private set

    init {
        if (auto) start()
    }

    fun start() {
        if (working) return
        error = null
        working = true
        viewModelScope.launch {
            try {
                addedId = c.playlists.addM3u(PlaylistRepository.FREE_PLAYLIST_NAME, PlaylistRepository.FREE_PLAYLIST_URL)
            } catch (e: CancellationException) {
                throw e
            } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                // Any failure (network, full storage…) must leave a way forward rather than crash on first launch.
                error = (e as? IOException)?.message ?: "Couldn't set up the free channels"
            } finally {
                working = false
            }
        }
    }
}

@Composable
fun WelcomeScreen(auto: Boolean) {
    val vm = appViewModel { WelcomeViewModel(it, auto) }
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
            "MyTVPlayer can set up free TV channels from iptv-org, a public collection of freely available channels. " +
                "You can add your own TV provider at any time in Settings › Playlists.",
            Modifier.widthIn(max = 820.dp),
            maxLines = 3,
        )
        val error = vm.error
        when {
            vm.working -> Text("Downloading the channel list…", style = MaterialTheme.typography.titleMedium)
            error != null -> Text(error, style = MaterialTheme.typography.titleMedium, color = Palette.warning)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (!vm.working) {
                PillButton(
                    if (error != null) "Try again" else "Set up the free channels",
                    vm::start,
                    rememberFirstFocus(error),
                    primary = true,
                )
            }
            PillButton(
                "Use my own provider instead",
                { nav.replace(Screen.PlaylistEdit(null, firstRun = true)) },
                if (vm.working) rememberFirstFocus() else Modifier,
            )
        }
    }
}
