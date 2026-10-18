package com.biskyt.mytvplayer.ui

import com.biskyt.mytvplayer.data.ChannelFilter
import com.biskyt.mytvplayer.data.db.ChannelProgramme
import com.biskyt.mytvplayer.ui.common.NowNextLoader
import com.biskyt.mytvplayer.ui.common.cycle
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NavigationAndLoaderTest {

    @Test
    fun `back stack push pop reset`() {
        val nav = Navigator(Screen.Home)
        nav.push(Screen.Live())
        nav.push(Screen.Player(1, 5, ChannelFilter.All))
        assertEquals(Screen.Player(1, 5, ChannelFilter.All), nav.current.screen)
        assert(nav.pop())
        assertEquals(Screen.Live(), nav.current.screen)
        nav.reset(Screen.Settings)
        assertEquals(1, nav.stack.size)
        assertFalse(nav.pop())
        // Home (0), Live (1) and Player (2) were all removed; their saved state gets cleaned up.
        assertEquals(setOf(0, 1, 2), nav.dropped.toSet())
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
