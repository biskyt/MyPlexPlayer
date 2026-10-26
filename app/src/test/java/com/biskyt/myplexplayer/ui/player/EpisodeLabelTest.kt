package com.biskyt.myplexplayer.ui.player

import androidx.test.core.app.ApplicationProvider
import com.biskyt.myplexplayer.AppText
import com.biskyt.myplexplayer.core.vod.EpisodeInfo
import com.biskyt.myplexplayer.testutil.PlainApp
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A provider's episode title is shown as it is; the app's own words around it follow the app's language. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = PlainApp::class)
class EpisodeLabelTest {
    private val app = ApplicationProvider.getApplicationContext<PlainApp>()

    @After fun followDevice() = AppText.setLanguage(app, "")

    private fun episode(title: String) = EpisodeInfo(1, 2, 3, title, "mp4", null, null, null)

    @Test
    fun `a provider's title is kept as it is, and only the app's words change language`() {
        AppText.setLanguage(app, "es")
        val titled = episode("The Pilot")
        assertEquals("The Pilot", episodeName(titled))
        assertEquals("Temporada 2 · Episodio 3 · The Pilot", episodeLabel(titled))
        // Only its number: shown as the provider wrote it, and left out of the label so the number isn't there twice.
        val numbered = episode("Episode 3")
        assertEquals("Episode 3", episodeName(numbered))
        assertEquals("Temporada 2 · Episodio 3", episodeLabel(numbered))
    }

    @Test
    fun `an episode with no title is named by the app, in its language`() {
        AppText.setLanguage(app, "es")
        val untitled = episode("")
        assertNull(providerTitle(untitled))
        assertEquals("Episodio 3", episodeName(untitled))
        assertEquals("Temporada 2 · Episodio 3", episodeLabel(untitled))
    }
}
