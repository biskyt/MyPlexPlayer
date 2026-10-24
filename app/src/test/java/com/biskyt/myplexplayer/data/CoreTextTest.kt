package com.biskyt.myplexplayer.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.biskyt.myplexplayer.R
import com.biskyt.myplexplayer.appString
import com.biskyt.myplexplayer.core.epg.CategoryLanguageDetector
import com.biskyt.myplexplayer.core.ui.AccentPalette
import com.biskyt.myplexplayer.core.xtream.XtreamException
import com.biskyt.myplexplayer.core.xtream.XtreamException.Reason
import com.biskyt.myplexplayer.testutil.PlainApp
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = PlainApp::class)
class CoreTextTest {
    @Test
    fun `every language group has its heading`() {
        assertEquals(CategoryLanguageDetector.groups, groupLabels.keys)
        assertEquals("English (UK)", groupLabel("UK"))
        assertEquals("English (Ireland)", groupLabel("IE"))
        assertEquals("Balkan", groupLabel("EXYU"))
        assertEquals("Other", groupLabel(null))
        // Other countries by their name, and anything else (a language's name) as it is.
        assertEquals("Austria", groupLabel("AT"))
        assertEquals("Swahili", groupLabel("Swahili"))
    }

    @Test
    fun `the icon's colour is the default accent`() {
        // res/values/colors.xml can't refer to AccentPalette.DEFAULT, so this keeps the two the same.
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertEquals(AccentPalette.DEFAULT, context.getColor(R.color.accent))
    }

    @Test
    fun `every accent colour has its name`() {
        assertEquals(
            listOf("Yellow", "Mint", "Violet", "Sky", "Coral", "Lime", "Pink", "Silver"),
            AccentPalette.presets.map { appString(accentName(it.id)) }
        )
    }

    @Test
    fun `xtream failures are told in the app's words, keeping the English for logs`() {
        val unreachable = XtreamException("Couldn't reach the server: timeout", IOException("timeout"), Reason.UNREACHABLE)
        assertEquals("Couldn't reach the server: timeout", userMessage(unreachable))
        val error = XtreamException("Server returned error 503", reason = Reason.SERVER_ERROR, code = 503)
        assertEquals("Server returned error 503", userMessage(error))
        val login = XtreamException("Login failed (log)", reason = Reason.LOGIN_FAILED)
        assertEquals("Login failed: check the server address, username and password", userMessage(login))
        assertEquals("Login failed (log)", login.message)
        // Shown once wrapped, as before.
        val wrapped = runCatching { withUserXtreamErrors { throw login } }.exceptionOrNull()
        assertEquals("Login failed: check the server address, username and password", wrapped?.message)
        assertEquals("Not an Xtream error", userMessage(IOException("Not an Xtream error")))
    }
}
