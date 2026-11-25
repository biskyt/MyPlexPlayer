package com.biskyt.myplexplayer.ui.theme

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.biskyt.myplexplayer.data.ChannelFilter
import com.biskyt.myplexplayer.data.LayoutMode
import com.biskyt.myplexplayer.testutil.PlainApp
import com.biskyt.myplexplayer.ui.Screen
import com.biskyt.myplexplayer.ui.hasPhoneLayout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Which layout the app draws (the owner, 2026-11-25), and which screens have a phone layout in this step. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = PlainApp::class)
class FormFactorTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @After
    fun tearDown() {
        autoFindsTv = { true } // as PlainApp sets it
    }

    @Test
    fun `Automatic finds a TV, else a phone, or an unfolded screen from 600 dp`() {
        autoFindsTv = { true }
        assertEquals(FormFactor.TV, formFactor(context, LayoutMode.AUTO, 390))
        autoFindsTv = { false }
        assertEquals(FormFactor.PHONE, formFactor(context, LayoutMode.AUTO, 390))
        assertEquals(FormFactor.PHONE, formFactor(context, LayoutMode.AUTO, 599))
        assertEquals(FormFactor.UNFOLDED, formFactor(context, LayoutMode.AUTO, 600))
    }

    @Test
    fun `the Layout setting chooses the TV's or the touch layout whatever the device`() {
        autoFindsTv = { false }
        assertEquals(FormFactor.TV, formFactor(context, LayoutMode.TV, 390))
        autoFindsTv = { true }
        assertEquals(FormFactor.PHONE, formFactor(context, LayoutMode.TOUCH, 390))
        assertEquals(FormFactor.UNFOLDED, formFactor(context, LayoutMode.TOUCH, 840))
    }

    @Test
    fun `Home, the channel list, the films, the series and the players have phone layouts`() {
        assertTrue(Screen.Home.hasPhoneLayout)
        assertTrue(Screen.Live().hasPhoneLayout)
        assertTrue(Screen.Movies().hasPhoneLayout)
        assertTrue(Screen.Series().hasPhoneLayout)
        assertTrue(Screen.VodPlayer(1, 2).hasPhoneLayout)
        assertTrue(Screen.Player(1, 2, ChannelFilter.All).hasPhoneLayout)
        // Drawn as on a TV for now: the guide, the title pages (step 2), DVR and a person's page (step 3), Settings.
        assertFalse(Screen.Live(guide = true).hasPhoneLayout)
        assertFalse(Screen.MovieDetails(1, 2).hasPhoneLayout)
        assertFalse(Screen.SeriesDetails(1, 2).hasPhoneLayout)
        assertFalse(Screen.Dvr.hasPhoneLayout)
        assertFalse(Screen.Settings.hasPhoneLayout)
    }
}
