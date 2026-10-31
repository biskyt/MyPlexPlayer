package com.biskyt.myplexplayer.ui

import androidx.activity.ComponentActivity
import androidx.test.core.app.ApplicationProvider
import com.biskyt.myplexplayer.testutil.FakePlex
import com.biskyt.myplexplayer.testutil.TestApp
import com.biskyt.myplexplayer.testutil.addHomePlex
import com.biskyt.myplexplayer.testutil.createUiComposeRule
import com.biskyt.myplexplayer.testutil.waitAdvancingClock
import com.biskyt.myplexplayer.testutil.waitForText
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Back on the home screen closes the app; on another screen it goes back first. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestApp::class, qualifiers = "+w960dp-h540dp-tvdpi")
class BackFromHomeTest {
    @get:Rule val compose = createUiComposeRule<ComponentActivity>()

    private val plex = FakePlex()
    private val app = ApplicationProvider.getApplicationContext<TestApp>()

    @Before
    fun setUp() = runBlocking {
        app.container.addHomePlex(plex)
        Unit
    }

    @After
    fun tearDown() = plex.close()

    @Test
    fun `back on the home screen closes the app`() {
        compose.setContent { AppRoot() }
        compose.waitForText("Live TV")
        assertFalse(compose.activity.isFinishing)
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitAdvancingClock { compose.activity.isFinishing }
        assertTrue(compose.activity.isFinishing)
    }
}
