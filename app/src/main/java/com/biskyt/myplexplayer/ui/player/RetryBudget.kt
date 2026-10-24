package com.biskyt.myplexplayer.ui.player

/** How many times a failing stream is tried again before the user is told: a few in a row, until [reset]. */
class RetryBudget(private val max: Int) {
    var used = 0
        private set

    /** Uses one retry; false when none are left. */
    fun take(): Boolean = (used < max).also { if (it) used++ }

    fun reset() {
        used = 0
    }
}
