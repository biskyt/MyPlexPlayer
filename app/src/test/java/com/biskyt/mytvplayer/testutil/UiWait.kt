package com.biskyt.mytvplayer.testutil

import androidx.compose.ui.test.junit4.ComposeTestRule
import java.util.concurrent.TimeUnit
import org.robolectric.shadows.ShadowLooper

/**
 * How long UI tests wait for something to appear or take focus. A wait ends as soon as its condition holds, so this
 * only matters when a test fails; it's generous because CI runners can be slow and busy.
 */
const val UI_WAIT_MS = 15_000L

/**
 * Waits for [condition], running the main looper's due work before each check and moving its (simulated) clock on by
 * [stepMs]: what appears after posted work (a database change notice) needs the looper to run, and what appears after
 * a delay (a debounce, a timeout) needs that time to pass as well.
 */
fun ComposeTestRule.waitAdvancingClock(stepMs: Long = 0, condition: () -> Boolean) = waitUntil(UI_WAIT_MS) {
    ShadowLooper.idleMainLooper(stepMs, TimeUnit.MILLISECONDS)
    condition()
}

/** What every other thread is doing (the top of its stack), for a wait that timed out. */
fun otherThreads(): String = Thread.getAllStackTraces().filterKeys { it.isAlive && it != Thread.currentThread() }.entries
    .joinToString("\n") { (t, stack) -> "${t.name} ${t.state}: " + stack.take(8).joinToString(" < ") }
