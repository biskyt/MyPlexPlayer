package com.biskyt.myplexplayer.testutil

import com.biskyt.myplexplayer.ui.Navigator
import com.biskyt.myplexplayer.ui.Screen
import org.junit.rules.ExternalResource

/**
 * Makes a test's [Navigator]s and, once the test is over, clears their screens' ViewModels as the activity does when
 * it finishes. Left alone they outlive the test: their coroutines (on the main thread, collecting the process-wide
 * settings) and players keep running in the tests after it.
 *
 * Declare it outside the compose rule (`@get:Rule(order = Navigators.ORDER)`), so the screens are gone first.
 */
class Navigators : ExternalResource() {
    private val made = mutableListOf<Navigator>()

    fun new(start: Screen): Navigator = Navigator(start).also { made += it }

    override fun after() {
        made.forEach { it.clear() }
        made.clear()
    }

    companion object {
        /** Before the compose rule (whose order is the default, -1): its content is disposed first. */
        const val ORDER = -2
    }
}
