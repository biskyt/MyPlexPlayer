package com.biskyt.myplexplayer.ui.phone

import android.content.pm.PackageManager
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.onRoot
import androidx.test.core.app.ApplicationProvider
import com.biskyt.myplexplayer.data.ChannelFilter
import com.biskyt.myplexplayer.testutil.BoardTitles
import com.biskyt.myplexplayer.testutil.FakePlex
import com.biskyt.myplexplayer.testutil.FakeTmdb
import com.biskyt.myplexplayer.testutil.Navigators
import com.biskyt.myplexplayer.testutil.TestApp
import com.biskyt.myplexplayer.testutil.addSyncedHomePlex
import com.biskyt.myplexplayer.testutil.createUiComposeRule
import com.biskyt.myplexplayer.testutil.waitAdvancingClock
import com.biskyt.myplexplayer.testutil.waitForText
import com.biskyt.myplexplayer.ui.NavHost
import com.biskyt.myplexplayer.ui.Screen
import com.biskyt.myplexplayer.ui.ScreenContent
import com.biskyt.myplexplayer.ui.common.Tab
import com.biskyt.myplexplayer.ui.theme.MyPlexTheme
import com.biskyt.myplexplayer.ui.theme.Palette
import com.biskyt.myplexplayer.ui.theme.autoFindsTv
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Search, the TV guide and the pages fed from TMDB on a phone held upright and unfolded, recorded to look at. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestApp::class, qualifiers = "w390dp-h844dp-xxhdpi")
class PhoneScreensScreenshotTest {
    @get:Rule val compose = createUiComposeRule<ComponentActivity>()

    @get:Rule(order = Navigators.ORDER)
    val navigators = Navigators()

    private val plex = FakePlex()
    private val tmdb = FakeTmdb()
    private val app = ApplicationProvider.getApplicationContext<TestApp>()
    private var source = 0L

    @Before
    fun setUp() = runBlocking {
        shadowOf(app.packageManager).setSystemFeature(PackageManager.FEATURE_TOUCHSCREEN, true)
        autoFindsTv = { false } // the phone layout (TestApp sets the TV's)
        plex.images = BoardTitles::art
        plex.movies = listOf(BoardTitles.film(), FakePlex.film("12", "Sintel", 2010))
        plex.shows = listOf(BoardTitles.show())
        plex.episodes = BoardTitles.episodes()
        plex.channels = listOf("One", "Two", "Three", "Four", "Five").mapIndexed { i, name -> FakePlex.Channel("${i + 1}", name) }
        // One programme set to record, for its mark.
        plex.rules += FakePlex.Rule("1", 4, "plex://episode/ch-2-1", emptyMap())
        source = app.container.addSyncedHomePlex(plex)
        app.container.recordings.refresh(source)
        tmdb.use(app.container)
    }

    @After
    fun tearDown() {
        plex.close()
        tmdb.close()
    }

    /** Each page in turn, recorded as screenshots/<prefix>-<page>.png. */
    private fun pages(prefix: String) {
        val pages = listOf(
            Triple("search", Screen.Search("on"), "The Long Harbour"),
            Triple("guide", Screen.Live(ChannelFilter.All, guide = true), "Show 0"),
            Triple("more-like", Screen.MoreLike(source, FakeTmdb.FILM, series = false, "The Long Harbour", Tab.MOVIES), "Paper Boats"),
            Triple(
                "tmdb-title",
                Screen.TmdbTitle(source, FakeTmdb.SIGNAL_HILL, series = true, "Signal Hill", null, Tab.SERIES),
                "Static"
            )
        )
        val navigator = navigators.new(Screen.Home)
        compose.setContent {
            MyPlexTheme {
                Box(Modifier.fillMaxSize().background(Palette.background)) { NavHost(remember { navigator }) { ScreenContent(it) } }
            }
        }
        // Each page once its [shows] text is there: TMDB answers on another thread.
        for ((name, screen, shows) in pages) {
            compose.runOnUiThread { navigator.reset(screen) }
            compose.waitForText(shows, stepMs = 50)
            repeat(PAUSES) { compose.waitAdvancingClock(stepMs = 100) { true } }
            compose.onRoot().captureRoboImage("screenshots/$prefix-$name.png")
        }
    }

    @Test
    fun `the pages on a phone`() = pages("phone")

    @Test
    @Config(qualifiers = "w840dp-h720dp-xhdpi")
    fun `the pages unfolded`() = pages("unfolded")

    private companion object {
        /** Enough for each page to load from the database and the stand-in TMDB. */
        const val PAUSES = 20
    }
}
