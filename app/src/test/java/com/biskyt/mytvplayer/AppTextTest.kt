package com.biskyt.mytvplayer

import android.os.LocaleList
import androidx.test.core.app.ApplicationProvider
import com.biskyt.mytvplayer.testutil.PlainApp
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = PlainApp::class)
class AppTextTest {
    private val app = ApplicationProvider.getApplicationContext<PlainApp>()

    @After fun followDevice() = AppText.setLanguage(app, "")

    private fun resolve(chosen: String, vararg device: String) =
        AppText.resolve(chosen, LocaleList.forLanguageTags(device.joinToString(","))).toLanguageTag()

    @Test fun `a chosen language wins over the device's`() = assertEquals("fr", resolve("fr", "es-ES"))

    @Test fun `the device's language and country when the app has them`() = assertEquals("en-US", resolve("", "en-US"))

    @Test fun `else the device's language alone`() {
        assertEquals("fr", resolve("", "fr-CA"))
        assertEquals("pt-BR", resolve("", "pt-PT"))
        assertEquals("en-GB", resolve("", "en-AU"))
    }

    @Test fun `the device's order wins over an exact match further down`() = assertEquals("fr", resolve("", "fr-CA", "en-US"))

    @Test fun `the first of the device's languages the app has`() = assertEquals("es", resolve("", "de-DE", "es-MX", "fr-FR"))

    @Test fun `UK English when the app has none of them`() = assertEquals("en-GB", resolve("", "de-DE", "ja-JP"))

    @Test fun `words follow the chosen language straight away`() {
        AppText.setLanguage(app, "es")
        assertEquals("es", AppText.locale.toLanguageTag())
        assertEquals("Idioma", appString(R.string.settings_language))
        AppText.setLanguage(app, "en-US")
        assertEquals("Favorites", appString(R.string.common_favourites))
        AppText.setLanguage(app, "")
        assertEquals("Favourites", appString(R.string.common_favourites))
        AppText.setLanguage(app, "de") // not offered (any more): the device's is followed
        assertEquals("" to "en-GB", AppText.chosen.value to AppText.locale.toLanguageTag())
    }
}
