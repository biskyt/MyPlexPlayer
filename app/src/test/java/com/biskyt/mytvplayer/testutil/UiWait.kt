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
 * Waits for [condition], moving the (simulated) main-looper clock on by [stepMs] before each check: what appears after
 * posted or delayed work (a debounce, a database change notice, a timeout) needs that time to pass.
 */
fun ComposeTestRule.waitAdvancingClock(stepMs: Long = 50, condition: () -> Boolean) = waitUntil(UI_WAIT_MS) {
    ShadowLooper.idleMainLooper(stepMs, TimeUnit.MILLISECONDS)
    condition()
}

/** What every other thread is doing (the top of its stack), for a wait that timed out. */
fun otherThreads(): String = Thread.getAllStackTraces().filterKeys { it.isAlive && it != Thread.currentThread() }.entries
    .joinToString("\n") { (t, stack) -> "${t.name} ${t.state}: " + stack.take(8).joinToString(" < ") }
