package com.biskyt.mytvplayer

import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The app starts on a real Android: a fresh install opens on set-up. */
@RunWith(AndroidJUnit4::class)
class SmokeTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun a_fresh_install_opens_on_set_up() {
        compose.waitUntil(STARTUP_MS) { compose.onAllNodes(hasText("Add my TV provider")).fetchSemanticsNodes().isNotEmpty() }
    }

    private companion object {
        /** Generous: an emulator on a shared CI runner can be slow to start an app. */
        const val STARTUP_MS = 30_000L
    }
}
