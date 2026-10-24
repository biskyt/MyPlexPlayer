package com.biskyt.myplexplayer.data

import android.os.Environment
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.biskyt.myplexplayer.container
import com.biskyt.myplexplayer.testutil.TestApp
import com.biskyt.myplexplayer.ui.settings.BackupViewModel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowEnvironment

/** Android 9 (Fire OS 7) has no MediaStore downloads: the backup is written directly, with the storage permission. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = TestApp::class)
class BackupFileTest {
    private val app = ApplicationProvider.getApplicationContext<TestApp>()
    private val file = BackupFile(app)

    @Before
    fun mountStorage() = ShadowEnvironment.setExternalStorageState(Environment.MEDIA_MOUNTED)

    @Test
    fun `android 9 saves, finds and reads the backup once storage permission is granted`() = runBlocking {
        shadowOf(app).grantPermissions(BackupFile.STORAGE_PERMISSION)
        assertEquals(false, file.needsPermission)
        assertNull(file.find())

        val saved = file.write("""{"v":1}""".toByteArray())
        assertEquals("Download/MyPlexPlayer/myplexplayer-backup.json", saved.path)
        file.write("""{"v":2}""".toByteArray()) // overwrites in place
        assertEquals(saved.uri, file.find()?.uri)
        assertEquals("""{"v":2}""", file.read(saved.uri) { it.readBytes().decodeToString() })
    }

    @Test
    fun `granting the permission from Back up now offers a backup already there instead of replacing it`() {
        // A backup from before a reinstall: on disk, but unseen until the permission is granted again.
        shadowOf(app).grantPermissions(BackupFile.STORAGE_PERMISSION)
        runBlocking { file.write("""{"old":true}""".toByteArray()) }
        shadowOf(app).denyPermissions(BackupFile.STORAGE_PERMISSION)

        val vm = BackupViewModel(app.container)
        runBackgroundWork()
        assertNull(vm.saved)
        vm.askingPermission(forBackUp = true)
        shadowOf(app).grantPermissions(BackupFile.STORAGE_PERMISSION)
        vm.permissionResult(granted = true)
        settle { vm.saved != null }

        assertTrue(vm.status.orEmpty(), vm.status.orEmpty().contains("backup here already"))
        val content = runBlocking { file.read(vm.saved!!.uri) { it.readBytes().decodeToString() } }
        assertEquals("""{"old":true}""", content)
    }

    /** Runs the main thread until [done], for work the view model does in the background; fails after 5 seconds. */
    private fun settle(done: () -> Boolean) {
        repeat(250) {
            shadowOf(Looper.getMainLooper()).idle()
            if (done()) return
            Thread.sleep(20)
        }
        throw AssertionError("Timed out")
    }

    /** Lets the view model's start-up lookup (on a background thread) finish. */
    private fun runBackgroundWork() = repeat(5) {
        shadowOf(Looper.getMainLooper()).idle()
        Thread.sleep(20)
    }

    @Test
    fun `android 9 without the permission asks for it instead of failing`() = runBlocking {
        assertTrue(file.needsPermission)
        assertNull(file.find())
        val error = runCatching { file.write(ByteArray(1)) }.exceptionOrNull()
        assertTrue(error.toString(), error is BackupException)
    }
}
