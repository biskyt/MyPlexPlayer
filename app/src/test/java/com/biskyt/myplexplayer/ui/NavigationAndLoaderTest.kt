package com.biskyt.myplexplayer.ui

import com.biskyt.myplexplayer.data.ChannelFilter
import com.biskyt.myplexplayer.data.db.ChannelProgramme
import com.biskyt.myplexplayer.ui.common.NowNextLoader
import com.biskyt.myplexplayer.ui.common.cycle
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NavigationAndLoaderTest {

    @Test
    fun `back stack push pop reset`() {
        val nav = Navigator(Screen.Home)
        nav.push(Screen.Live())
        nav.push(Screen.Player(1, 5, ChannelFilter.All))
        assertEquals(Screen.Player(1, 5, ChannelFilter.All), nav.current.screen)
        assertTrue(nav.pop())
        assertEquals(Screen.Live(), nav.current.screen)
        nav.reset(Screen.Settings)
        assertEquals(1, nav.stack.size)
        assertFalse(nav.pop())
    }

    @Test
    fun `closing what a launcher card opened goes back to the launcher, with Home under it`() {
        val nav = Navigator(Screen.Home)
        var left = 0
        nav.onLeave = { left++ }
        nav.push(Screen.MovieDetails(1, 5))
        nav.launcherEntry = nav.current.id
        // A screen over it closes as usual: back to the page.
        nav.push(Screen.VodPlayer(1, 5))
        assertTrue(nav.pop())
        assertEquals(Screen.MovieDetails(1, 5), nav.current.screen)
        assertEquals(0, left)
        // The page closes: back to the launcher, and Home is there for the next time.
        assertTrue(nav.pop())
        assertEquals(1, left)
        assertEquals(listOf(Screen.Home), nav.stack.map { it.screen })
        assertEquals(null, nav.launcherEntry)

        // A player that replaces itself (from its options, as "Play from the start"): still the launcher's entry.
        nav.push(Screen.VodPlayer(1, 5))
        nav.launcherEntry = nav.current.id
        nav.replace(Screen.VodPlayer(1, 5, fromStart = true))
        assertTrue(nav.pop())
        assertEquals(2, left)

        // Opened another way (the stack reset): closing it does not leave the app.
        nav.push(Screen.Settings)
        nav.launcherEntry = nav.current.id
        nav.reset(Screen.Home)
        nav.push(Screen.Settings)
        assertTrue(nav.pop())
        assertEquals(2, left)
    }

    @Test
    fun `a new player closes the one left under the pages above it`() {
        val nav = Navigator(Screen.Home)
        nav.push(Screen.VodPlayer(1, 5))
        val first = nav.current.id // ids start where each navigator's own count starts
        nav.push(Screen.Settings)
        nav.push(Screen.VodPlayer(1, 6))
        assertEquals(listOf(Screen.Home, Screen.Settings, Screen.VodPlayer(1, 6)), nav.stack.map { it.screen })
        assertFalse(nav.stack.any { it.id == first })
        assertTrue(nav.pop())
        assertEquals(Screen.Settings, nav.current.screen)
    }

    @Test
    fun `open goes back to a screen already open rather than stacking a copy`() {
        val nav = Navigator(Screen.Home)
        nav.open(Screen.Playlists)
        nav.push(Screen.PlexSignIn())
        nav.open(Screen.Playlists) // the top bar's server button, from adding a server
        assertEquals(listOf(Screen.Home, Screen.Playlists), nav.stack.map { it.screen })
        nav.open(Screen.Search("News"))
        nav.open(Screen.Search()) // a new search over one with a query
        assertEquals(Screen.Search(), nav.current.screen)
        assertEquals(4, nav.stack.size)
    }

    @Test
    fun `the guide button opens the guide except while watching or setting up`() {
        assertTrue(guideButtonOpensGuide(Screen.Home, liveTv = true))
        assertTrue(guideButtonOpensGuide(Screen.Settings, liveTv = true))
        assertTrue(guideButtonOpensGuide(Screen.Movies(), liveTv = true))
        assertFalse(guideButtonOpensGuide(Screen.Player(1, 5, ChannelFilter.All), liveTv = true))
        assertFalse(guideButtonOpensGuide(Screen.Welcome, liveTv = true))
        assertFalse(guideButtonOpensGuide(Screen.Live(), liveTv = true)) // the live screen switches list and guide itself
        assertFalse(guideButtonOpensGuide(Screen.Categories(1, firstRun = true), liveTv = true))
        assertTrue(guideButtonOpensGuide(Screen.Categories(1), liveTv = true))
        // A server with no Live TV has no guide.
        assertFalse(guideButtonOpensGuide(Screen.Home, liveTv = false))
    }

    @Test
    fun `now next pauses while its screen is hidden and catches up on return`() = runTest {
        val now = 1_000_000L
        val changes = MutableStateFlow(0)
        var loads = 0
        val loader = NowNextLoader(backgroundScope, changes, clock = { now }) { ids, _, _ ->
            loads++
            ids.associateWith { listOf(prog(it, now - 10, now + 10, "now")) }
        }
        loader.request(1)
        advanceTimeBy(200)
        runCurrent()
        assertEquals(1, loads)
        loader.hidden()
        changes.value = 1
        runCurrent()
        assertEquals(1, loads) // nothing reloaded behind another screen
        loader.shown()
        runCurrent()
        assertEquals(2, loads) // the missed guide change, on return
    }

    @Test
    fun `cycle wraps both ways`() {
        val ids = listOf(10L, 20L, 30L)
        assertEquals(20L, ids.cycle(10L))
        assertEquals(10L, ids.cycle(30L))
        assertEquals(30L, ids.cycle(10L, -1))
        assertEquals(10L, ids.cycle(99L)) // unknown → the first option
    }

    private fun prog(id: Long, start: Long, stop: Long, title: String) = ChannelProgramme(id, start, stop, title, null, null)

    @Test
    fun `now next requests are batched into one query`() = runTest {
        val now = 1_000_000L
        val calls = mutableListOf<List<Long>>()
        val loader = NowNextLoader(backgroundScope, clock = { now }) { ids, _, _ ->
            calls += ids
            ids.associateWith { listOf(prog(it, now - 10, now + 10, "now $it"), prog(it, now + 10, now + 20, "next $it")) }
        }
        loader.request(1)
        loader.request(2)
        loader.request(1)
        runCurrent()
        advanceTimeBy(200)
        runCurrent()
        assertEquals(listOf(listOf(1L, 2L)), calls)
        assertEquals("now 2", loader.entries[2]!!.now!!.title)
        assertEquals("next 1", loader.entries[1]!!.next!!.title)
        assertNull(loader.entries[3])
    }
}
