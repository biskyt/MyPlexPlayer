package com.biskyt.myplexplayer.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.biskyt.myplexplayer.AppContainer
import com.biskyt.myplexplayer.R
import com.biskyt.myplexplayer.appString
import com.biskyt.myplexplayer.core.plex.PlexException
import com.biskyt.myplexplayer.data.PlexSources
import com.biskyt.myplexplayer.data.describe
import com.biskyt.myplexplayer.ui.LocalNavigator
import com.biskyt.myplexplayer.ui.Screen
import com.biskyt.myplexplayer.ui.appViewModel
import com.biskyt.myplexplayer.ui.common.Muted
import com.biskyt.myplexplayer.ui.common.PillButton
import com.biskyt.myplexplayer.ui.common.QrCode
import com.biskyt.myplexplayer.ui.common.openLink
import com.biskyt.myplexplayer.ui.common.rememberFirstFocus
import com.biskyt.myplexplayer.ui.theme.Palette
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Signs in to Plex with a code the user enters at plex.tv/link (on a phone or computer: typing a password with a
 * remote is no fun), then adds the account's servers. Already signed in (from Settings › Playlists), it only looks
 * for servers again.
 */
class PlexSignInViewModel(private val c: AppContainer) : ViewModel() {
    sealed interface State {
        /** Asking plex.tv for a code, or for the account's servers. */
        data object Starting : State

        /** Waiting for [code] to be entered. */
        data class Code(val code: String) : State

        data object Finding : State

        data class Failed(val message: String) : State

        /** Servers were added: the screen moves on, once it has said which (if any) didn't answer. */
        data class Done(val unreachable: List<String>) : State
    }

    var state by mutableStateOf<State>(State.Starting)
        private set

    private var job: Job? = null

    /** Signs in (or looks for servers again); [stop]ped when the screen goes, so plex.tv isn't asked meanwhile. */
    fun start() {
        if (job?.isActive == true || state is State.Done) return
        state = State.Starting
        job = viewModelScope.launch {
            state = try {
                val added = c.settings.plexToken()?.let { withToken(it) } ?: run {
                    val token = waitForToken() ?: return@launch run { state = State.Failed(appString(R.string.settings_plex_code_expired)) }
                    state = State.Finding
                    c.plex.signIn(token)
                }
                when {
                    added.ids.isNotEmpty() -> {
                        // Their films and series load now, one server after another.
                        added.ids.forEach { c.sync.refresh(it) }
                        State.Done(added.unreachable)
                    }
                    added.unreachable.isNotEmpty() -> State.Failed(
                        appString(R.string.settings_plex_none_answered) + "\n" +
                            appString(R.string.settings_plex_not_reached, added.unreachable.joinToString())
                    )
                    else -> State.Failed(appString(R.string.settings_plex_no_servers))
                }
            } catch (e: PlexException) {
                State.Failed(describe(e))
            }
        }
    }

    fun stop() {
        job?.cancel()
    }

    /** The servers of the account already signed in to; null if its token was refused (signed out on plex.tv). */
    private suspend fun withToken(token: String): PlexSources.Added? = try {
        state = State.Finding
        c.plex.addServers(token)
    } catch (e: PlexException) {
        if (e.reason != PlexException.Reason.UNAUTHORIZED) throw e
        c.settings.setPlexToken(null) // signs in again
        null
    }

    /** Shows a code (a second when the first runs out) until one is entered; then its token, or null if neither was. */
    private suspend fun waitForToken(): String? {
        repeat(CODES) {
            val pin = c.plex.createPin()
            state = State.Code(pin.code)
            repeat((pin.expiresIn * MS_PER_SEC / POLL_MS).toInt().coerceAtLeast(1)) {
                delay(POLL_MS)
                checked(pin.id)?.let { return it }
            }
        }
        return null
    }

    /** The code's token if it has been entered; null if not yet, or if plex.tv didn't answer this time (it's asked again). */
    private suspend fun checked(id: Long): String? = try {
        c.plex.pin(id).authToken
    } catch (e: PlexException) {
        if (e.reason != PlexException.Reason.UNREACHABLE && e.reason != PlexException.Reason.SERVER_ERROR) throw e
        null
    }

    private companion object {
        const val POLL_MS = 2_000L
        const val MS_PER_SEC = 1_000L
        const val CODES = 2
    }
}

@Composable
fun PlexSignInScreen(firstRun: Boolean) {
    val vm = appViewModel { PlexSignInViewModel(it) }
    val nav = LocalNavigator.current
    val c = LocalContext.current
    DisposableEffect(vm) {
        vm.start()
        onDispose { vm.stop() }
    }
    val state = vm.state
    val leave: () -> Unit = { if (firstRun) nav.reset(Screen.Home) else nav.pop() }
    if (state is PlexSignInViewModel.State.Done && state.unreachable.isEmpty()) LaunchedEffect(Unit) { leave() }
    var problem by remember { mutableStateOf<String?>(null) }
    Column(
        Modifier.fillMaxSize().background(Palette.background).padding(horizontal = 96.dp, vertical = 72.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Text(stringResource(R.string.settings_plex_sign_in), style = MaterialTheme.typography.displaySmall)
        when (state) {
            is PlexSignInViewModel.State.Failed -> {
                Text(state.message, Modifier.widthIn(max = 820.dp), style = MaterialTheme.typography.titleMedium, color = Palette.warning)
                PillButton(stringResource(R.string.settings_plex_try_again), vm::start, rememberFirstFocus(state), primary = true)
            }
            PlexSignInViewModel.State.Starting -> Unit
            PlexSignInViewModel.State.Finding ->
                Text(stringResource(R.string.settings_plex_finding), style = MaterialTheme.typography.titleMedium)
            is PlexSignInViewModel.State.Done -> if (state.unreachable.isNotEmpty()) {
                Text(
                    stringResource(R.string.settings_plex_not_reached, state.unreachable.joinToString()),
                    style = MaterialTheme.typography.titleMedium,
                    color = Palette.warning
                )
                PillButton(stringResource(R.string.common_continue), { leave() }, rememberFirstFocus(state), primary = true)
            }
            is PlexSignInViewModel.State.Code -> Row(
                horizontalArrangement = Arrangement.spacedBy(48.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.widthIn(max = 560.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Muted(stringResource(R.string.settings_plex_steps), maxLines = 3)
                    Text(state.code, style = MaterialTheme.typography.displayMedium)
                    Muted(stringResource(R.string.settings_plex_waiting))
                    // On a phone, plex.tv/link can open right here.
                    PillButton(stringResource(R.string.common_link_open_here), { problem = openLink(c, LINK) }, rememberFirstFocus(state))
                    problem?.let { Muted(it, maxLines = 2) }
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    QrCode(LINK, Modifier.size(200.dp))
                    Muted(stringResource(R.string.settings_plex_scan), maxLines = 2)
                }
            }
        }
    }
}

private const val LINK = "https://plex.tv/link"
