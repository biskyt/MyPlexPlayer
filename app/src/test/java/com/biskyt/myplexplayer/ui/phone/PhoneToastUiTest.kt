package com.biskyt.myplexplayer.ui.phone

import android.content.pm.PackageManager
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onRoot
import androidx.test.core.app.ApplicationProvider
import com.biskyt.myplexplayer.data.Notice
import com.biskyt.myplexplayer.testutil.FakePlex
import com.biskyt.myplexplayer.testutil.TestApp
import com.biskyt.myplexplayer.testutil.addSyncedHomePlex
import com.biskyt.myplexplayer.testutil.createUiComposeRule
import com.biskyt.myplexplayer.testutil.waitForText
import com.biskyt.myplexplayer.ui.AppRoot
import com.biskyt.myplexplayer.ui.theme.autoFindsTv
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** A message in the corner of a phone's screen stays clear of the tab bar along the bottom (the owner, 2026-11-25). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestApp::class, qualifiers = "w390dp-h844dp-xxhdpi")
class PhoneToastUiTest {
    @get:Rule val compose = createUiComposeRule<ComponentActivity>()

    private val plex = FakePlex()
    private val app = ApplicationProvider.getApplicationContext<TestApp>()

    @Before
    fun setUp() = runBlocking<Unit> {
        shadowOf(app.packageManager).setSystemFeature(PackageManager.FEATURE_TOUCHSCREEN, true)
        autoFindsTv = { false } // the phone layout (TestApp sets the TV's)
        app.container.addSyncedHomePlex(plex)
    }

    @After
    fun tearDown() = plex.close()

    @Test
    fun `a notice on Home is above the tab bar`() {
        compose.setContent { AppRoot() }
        compose.waitForText("Recently added movies")
        app.container.notices.post(Notice("Not saved", null))
        compose.waitForText("Not saved")
        val notice = compose.onNode(hasText("Not saved")).fetchSemanticsNode().boundsInRoot
        val bar = compose.onNode(hasText("Home")).fetchSemanticsNode().boundsInRoot
        val screen = compose.onRoot().fetchSemanticsNode().boundsInRoot
        assertTrue("$notice over the tab bar ($bar, screen $screen)", notice.bottom <= bar.top)
    }
}
