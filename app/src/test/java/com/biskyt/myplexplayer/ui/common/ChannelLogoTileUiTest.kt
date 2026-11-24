package com.biskyt.myplexplayer.ui.common

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import com.biskyt.myplexplayer.testutil.FakePlex
import com.biskyt.myplexplayer.testutil.TestApp
import com.biskyt.myplexplayer.testutil.assertColourAcross
import com.biskyt.myplexplayer.testutil.createUiComposeRule
import com.biskyt.myplexplayer.testutil.drawScreen
import com.biskyt.myplexplayer.testutil.waitAdvancingClock
import com.biskyt.myplexplayer.ui.theme.MyPlexTheme
import com.biskyt.myplexplayer.ui.theme.Palette
import java.io.ByteArrayOutputStream
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Every channel logo shows on the dark, see-through tile, and a black one is made white (the owner, 2026-11-24). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestApp::class, qualifiers = "+w960dp-h540dp-tvdpi")
class ChannelLogoTileUiTest {
    @get:Rule val compose = createUiComposeRule<ComponentActivity>()

    private val plex = FakePlex()

    @After
    fun tearDown() = plex.close()

    /** A black logo in a wide transparent border, which the dark tile would hide. */
    private fun blackLogo(): ByteArray {
        val bitmap = Bitmap.createBitmap(LOGO_WIDTH, LOGO_HEIGHT, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).drawRect(20f, 20f, 100f, 40f, Paint().apply { color = android.graphics.Color.BLACK })
        return ByteArrayOutputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            it.toByteArray()
        }
    }

    @Test
    fun `a black logo shows white on the dark tile`() {
        val logo = blackLogo()
        plex.images = { if (it == "black.png") logo else null }
        compose.setContent {
            MyPlexTheme {
                // On the app's background, which the tile lets show through.
                Box(Modifier.background(Palette.background)) {
                    Box(Modifier.testTag("logo")) { ChannelLogo(plex.imageAddress("black.png"), "Channel One") }
                }
            }
        }
        // The initials show until the logo does.
        compose.waitAdvancingClock { compose.onAllNodes(hasText("CO")).fetchSemanticsNodes().isEmpty() }
        val screen = compose.drawScreen()
        val tile = compose.onNodeWithTag("logo").fetchSemanticsNode()
        // Next to the left edge, in the margin round the logo: the see-through tile over the background. In the middle:
        // the logo, now white.
        assertColourAcross(screen, tile, listOf(2), LOGO_TILE.compositeOver(Palette.background), tolerance = 2)
        assertColourAcross(screen, tile, listOf(tile.size.width / 2), Color.White)
    }
}

private const val LOGO_WIDTH = 120
private const val LOGO_HEIGHT = 60
