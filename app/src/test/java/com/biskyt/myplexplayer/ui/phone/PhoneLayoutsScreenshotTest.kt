package com.biskyt.myplexplayer.ui.phone

import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.core.app.ApplicationProvider
import com.biskyt.myplexplayer.data.db.CAT_MOVIE
import com.biskyt.myplexplayer.data.db.CAT_SERIES
import com.biskyt.myplexplayer.testutil.FakePlex
import com.biskyt.myplexplayer.testutil.Navigators
import com.biskyt.myplexplayer.testutil.TestApp
import com.biskyt.myplexplayer.testutil.addSyncedHomePlex
import com.biskyt.myplexplayer.testutil.createUiComposeRule
import com.biskyt.myplexplayer.testutil.plexChannel
import com.biskyt.myplexplayer.testutil.waitAdvancingClock
import com.biskyt.myplexplayer.testutil.waitForText
import com.biskyt.myplexplayer.ui.AppRoot
import com.biskyt.myplexplayer.ui.theme.autoFindsTv
import com.github.takahirom.roborazzi.captureRoboImage
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Home and Movies on a phone held upright and on an unfolded foldable, recorded to look at. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestApp::class, qualifiers = "w390dp-h844dp-xxhdpi")
class PhoneLayoutsScreenshotTest {
    @get:Rule val compose = createUiComposeRule<ComponentActivity>()

    @get:Rule(order = Navigators.ORDER)
    val navigators = Navigators()

    private val plex = FakePlex()
    private val app = ApplicationProvider.getApplicationContext<TestApp>()

    @Before
    fun setUp() = runBlocking {
        shadowOf(app.packageManager).setSystemFeature(PackageManager.FEATURE_TOUCHSCREEN, true)
        autoFindsTv = { false } // the phone layout (TestApp sets the TV's)
        plex.images = { path -> poster(path.hashCode()) }
        plex.movies = FILMS.mapIndexed { i, (title, year) ->
            FakePlex.film(
                "${51 + i}",
                title,
                year,
                addedAt = 1_700_000_051L + i,
                duration = 155 * MIN,
                thumb = "/library/metadata/${51 + i}/thumb/1"
            )
        }
        // Dune, part-watched: on Continue watching.
        plex.viewOffset["51"] = 40 * MIN
        plex.lastViewedAt["51"] = 1_700_000_000
        plex.continueWatching = listOf("51")
        val pid = app.container.addSyncedHomePlex(plex)
        listOf("1", "2").forEach { app.container.channels.setFavourite(pid, plexChannel(it), true) }
        listOf(52L, 53L).forEach { app.container.vod.setFavourite(pid, CAT_MOVIE, it, true) }
        app.container.vod.setFavourite(pid, CAT_SERIES, 21, true)
        Unit
    }

    @After
    fun tearDown() = plex.close()

    @Test
    fun `home on a phone`() = home("screenshots/phone-home.png")

    @Test
    @Config(qualifiers = "w840dp-h720dp-xhdpi")
    fun `home unfolded`() = home("screenshots/unfolded-home.png")

    @Test
    fun `movies on a phone`() = movies("screenshots/phone-movies.png")

    @Test
    @Config(qualifiers = "w840dp-h720dp-xhdpi")
    fun `movies unfolded`() = movies("screenshots/unfolded-movies.png")

    private fun home(file: String) {
        compose.setContent { AppRoot() }
        compose.waitForText("Continue watching")
        compose.waitForText("Dune")
        settle()
        compose.onRoot().captureRoboImage(file)
    }

    private fun movies(file: String) {
        compose.setContent { AppRoot() }
        compose.waitForText("Continue watching")
        compose.onNode(hasText("Movies") and hasClickAction()).performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForText("Heat")
        settle()
        compose.onRoot().captureRoboImage(file)
    }

    /** Time for the pictures to load and the background to fade in. */
    private fun settle() {
        var steps = 0
        compose.waitAdvancingClock(stepMs = 100) { ++steps > 30 }
    }

    /** A poster in a colour of its own, darker to the bottom. */
    private fun poster(seed: Int): ByteArray {
        val hue = (seed.toLong() and 0xFFFF) % 360
        val top = android.graphics.Color.HSVToColor(floatArrayOf(hue.toFloat(), 0.55f, 0.75f))
        val bottom = android.graphics.Color.HSVToColor(floatArrayOf(hue.toFloat(), 0.6f, 0.25f))
        val img = Bitmap.createBitmap(120, 180, Bitmap.Config.ARGB_8888)
        Canvas(img).drawRect(
            0f,
            0f,
            120f,
            180f,
            Paint().apply {
                shader =
                    LinearGradient(0f, 0f, 0f, 180f, top, bottom, Shader.TileMode.CLAMP)
            }
        )
        return ByteArrayOutputStream().use {
            img.compress(Bitmap.CompressFormat.PNG, 100, it)
            it.toByteArray()
        }
    }

    private companion object {
        const val MIN = 60_000L
        val FILMS = listOf("Dune" to 2021, "Paddington 2" to 2017, "Heat" to 1995, "Arrival" to 2016, "Amélie" to 2001)
    }
}
