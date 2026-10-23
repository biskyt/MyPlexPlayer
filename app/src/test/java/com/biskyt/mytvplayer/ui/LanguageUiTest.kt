package com.biskyt.mytvplayer.ui

import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.core.app.ApplicationProvider
import androidx.tv.material3.Text
import com.biskyt.mytvplayer.AppText
import com.biskyt.mytvplayer.InAppLanguage
import com.biskyt.mytvplayer.R
import com.biskyt.mytvplayer.testutil.Navigators
import com.biskyt.mytvplayer.testutil.TestApp
import com.biskyt.mytvplayer.testutil.createUiComposeRule
import com.biskyt.mytvplayer.testutil.waitForText
import com.biskyt.mytvplayer.ui.common.SidePanel
import com.biskyt.mytvplayer.ui.theme.MyTvTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Settings › Language: the screens change language in place. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestApp::class, qualifiers = "+w960dp-h540dp-tvdpi")
class LanguageUiTest {
    @get:Rule val compose = createUiComposeRule()

    @get:Rule(order = Navigators.ORDER)
    val navigators = Navigators()

    private val app = ApplicationProvider.getApplicationContext<TestApp>()

    @After fun followDevice() = AppText.setLanguage(app, "")

    private fun waitFor(text: String) = compose.waitForText(text)

    @Test
    fun `choosing a language switches the screen in place`() {
        val navigator = navigators.new(Screen.Settings)
        compose.setContent { InAppLanguage { MyTvTheme { NavHost(remember { navigator }) { ScreenContent(it) } } } }
        waitFor("System (English)")
        // System → English (UK) → English (US) → Español.
        repeat(3) {
            compose.onNode((hasText("Language") or hasText("Idioma")) and hasClickAction())
                .performSemanticsAction(SemanticsActions.OnClick)
            compose.waitForIdle()
        }
        waitFor("Configuración")
        waitFor("Español")
        assertEquals("es", AppText.locale.toLanguageTag())
        assertEquals(Screen.Settings, navigator.current.screen)
    }

    // A panel is its own window, which would otherwise take the Activity's (the device's) language.
    @Test
    fun `side panels are in the app's language too`() {
        AppText.setLanguage(app, "fr")
        compose.setContent { InAppLanguage { MyTvTheme { SidePanel({}) { Text(stringResource(R.string.settings_language)) } } } }
        waitFor("Langue")
    }
}
