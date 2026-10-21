package com.biskyt.mytvplayer.ui

import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import androidx.activity.ComponentActivity
import androidx.compose.runtime.remember
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.performSemanticsAction
import com.biskyt.mytvplayer.testutil.TestApp
import com.biskyt.mytvplayer.testutil.waitAdvancingClock
import com.biskyt.mytvplayer.ui.theme.MyTvTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Restoring from a file on a TV without Android's file picker. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestApp::class, qualifiers = "w960dp-h540dp-tvdpi")
class BackupPickerTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val stub = ComponentName("com.android.tv.frameworkpackagestubs", "com.android.tv.frameworkpackagestubs.Stubs\$DocumentsStub")
    private val fileManager = ComponentName("com.lonelycatgames.Xplore", "com.lonelycatgames.Xplore.GetContent")

    private fun installs(component: ComponentName, action: String) {
        val pm = shadowOf(compose.activity.packageManager)
        pm.addActivityIfNotPresent(component)
        pm.addIntentFilterForActivity(
            component,
            IntentFilter(action).apply {
                addCategory(Intent.CATEGORY_DEFAULT)
                addCategory(Intent.CATEGORY_OPENABLE)
                addDataType("*/*")
            }
        )
    }

    @Test
    fun `the TV's stand-in picker is skipped for a file manager`() {
        // Android TV's stand-in answers both kinds of request, and only says nothing can handle it.
        installs(stub, Intent.ACTION_OPEN_DOCUMENT)
        installs(stub, Intent.ACTION_GET_CONTENT)
        installs(fileManager, Intent.ACTION_GET_CONTENT)
        compose.setContent { MyTvTheme { NavHost(remember { Navigator(Screen.Backup) }) { ScreenContent(it) } } }
        compose.waitAdvancingClock { compose.onAllNodes(hasText("Restore from", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(hasText("Restore from", substring = true) and hasClickAction()).performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()

        val started = shadowOf(compose.activity).nextStartedActivityForResult.intent
        assertEquals(Intent.ACTION_CHOOSER, started.action)
        @Suppress("DEPRECATION")
        val target = started.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
        assertEquals(Intent.ACTION_GET_CONTENT, target.action)
        @Suppress("DEPRECATION")
        val excluded = started.getParcelableArrayExtra(Intent.EXTRA_EXCLUDE_COMPONENTS)!!.toList()
        assertTrue(excluded.toString(), stub in excluded)
    }
}
