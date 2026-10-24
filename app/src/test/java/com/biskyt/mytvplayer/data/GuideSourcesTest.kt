package com.biskyt.mytvplayer.data

import androidx.test.core.app.ApplicationProvider
import com.biskyt.mytvplayer.testutil.FakeProvider
import com.biskyt.mytvplayer.testutil.TestApp
import com.biskyt.mytvplayer.testutil.addHomeTv
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A guide switched off is off: its programmes aren't shown, and channels aren't matched to it. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestApp::class)
class GuideSourcesTest {
    private val provider = FakeProvider()
    private val app = ApplicationProvider.getApplicationContext<TestApp>()

    @After
    fun tearDown() = provider.close()

    @Test
    fun `a guide switched off is neither shown nor matched, and switched on again comes back`() = runBlocking {
        val c = app.container
        val id = c.addHomeTv(provider, guide = true)
        val now = System.currentTimeMillis()
        suspend fun onNow() = c.epg.programmes(id, listOf(101), now, now + 60_000)[101].orEmpty().map { it.title }
        assertEquals(listOf("Now on one.uk"), onNow())
        val source = c.db.epg().enabledSources().single()

        c.epg.setSourceEnabled(source, false)
        assertEquals(listOf<String>(), onNow())
        assertEquals(listOf<String>(), c.epg.searchGuideChannels("one").map { it.xmlId }) // nor offered to choose
        c.epg.autoMatch(id, MatchScope.ALL)
        assertTrue(c.epg.mappingInfo(id, listOf(101L)).all { it.sourceId == null })

        c.epg.setSourceEnabled(source, true)
        c.epg.importAll(listOf(id))
        assertEquals(listOf("Now on one.uk"), onNow())
    }

    @Test
    fun `a guide channel chosen by hand comes back with its guide`() = runBlocking {
        val c = app.container
        val id = c.addHomeTv(provider, guide = true)
        val source = c.db.epg().enabledSources().single()
        c.epg.setManual(id, 103, source.id, "cine.fr")
        val now = System.currentTimeMillis()
        suspend fun onNow() = c.epg.programmes(id, listOf(103), now, now + 60_000)[103].orEmpty().map { it.title }
        // The choice's programmes are loaded shortly after (debounced, in case of more choices).
        withTimeout(10_000) { while (onNow().isEmpty()) delay(100) }

        c.epg.setSourceEnabled(source, false)
        c.epg.setSourceEnabled(source, true)
        c.epg.importAll(listOf(id)) // an ordinary update, the guide unchanged on the server
        assertEquals(listOf("Now on cine.fr"), onNow())
    }

    @Test
    fun `a guide switched off by an older version is cleared at the next update`() = runBlocking {
        val c = app.container
        val id = c.addHomeTv(provider, guide = true)
        val source = c.db.epg().enabledSources().single()
        c.db.epg().setSourceEnabled(source.id, false) // as an older version did: the flag alone
        c.epg.importAll(listOf(id))
        val now = System.currentTimeMillis()
        assertEquals(listOf<String>(), c.epg.programmes(id, listOf(101), now, now + 60_000)[101].orEmpty().map { it.title })
        assertTrue(c.epg.mappingInfo(id, listOf(101L)).all { it.sourceId == null })
    }
}
