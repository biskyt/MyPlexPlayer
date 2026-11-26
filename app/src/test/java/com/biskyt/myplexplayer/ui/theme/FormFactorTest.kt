package com.biskyt.myplexplayer.ui.theme

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.biskyt.myplexplayer.data.ChannelFilter
import com.biskyt.myplexplayer.data.LayoutMode
import com.biskyt.myplexplayer.testutil.PlainApp
import com.biskyt.myplexplayer.ui.Screen
import com.biskyt.myplexplayer.ui.common.Tab
import com.biskyt.myplexplayer.ui.hasPhoneLayout
import com.biskyt.myplexplayer.ui.hasTabBar
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Which layout the app draws (the owner, 2026-11-25), and which screens have a phone layout. */
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
    fun `every screen has a phone layout`() {
        listOf(
            Screen.Home,
            Screen.Live(),
            Screen.Live(guide = true),
            Screen.Movies(),
            Screen.Series(),
            Screen.Dvr,
            Screen.Search(),
            Screen.VodPlayer(1, 2),
            Screen.Player(1, 2, ChannelFilter.All),
            Screen.MovieDetails(1, 2),
            Screen.SeriesDetails(1, 2),
            Screen.Person(1, 2, null, "Ana Ruiz", null, null, "The Harbour", Tab.MOVIES),
            Screen.MoreLike(1, 2, series = false, title = "The Harbour", tab = Tab.MOVIES),
            Screen.TmdbTitle(1, 2, series = false, title = "The Harbour", posterUrl = null, tab = Tab.MOVIES)
        ).forEach { assertTrue("$it", it.hasPhoneLayout) }
    }

    @Test
    fun `Settings, every page under it and the first launch's pages have phone layouts, with no tab bar`() {
        listOf(
            Screen.Welcome,
            Screen.Settings,
            Screen.Playlists,
            Screen.PlexSignIn(),
            Screen.PlexSignIn(firstRun = true),
            Screen.Categories(1),
            Screen.PlaybackSettings,
            Screen.Appearance,
            Screen.MiscSettings,
            Screen.Backup,
            Screen.About,
            Screen.Licences
        ).forEach {
            assertTrue("$it", it.hasPhoneLayout)
            assertFalse("$it", it.hasTabBar)
        }
    }

    @Test
    fun `the tabs, Search and the pages opened from them have the tab bar, and the guide and the players do not`() {
        listOf(
            Screen.Home,
            Screen.Live(),
            Screen.Movies(),
            Screen.Series(),
            Screen.Dvr,
            Screen.Search(),
            Screen.MovieDetails(1, 2),
            Screen.SeriesDetails(1, 2),
            Screen.Person(1, 2, null, "Ana Ruiz", null, null, "The Harbour", Tab.MOVIES),
            Screen.MoreLike(1, 2, series = false, title = "The Harbour", tab = Tab.MOVIES),
            Screen.TmdbTitle(1, 2, series = false, title = "The Harbour", posterUrl = null, tab = Tab.MOVIES)
        )
            .forEach { assertTrue("$it", it.hasTabBar) }
        listOf(
            Screen.VodPlayer(1, 2),
            Screen.Player(1, 2, ChannelFilter.All),
            Screen.Live(guide = true)
        ).forEach { assertFalse("$it", it.hasTabBar) }
    }
}
