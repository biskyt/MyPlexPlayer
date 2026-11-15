package com.biskyt.myplexplayer.data

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import com.biskyt.myplexplayer.HomeRowsWorker
import com.biskyt.myplexplayer.launcher.LauncherRows
import com.biskyt.myplexplayer.testutil.FakePlex
import com.biskyt.myplexplayer.testutil.TestApp
import com.biskyt.myplexplayer.testutil.addSyncedHomePlex
import com.biskyt.myplexplayer.testutil.eventually
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A launcher's request to refresh the home rows (MyTVLauncher, the owner 2026-11-15): it reaches the app while it is
 * not running, brings what was added to the Plex server and writes the rows again when that setting is on, does nothing
 * when it is off, and is done at most once in a few minutes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestApp::class)
class HomeRowsRefreshTest {
    private val plex = FakePlex()
    private val app = ApplicationProvider.getApplicationContext<TestApp>()
    private val c get() = app.container

    private var pid = 0L

    @Before
    fun setUp() = runBlocking<Unit> {
        pid = c.addSyncedHomePlex(plex)
        app.getSharedPreferences(HomeRowsWorker.PREFS, Context.MODE_PRIVATE).edit().clear().commit()
    }

    @After
    fun tearDown() = plex.close()

    private fun refresh(): ListenableWorker.Result = runBlocking { TestListenableWorkerBuilder<HomeRowsWorker>(app).build().doWork() }

    /** The app's receiver for the request, if it is on. */
    private fun receiver() = app.packageManager.queryBroadcastReceivers(
        Intent(HomeRowsReceiver.action(app)).setPackage(app.packageName),
        0
    ).singleOrNull()?.activityInfo

    private fun LauncherRows.newFilms() = channels.firstOrNull { it.key == LauncherTarget.NEWEST_MOVIES }?.cards.orEmpty().map { it.name }

    @Test
    fun `the request reaches the app's receiver, exported, while the rows are on, and not while they are off`() = runBlocking {
        c.settings.update { it.copy(launcherRows = true) }
        eventually("the receiver on") { receiver() != null }
        assertEquals(HomeRowsReceiver::class.java.name, receiver()?.name)
        assertTrue(receiver()!!.exported)
        c.settings.update { it.copy(launcherRows = false) }
        eventually("the receiver off") { receiver() == null }
    }

    @Test
    fun `with the rows on, a film added on the server goes to the TV's home screen at once, at most once in a few minutes`() = runBlocking {
        c.settings.update { it.copy(launcherRows = true) }
        eventually("the first write") { app.launcherWrites.isNotEmpty() }
        // The sync just now was the last look: a look within seconds of it does not ask the server again.
        delay(PLAY_STATE_GAP_MS + 100)
        plex.movies = plex.movies + FakePlex.film("13", "Elephants Dream", addedAt = System.currentTimeMillis() / 1000)
        val before = app.launcherWrites.size
        assertEquals(ListenableWorker.Result.success(), refresh())
        // (Written otherwise only once changes settle, 20 s after the film came in.)
        assertTrue("written at once", app.launcherWrites.drop(before).any { "Elephants Dream" in it.newFilms() })
        // Asked again at once, with another film on the server: not done again.
        val after = app.launcherWrites.size
        plex.movies = plex.movies + FakePlex.film("14", "Caminandes", addedAt = System.currentTimeMillis() / 1000)
        refresh()
        assertEquals(after, app.launcherWrites.size)
        assertFalse(app.launcherWrites.any { "Caminandes" in it.newFilms() })
    }

    @Test
    fun `with the rows off, nothing is asked of the server and nothing is written`() {
        val asked = plex.requestCount
        assertEquals(ListenableWorker.Result.success(), refresh())
        assertEquals(0, app.launcherWrites.size)
        assertEquals(asked, plex.requestCount)
    }
}
