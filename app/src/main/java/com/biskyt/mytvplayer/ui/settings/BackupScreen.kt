package com.biskyt.mytvplayer.ui.settings

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
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
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

private const val PERMISSION_DENIED =
    "Without storage permission the backup can't be saved. Allow it in the TV's Settings › Apps › MyTVPlayer › Permissions."

class BackupViewModel(private val c: AppContainer) : ViewModel() {
    private val file = c.backupFile

    val needsPermission: Boolean get() = file.needsPermission

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

    /** A file another app opened with MyTVPlayer (see MainActivity). */
    val openedFile: StateFlow<Uri?> = c.openedFile

    fun askOpened(uri: Uri) {
        c.openedFile.value = null
        ask(uri)
    }

    fun cancel() {
        confirming = null
    }

    fun failed(message: String) {
        status = message
    }

    /** Android 9: whether the storage permission is being asked for by "Back up now" (else on opening the page). */
    private var backUpAfterPermission = false

    /** Android 9: asked already on opening the page (not again if the screen is rebuilt, e.g. the TV changes mode). */
    var askedOnOpening = false
        private set

    fun askingPermission(forBackUp: Boolean) {
        backUpAfterPermission = forBackUp
        if (!forBackUp) askedOnOpening = true
    }

    /**
     * Android 9: the answer to the storage permission. Granted, the backup file can be seen: one already there (e.g.
     * from before a reinstall) is offered to restore, never silently replaced; otherwise "Back up now" carries on.
     */
    fun permissionResult(granted: Boolean) {
        val backUpNext = backUpAfterPermission
        backUpAfterPermission = false
        // Declined on opening the page: fine for restoring from a file, so nothing to report until "Back up now".
        if (!granted) return if (backUpNext) failed(PERMISSION_DENIED) else Unit
        viewModelScope.launch {
            // Not knowing whether one is there, nothing is saved over it.
            val found = try {
                file.find()
            } catch (e: CancellationException) {
                throw e
            } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                return@launch failed("Couldn't look for a backup: ${e.message ?: e.javaClass.simpleName}")
            }
            saved = found
            when {
                !backUpNext -> Unit
                found != null -> status = "There's a backup here already. Restore it, or press Back up now again to replace it."
                else -> backUp()
            }
        }
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

/**
 * Android TV fills in for system apps a TV leaves out (like the file picker) with stand-ins that only say "no
 * application can handle this request" (e.g. com.android.tv.frameworkpackagestubs; makers may name theirs otherwise).
 */
private val ActivityInfo.isStandIn get() = "stub" in packageName.lowercase()

/** Android's own file picker (com.android.documentsui, or Google's com.google.android.documentsui). */
private val ActivityInfo.isAndroidPicker get() = packageName.endsWith(".documentsui")

/** The activities that would answer [intent] (as starting it would find them). */
private fun Context.handlers(intent: Intent) =
    packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY).map { it.activityInfo }

/** Opens the picker in the backup folder (where the TV's picker supports that). */
private class OpenBackup : ActivityResultContracts.OpenDocument() {
    override fun createIntent(context: Context, input: Array<String>): Intent =
        super.createIntent(context, input).putExtra(DocumentsContract.EXTRA_INITIAL_URI, BackupFile.PICKER_START)
}

@Composable
fun BackupScreen() {
    val vm = appViewModel { BackupViewModel(it) }
    val nav = LocalNavigator.current
    // Android 9 only: the Download folder needs the storage permission, asked for on opening the page (and again by
    // "Back up now" if it was declined).
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission(), vm::permissionResult)
    fun askPermission(forBackUp: Boolean) {
        vm.askingPermission(forBackUp)
        permission.launch(BackupFile.STORAGE_PERMISSION)
    }
    // Asked for on opening, so a backup already here (e.g. from before a reinstall) shows, ready to restore.
    LaunchedEffect(Unit) { if (vm.needsPermission && !vm.askedOnOpening) askPermission(forBackUp = false) }
    val backUp = { if (vm.needsPermission) askPermission(forBackUp = true) else vm.backUp() }
    val context = LocalContext.current
    val chooseFile = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) result.data?.data?.let(vm::ask)
    }
    fun pick() {
        // Android's own picker where the TV has it, opened in the backup folder. Anything else answering for it may be
        // a stand-in under another name, so then the file manager apps installed (e.g. X-plore) are offered instead.
        val open = OpenBackup().createIntent(context, arrayOf("*/*"))
        val picker = context.handlers(open).firstOrNull { it.isAndroidPicker }
        val get = Intent(Intent.ACTION_GET_CONTENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE)
        val (standIns, fileManagers) = context.handlers(get).partition { it.isStandIn }
        val tries = listOfNotNull(
            picker?.let { open.setPackage(it.packageName) },
            Intent.createChooser(get, "Choose the backup file")
                .putExtra(Intent.EXTRA_EXCLUDE_COMPONENTS, standIns.map { ComponentName(it.packageName, it.name) }.toTypedArray())
                .takeIf { fileManagers.isNotEmpty() }
        )
        // The first that opens: should the picker fail after all, a file manager is next.
        val opened = tries.any {
            try {
                chooseFile.launch(it)
                true
            } catch (_: ActivityNotFoundException) {
                false
            }
        }
        if (!opened) {
            vm.failed(
                "This TV has no way to choose a file. Open the backup with MyTVPlayer from a file manager app such as " +
                    "X-plore (Open with, or Share), or install one and try again."
            )
        }
    }

    // A file opened with MyTVPlayer from another app: offered to restore, like one chosen here.
    val opened by vm.openedFile.collectAsState()
    LaunchedEffect(opened) { opened?.let(vm::askOpened) }

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
                    PillButton("Back up now", backUp, rememberFirstFocus(), primary = true)
                    val s = vm.saved
                    if (s != null) PillButton("Restore", { vm.ask(s.uri) })
                    PillButton(if (s == null) "Restore from a file…" else "Restore from another file…", ::pick)
                }
            }
        }
        vm.status?.let { item { Text(it, style = MaterialTheme.typography.bodyLarge) } }
    }
}
