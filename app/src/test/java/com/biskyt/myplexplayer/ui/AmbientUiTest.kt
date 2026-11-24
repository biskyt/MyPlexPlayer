package com.biskyt.myplexplayer.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.activity.ComponentActivity
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.core.app.ApplicationProvider
import com.biskyt.myplexplayer.testutil.FakePlex
import com.biskyt.myplexplayer.testutil.TestApp
import com.biskyt.myplexplayer.testutil.addSyncedHomePlex
import com.biskyt.myplexplayer.testutil.createUiComposeRule
import com.biskyt.myplexplayer.testutil.focusedText
import com.biskyt.myplexplayer.testutil.waitAdvancingClock
import com.biskyt.myplexplayer.testutil.waitForText
import com.biskyt.myplexplayer.ui.theme.Palette
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The browsing screens' background follows the card with focus, as MyTVLauncher's Home (the owner, 2026-11-24). Not on
 * MyPlexPlayer's Home: it has the title's own art behind it (the owner, 2026-11-24).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestApp::class, qualifiers = "+w960dp-h540dp-tvdpi")
class AmbientUiTest {
    @get:Rule val compose = createUiComposeRule<ComponentActivity>()

    private val plex = FakePlex()
    private val app = ApplicationProvider.getApplicationContext<TestApp>()

    @Before
    fun setUp() = runBlocking {
        val red = ByteArrayOutputStream().use {
            Bitmap.createBitmap(124, 186, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
                .compress(Bitmap.CompressFormat.PNG, 100, it)
            it.toByteArray()
        }
        // Every picture is red, and every film has a poster (but no art, so Home has no backdrop of its own).
        plex.images = { red }
        plex.movies = FILMS.mapIndexed { i, title -> FakePlex.film("${11 + i}", title, thumb = "/library/metadata/${11 + i}/thumb/1") }
        // No series: Home's first card is a film's.
        plex.shows = emptyList()
        plex.episodes = emptyList()
        app.container.addSyncedHomePlex(plex)
        Unit
    }

    @After
    fun tearDown() = plex.close()

    /** The colour at the screen's left edge, half way down: clear of the cards, where the background shows. */
    private fun edge(): Int {
        var pixel = 0
        compose.runOnUiThread {
            val v = compose.activity.window.decorView
            val img = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
            v.draw(Canvas(img))
            pixel = img.getPixel(2, v.height / 2)
        }
        return pixel
    }

    /** Red, not the accent's yellow glow (its green is more than half its red). */
    private fun isRed(pixel: Int) = Color.red(pixel) > 2 * Color.green(pixel) + 20

    /** Opens Home, where a film's poster takes the focus. */
    private fun openHome() {
        compose.setContent { AppRoot() }
        compose.waitForText("Movies", stepMs = 50)
        compose.waitAdvancingClock(stepMs = 50) { FILMS.any { it in compose.focusedText() } }
    }

    /** Opens the films, whose first poster takes the focus. */
    private fun openMovies() {
        openHome()
        compose.onNode(hasText("Movies") and hasClickAction()).performSemanticsAction(SemanticsActions.OnClick)
        compose.waitAdvancingClock(stepMs = 50) { FILMS.any { it in compose.focusedText() } }
    }

    /** Past the settle delay and the fade (40 steps of 50 ms). */
    private fun settle() {
        var steps = 0
        compose.waitAdvancingClock(stepMs = 50) { ++steps > 40 }
    }

    @Test
    fun `a red poster with focus tints the background red`() {
        openMovies()
        compose.waitAdvancingClock(stepMs = 50) { isRed(edge()) }
    }

    @Test
    fun `with the background brightness at 0, the background stays plain and dark`() {
        runBlocking { app.container.settings.update { it.copy(backdropLevel = 0) } }
        openMovies()
        settle()
        assertEquals(Palette.background.toArgb(), edge())
    }

    @Test
    fun `Home has no such background, also with a red poster in focus`() {
        openHome()
        settle()
        assertEquals(Palette.background.toArgb(), edge())
    }

    @Test
    fun `only the browsing screens with no picture of their own have it`() {
        assertFalse(Screen.Home.hasAmbience)
        assertFalse(Screen.Settings.hasAmbience)
        assertTrue(Screen.Movies().hasAmbience)
        assertTrue(Screen.Dvr.hasAmbience)
    }

    private companion object {
        /** The films, as their cards show them. */
        val FILMS = listOf("Big Buck Bunny", "Sintel")
    }
}
