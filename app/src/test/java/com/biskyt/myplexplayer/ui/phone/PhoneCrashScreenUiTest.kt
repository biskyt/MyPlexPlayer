package com.biskyt.myplexplayer.ui.phone

import android.content.pm.PackageManager
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.biskyt.myplexplayer.testutil.TestApp
import com.biskyt.myplexplayer.testutil.createUiComposeRule
import com.biskyt.myplexplayer.testutil.waitForText
import com.biskyt.myplexplayer.ui.CrashScreen
import com.biskyt.myplexplayer.ui.theme.MyPlexTheme
import com.biskyt.myplexplayer.ui.theme.autoFindsTv
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** The crash screen on a narrow phone: every button is fully on the screen. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestApp::class, qualifiers = "w360dp-h640dp-xxhdpi")
class PhoneCrashScreenUiTest {
    @get:Rule val compose = createUiComposeRule()

    private val app = ApplicationProvider.getApplicationContext<TestApp>()

    @Before
    fun setUp() {
        shadowOf(app.packageManager).setSystemFeature(PackageManager.FEATURE_TOUCHSCREEN, true)
        autoFindsTv = { false } // the phone layout (TestApp sets the TV's)
    }

    @Test
    fun `on a phone, every button is inside the screen`() {
        compose.setContent { MyPlexTheme { CrashScreen("java.lang.IllegalStateException: boom") {} } }
        compose.waitForText("Open the report")
        val screen = compose.onNode(isRoot()).fetchSemanticsNode().boundsInRoot
        for (label in listOf("Open the report", "Continue", "Continue without updating")) {
            val button = compose.onNode(hasText(label) and hasClickAction()).fetchSemanticsNode().boundsInRoot
            // A button squeezed to no width is not shown, though its bounds are inside the screen.
            assertTrue("$label at $button, too small", button.width >= px(MIN_BUTTON_DP) && button.height > 0)
            assertTrue("$label at $button, outside $screen", button.left >= screen.left && button.right <= screen.right)
            assertTrue("$label at $button, outside $screen", button.top >= screen.top && button.bottom <= screen.bottom)
        }
    }

    private fun px(dp: Int) = with(compose.density) { dp.dp.toPx() }

    private companion object {
        /** Narrower than this, a button's label does not fit. */
        const val MIN_BUTTON_DP = 48
    }
}
