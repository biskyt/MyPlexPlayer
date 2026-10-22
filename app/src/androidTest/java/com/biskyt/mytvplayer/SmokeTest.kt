package com.biskyt.mytvplayer

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The app on a real Android: what the JVM-based tests can't show, such as Android's own regex engine (ICU), which
 * crashed M3U set-up in 0.9.5 while every JVM test passed. One test, as each needs a fresh install.
 */
@RunWith(AndroidJUnit4::class)
class SmokeTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    /** Serves the playlist from the device itself, so the test needs no outside network. */
    private val server = MockWebServer().apply {
        dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().setBody(PLAYLIST)
        }
        start()
    }

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun a_fresh_install_sets_up_an_M3U_playlist() {
        waitFor(hasText("Add my TV provider"))
        click(hasText("Add my TV provider"))
        click(hasText("M3U playlist URL"))
        click(hasText("https://example.com/playlist.m3u"))
        waitFor(hasSetTextAction())
        compose.onNode(hasSetTextAction()).performTextInput(server.url("/playlist.m3u").toString())
        click(hasText("Continue"))
        // Downloaded and read: its categories are offered.
        waitFor(hasText("Languages & categories"))
        waitFor(hasText("Smoke Test News", substring = true))
    }

    private fun waitFor(matcher: SemanticsMatcher) =
        compose.waitUntil(WAIT_MS) { compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() }

    private fun click(matcher: SemanticsMatcher) {
        waitFor(matcher and hasClickAction())
        compose.onNode(matcher and hasClickAction()).performSemanticsAction(SemanticsActions.OnClick)
    }

    private companion object {
        /** Generous: an emulator on a shared CI runner can be slow. */
        const val WAIT_MS = 30_000L

        /** The attributes the app reads, catch-up included, so each of its regexes runs on Android's engine. */
        val PLAYLIST = """
            #EXTM3U
            #EXTINF:-1 tvg-id="One.uk" tvg-name="One" tvg-logo="http://logo.example/one.png" tvg-country="GB" tvg-language="English" group-title="Smoke Test News" catchup="append" catchup-days="2" catchup-source="?utc={utc}&lutc={lutc}&d={duration:60}" http-user-agent="SmokeTest/1.0",Channel One {HD}
            http://stream.example/one.m3u8
            #EXTINF:-1 tvg-id="Two.uk" group-title="Smoke Test Films",Channel Two
            #EXTVLCOPT:http-referrer=http://ref.example/
            http://stream.example/two.ts
        """.trimIndent()
    }
}
