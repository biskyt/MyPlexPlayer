package com.biskyt.myplexplayer.ui.live

import androidx.test.core.app.ApplicationProvider
import com.biskyt.myplexplayer.testutil.FakeProvider
import com.biskyt.myplexplayer.testutil.TestApp
import com.biskyt.myplexplayer.testutil.addHomeTv
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The guide asks the provider for past programmes only once the user moves it back, never because time passed. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestApp::class)
class GuideWindowTest {
    private val provider = FakeProvider()
    private val app = ApplicationProvider.getApplicationContext<TestApp>()

    @After
    fun tearDown() = provider.close()

    @Test
    fun `time passing doesn't put the guide in the past, moving it back does`() = runBlocking {
        val playlistId = app.container.addHomeTv(provider)
        var clock = System.currentTimeMillis()
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val catchup = GuideCatchup(app.container, scope, playlistId)
        val window = GuideWindow(scope, catchup) { clock }
        assertFalse(window.inPast)
        // Left open past the next half hour.
        clock += 31 * 60_000L
        assertFalse(window.inPast)
        catchup.lookUpDays() // as the first press back does
        assertTrue(window.move(-1, null))
        assertTrue(window.inPast)
    }

    @Test
    fun `moving on through hours that have passed since it opened counts as the past`() = runBlocking {
        val playlistId = app.container.addHomeTv(provider)
        var clock = System.currentTimeMillis()
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val window = GuideWindow(scope, GuideCatchup(app.container, scope, playlistId)) { clock }
        clock += 3 * 3_600_000L // left open for three hours
        assertTrue(window.move(1, null)) // an hour on from where it opened: still two hours ago
        assertTrue(window.inPast)
    }
}
