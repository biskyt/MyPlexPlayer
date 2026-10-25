package com.biskyt.myplexplayer

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.biskyt.myplexplayer.data.db.MovieEntity
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.runner.RunWith

/**
 * The app on a real Android: what the JVM-based tests can't show, such as Android's own regex engine (ICU) and
 * networking. One test, as each needs a fresh install: signing in to Plex and loading a server's films.
 */
@RunWith(AndroidJUnit4::class)
class SmokeTest {
    private val container = InstrumentationRegistry.getInstrumentation().targetContext.container

    /** Whether the code shown has been entered at plex.tv/link. */
    @Volatile private var claimed = false

    /** plex.tv and a Plex server in one, served from the device itself, so the test needs no outside network. */
    private val server = MockWebServer().apply {
        dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = answer(request.requestUrl!!.encodedPath, request.method.orEmpty())
        }
        start()
    }
    private val url = server.url("/").toString().trimEnd('/')

    /** Before the app starts, so nothing can reach the real plex.tv first. */
    @get:Rule(order = 0)
    val standIn = object : ExternalResource() {
        override fun before() {
            container.plex.plexTv = url
        }
    }

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<MainActivity>()

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun a_fresh_install_signs_in_to_Plex_and_loads_its_films() {
        click(hasText("Sign in to Plex"))
        waitFor(hasText("ABCD"))
        claimed = true // entered at plex.tv/link
        val film = waitForFilm()
        assertEquals("Smoke Test Film", film.title)
        assertEquals(2020, film.year)
    }

    /** The server's film once it's stored (films load in the background, after sign-in). */
    private fun waitForFilm(): MovieEntity {
        var found: MovieEntity? = null
        compose.waitUntil(WAIT_MS) {
            found = runBlocking { container.playlists.allIds().firstNotNullOfOrNull { container.db.vod().movie(it, 11) } }
            found != null
        }
        return found!!
    }

    private fun answer(path: String, method: String): MockResponse = when {
        path == "/api/v2/pins" && method == "POST" -> json("""{"id":1,"code":"ABCD","expiresIn":900}""").setResponseCode(201)
        path == "/api/v2/pins/1" -> json(if (claimed) """{"id":1,"code":"ABCD","authToken":"account"}""" else """{"id":1,"code":"ABCD"}""")
        path == "/api/v2/resources" -> json(
            """[{"name":"Smoke Plex","clientIdentifier":"smoke","provides":"server","owned":true,"accessToken":"token",""" +
                """"connections":[{"uri":"$url","local":true,"relay":false}]}]"""
        )
        path == "/identity" -> mediaContainer("""{"machineIdentifier":"smoke"}""")
        path == "/library/sections" -> mediaContainer("""{"Directory":[{"key":"1","type":"movie","title":"Films"}]}""")
        path == "/library/sections/1/all" -> mediaContainer(
            """{"size":1,"totalSize":1,"offset":0,"Metadata":[{"ratingKey":"11","type":"movie","title":"Smoke Test Film",""" +
                """"year":2020,"addedAt":1700000000,"Media":[{"container":"mp4","Part":[{"key":"/library/parts/1/1/file.mp4"}]}]}]}"""
        )
        else -> mediaContainer("{}")
    }

    private fun mediaContainer(body: String) = json("""{"MediaContainer":$body}""")

    private fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)

    private fun waitFor(matcher: SemanticsMatcher) =
        compose.waitUntil(WAIT_MS) { compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() }

    private fun click(matcher: SemanticsMatcher) {
        waitFor(matcher and hasClickAction())
        compose.onNode(matcher and hasClickAction()).performSemanticsAction(SemanticsActions.OnClick)
    }

    private companion object {
        /** Generous: an emulator on a shared CI runner can be slow (and the code is checked every 2 seconds). */
        const val WAIT_MS = 60_000L
    }
}
