package com.biskyt.mytvplayer.ui.settings

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
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
import com.biskyt.mytvplayer.data.BackupException
import com.biskyt.mytvplayer.data.BackupFile
import com.biskyt.mytvplayer.ui.LocalNavigator
import com.biskyt.mytvplayer.ui.Screen
import com.biskyt.mytvplayer.ui.appViewModel
import com.biskyt.mytvplayer.ui.common.Muted
import com.biskyt.mytvplayer.ui.common.PillButton
import com.biskyt.mytvplayer.ui.common.formatDayTime
import com.biskyt.mytvplayer.ui.common.rememberFirstFocus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.launch

class BackupViewModel(private val c: AppContainer) : ViewModel() {
    private val file = c.backupFile

    /** This install's backup file, if any. */
    var saved by mutableStateOf<BackupFile.Saved?>(null)
        private set

    /** What happened last, or what is happening. */
    var status by mutableStateOf<String?>(null)
        private set
    var busy by mutableStateOf(false)
        private set

    /** A file waiting for the user to confirm restoring from it. */
    var confirming by mutableStateOf<Uri?>(null)
        private set

    init {
        viewModelScope.launch { saved = runCatching { file.find() }.getOrNull() }
    }

    fun backUp() = run("Saving…") {
        val s = file.write(c.backup.export())
        saved = s
        "Saved to ${s.path}"
    }

    fun ask(uri: Uri) {
        confirming = uri
        status = null
    }

    fun cancel() {
        confirming = null
    }

    fun failed(message: String) {
        status = message
    }

    /** Replaces everything from the file, then downloads the playlists and guides again. [done]: has playlists. */
    fun restore(done: (Boolean) -> Unit) {
        val uri = confirming ?: return
        if (busy) return
        confirming = null
        run("Restoring…") {
            // In the app's scope: leaving the page mustn't stop a restore halfway.
            done(c.appScope.async { file.read(uri) { c.backup.restore(it) } }.await())
            null
        }
    }

    /** Runs one action at a time; any failure is shown on the page. */
    @Suppress("TooGenericExceptionCaught") // storage and MediaStore errors vary by TV; none may crash the app
    private fun run(working: String, block: suspend () -> String?) {
        if (busy) return
        busy = true
        status = working
        viewModelScope.launch {
            status = try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: BackupException) {
                e.message
            } catch (e: Exception) {
                "Something went wrong: ${e.message ?: e.javaClass.simpleName}"
            }
            busy = false
        }
    }
}

/** Opens the picker in the backup folder (where the TV's picker supports that). */
private class OpenBackup : ActivityResultContracts.OpenDocument() {
    override fun createIntent(context: Context, input: Array<String>): Intent =
        super.createIntent(context, input).putExtra(DocumentsContract.EXTRA_INITIAL_URI, BackupFile.PICKER_START)
}

@Composable
fun BackupScreen() {
    val vm = appViewModel { BackupViewModel(it) }
    val nav = LocalNavigator.current
    val picker = rememberLauncherForActivityResult(OpenBackup()) { uri -> uri?.let(vm::ask) }
    // Some TVs leave out Android's picker; a file manager app installed from the store can usually stand in.
    val getContent = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri -> uri?.let(vm::ask) }
    fun pick() {
        try {
            picker.launch(arrayOf("*/*"))
        } catch (_: ActivityNotFoundException) {
            try {
                getContent.launch("*/*")
            } catch (_: ActivityNotFoundException) {
                vm.failed("This TV has no way to choose a file. Install a file manager app, then try again.")
            }
        }
    }

    SettingsPage("Backup & restore") {
        item {
            Column(Modifier.width(760.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Muted(
                    "Saves your playlists (with their logins), channel and category choices, favourites, TV guide " +
                        "sources and settings to a file. Copy it with a file manager app to keep it somewhere else. The file " +
                        "isn't encrypted, so keep it private.",
                    maxLines = 4
                )
                val last = vm.saved?.let { s ->
                    listOfNotNull(s.modifiedAt.takeIf { it > 0 }?.let(::formatDayTime), s.path).joinToString(" · ")
                }
                Muted(last?.let { "Last backup: $it" } ?: "No backup on this TV yet.", maxLines = 2)
            }
        }
        item {
            if (vm.confirming != null) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        "This replaces your playlists and settings with the backup's. Restore?",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        PillButton("Restore", {
                            vm.restore { hasPlaylists -> nav.reset(if (hasPlaylists) Screen.Home else Screen.Welcome) }
                        }, rememberFirstFocus(), primary = true)
                        PillButton("Cancel", vm::cancel)
                    }
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    PillButton("Back up now", vm::backUp, rememberFirstFocus(), primary = true)
                    val s = vm.saved
                    if (s != null) PillButton("Restore", { vm.ask(s.uri) })
                    PillButton(if (s == null) "Restore from a file…" else "Restore from another file…", ::pick)
                }
            }
        }
        vm.status?.let { item { Text(it, style = MaterialTheme.typography.bodyLarge) } }
    }
}
