package com.biskyt.myplexplayer.data

import androidx.test.core.app.ApplicationProvider
import com.biskyt.myplexplayer.data.db.PlaylistEntity
import com.biskyt.myplexplayer.testutil.FakePlex
import com.biskyt.myplexplayer.testutil.TestApp
import com.biskyt.myplexplayer.testutil.addHomePlex
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Only the server in use is refreshed. A switch to another server stops the old one's refresh. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestApp::class)
class SyncInUseTest {
    private val inUse = FakePlex()
    private val other = FakePlex()
    private val c = ApplicationProvider.getApplicationContext<TestApp>().container
    private var inUseId = 0L
    private var otherId = 0L

    @Before
    fun setUp() = runBlocking {
        inUseId = c.addHomePlex(inUse)
        otherId = c.db.playlists().insert(
            PlaylistEntity(name = "Other Plex", server = other.url, password = FakePlex.SERVER_TOKEN, machineId = "other-plex")
        )
        inUse.paths.clear()
        other.paths.clear()
    }

    @After
    fun tearDown() {
        inUse.close()
        other.close()
    }

    @Test
    fun `refreshing everything leaves a server not in use alone`() = runBlocking {
        c.sync.refreshAllNow()
        assertTrue("the server in use is refreshed", "/library/sections" in inUse.paths)
        assertEquals(emptyList<String>(), other.paths.toList())
    }

    @Test
    fun `a refresh asked for a server not in use does nothing`() = runBlocking {
        c.sync.refreshNow(otherId)
        c.sync.refreshVod(otherId).join()
        assertEquals(emptyList<String>(), other.paths.toList())
    }

    @Test
    fun `switching to another server stops the old one's refresh`() = runBlocking {
        inUse.sectionsDelayMs = 2_000
        val old = c.sync.refresh(inUseId)
        withTimeout(10_000) { while ("/library/sections" !in inUse.paths) delay(10) }
        c.settings.update { it.copy(activePlaylistId = otherId) }
        withTimeout(10_000) { old.join() } // stopped, not left to run
        // Nothing of the old server after its library list: no films or series.
        assertTrue(inUse.paths.toString(), inUse.paths.none { it.startsWith("/library/sections/") })
        // Due an update again, though its channels were loaded: it is updated when switched back to.
        assertTrue(c.playlists.get(inUseId)!!.isStale())
    }
}
