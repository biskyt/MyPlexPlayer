package com.biskyt.myplexplayer.ui.common

import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.unit.Density
import com.biskyt.myplexplayer.core.vod.CastMember
import com.biskyt.myplexplayer.testutil.TestApp
import com.biskyt.myplexplayer.testutil.createUiComposeRule
import com.biskyt.myplexplayer.testutil.waitForText
import com.biskyt.myplexplayer.ui.theme.MyPlexTheme
import com.biskyt.myplexplayer.ui.theme.bd
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The cast panel's photos are 72 tall, half as large again as before (the owner, 2026-11-24). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestApp::class, qualifiers = "+w960dp-h540dp-tvdpi")
class CastPanelUiTest {
    @get:Rule val compose = createUiComposeRule()

    @Test
    fun `each person's row is as tall as the larger photo`() {
        var density: Density? = null
        compose.setContent {
            MyPlexTheme {
                density = LocalDensity.current
                CastPanel("Dune", listOf(CastMember("Tom Hale", "Rose", null)), onDismiss = {})
            }
        }
        compose.waitForText("Tom Hale")
        val row = compose.onNode(hasText("Tom Hale") and hasClickAction()).fetchSemanticsNode().size.height
        // The app's density (the theme sets its own), as the panel draws with it.
        val photo = with(density!!) { 72.bd.roundToPx() }
        assertTrue("row $row px, photo $photo px", row >= photo)
    }
}
