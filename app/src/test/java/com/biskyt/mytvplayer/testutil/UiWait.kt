package com.biskyt.mytvplayer.testutil

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.isFocused
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

/** The text of whatever has focus ("" for focus on something without text). */
fun ComposeTestRule.focusedText(): String = onAllNodes(isFocused()).fetchSemanticsNodes().joinToString(" | ") { node ->
    node.config.getOrNull(SemanticsProperties.Text)?.joinToString().orEmpty()
}

/** Lets a second of (simulated) time pass, idling as it goes: for checking that something did *not* happen. */
fun ComposeTestRule.settle() = repeat(10) {
    ShadowLooper.idleMainLooper(100, TimeUnit.MILLISECONDS)
    waitForIdle()
}
