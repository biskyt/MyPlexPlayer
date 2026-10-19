package com.biskyt.mytvplayer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.test.core.app.ApplicationProvider
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.biskyt.mytvplayer.R
import com.biskyt.mytvplayer.core.ui.AccentPalette
import com.biskyt.mytvplayer.data.ChannelFilter
import com.biskyt.mytvplayer.data.db.CAT_LIVE
import com.biskyt.mytvplayer.testutil.FakeProvider
import com.biskyt.mytvplayer.testutil.TestApp
import com.biskyt.mytvplayer.ui.home.HomeScreen
import com.biskyt.mytvplayer.ui.live.LiveScreen
import com.biskyt.mytvplayer.ui.theme.MyTvTheme
import com.biskyt.mytvplayer.ui.theme.Palette
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The Google Play listing's graphics and screenshots, drawn from the app itself so they always match it. Only run when
 * asked: `./gradlew :app:recordRoborazziDebug --tests '*StoreAssetsTest*' -PstoreAssets`. Files go to store/ at the
 * repository root. Demo channels and programmes are invented: no real broadcaster or title appears.
 *
 * xhdpi on a 960×540 dp screen gives the 1920×1080 pixels Play asks for in TV screenshots.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = TestApp::class, qualifiers = "w960dp-h540dp-xhdpi")
class StoreAssetsTest {
    @get:Rule val compose = createComposeRule()

    private val provider = FakeProvider().apply {
        categories = listOf("1" to "News", "2" to "Sport", "3" to "Kids", "4" to "Entertainment", "5" to "Documentaries")
        streams = listOf(
            FakeProvider.Stream(101, 1, "Riverside One HD", "4", "one", archiveDays = 7),
            FakeProvider.Stream(102, 2, "Northbridge News", "1", "news"),
            FakeProvider.Stream(103, 3, "Harbour Sport 1", "2", "sport1", archiveDays = 3),
            FakeProvider.Stream(104, 4, "Harbour Sport 2", "2", "sport2"),
            FakeProvider.Stream(105, 5, "Little Sky Kids", "3", "kids"),
            FakeProvider.Stream(106, 6, "Lantern Drama", "4", "drama"),
            FakeProvider.Stream(107, 7, "Atlas Documentary", "5", "docs"),
            FakeProvider.Stream(108, 8, "Meadow Comedy", "4", "comedy"),
        )
        guideChannels = listOf(
            "one" to "Riverside One HD",
            "news" to "Northbridge News",
            "sport1" to "Harbour Sport 1",
            "sport2" to "Harbour Sport 2",
            "kids" to "Little Sky Kids",
            "drama" to "Lantern Drama",
            "docs" to "Atlas Documentary",
            "comedy" to "Meadow Comedy"
        )
        fun now(title: String, desc: String) = "<title>$title</title><desc>$desc</desc><category>Series</category>"
        nowProgramme = mapOf(
            "one" to now("The Garden Planners", "Two neighbours turn a bare yard into a summer garden."),
            "news" to now("Evening Bulletin", "The day's main stories, with weather and sport."),
            "sport1" to now("Coastal Cup: Live", "The semi-final, live from the harbour stadium."),
            "sport2" to now("Cycling Highlights", "The best of the mountain stage."),
            "kids" to now("Paper Boats", "Mia and Leo build a fleet for the park pond."),
            "drama" to now("The Lighthouse Keeper", "A storm cuts the island off for the night."),
            "docs" to now("Wild Rivers", "Following salmon upstream through the autumn."),
            "comedy" to now("Flat Share", "Nobody admits to finishing the milk.")
        )
        nextTitle = mapOf(
            "one" to "Weekend Kitchen",
            "news" to "World Tonight",
            "sport1" to "Coastal Cup: Reaction",
            "sport2" to "Track Day",
            "kids" to "Star Painters",
            "drama" to "Harbour Lights",
            "docs" to "Night Skies",
            "comedy" to "Open Mic"
        )
        movieCategories = emptyList()
        movies = emptyList()
        seriesCategories = emptyList()
        series = emptyList()
    }
    private val app = ApplicationProvider.getApplicationContext<TestApp>()
    private var playlistId = 0L

    @Before
    fun setUp() = runBlocking {
        assumeTrue("Run with -PstoreAssets", System.getProperty("storeAssets") == "true")
        val c = app.container
        playlistId = c.playlists.add("My TV provider", provider.url, "me", "secret")
        // The demo categories have no country prefix, so none is picked by default: show them all.
        c.db.categories().setIncluded(playlistId, CAT_LIVE, provider.categories.map { it.first }, true)
        c.playlists.syncChannels(playlistId)
        c.playlists.get(playlistId)?.let { c.epg.ensureProviderSource(it, c.playlists.client(it).xmltvUrl()) }
        c.epg.importAll(listOf(playlistId))
        listOf(101L, 103L, 105L, 107L).forEach { c.channels.setFavourite(playlistId, it, true) }
        listOf(102L, 106L, 104L).forEach { c.channels.markWatched(playlistId, it) }
        c.settings.update { it.copy(lastLiveFilter = "all") }
        c.settings.current()
        Unit
    }

    @After
    fun tearDown() = provider.close()

    private fun waitFor(text: String) = compose.waitUntil(10_000) {
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        compose.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty()
    }

    private fun screenshot(name: String, start: Screen, ready: String) {
        val navigator = Navigator(start)
        compose.setContent { MyTvTheme { NavHost(remember { navigator }) { Screens(it) } } }
        runCatching { waitFor(ready) }.onFailure {
            compose.onRoot().captureRoboImage("/tmp/claude-0/-home-user/0abf9b51-1657-5eae-875f-1c39214c859f/scratchpad/stuck-$name.png")
            throw AssertionError("'$ready' not shown on $name", it)
        }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("$OUT/screenshots/$name.png")
        flatten("$OUT/screenshots/$name.png")
    }

    @Composable
    private fun Screens(screen: Screen) {
        when (screen) {
            Screen.Home -> HomeScreen()
            is Screen.Live -> LiveScreen(screen.filter, screen.guide)
            else -> Text(screen.toString())
        }
    }

    @Test fun home() = screenshot("1-home", Screen.Home, "The Garden Planners")

    @Test fun liveTv() = screenshot("2-live-tv", Screen.Live(ChannelFilter.All), "Evening Bulletin")

    @Test fun tvGuide() = screenshot("3-tv-guide", Screen.Live(ChannelFilter.All, guide = true), "World Tonight")

    @Test fun favourites() = screenshot("4-favourites", Screen.Live(ChannelFilter.Favourites), "Paper Boats")

    @Test fun icon() = graphic("icon-512", 256.dp, 256.dp) { Icon() }

    @Test fun featureGraphic() = graphic("feature-graphic-1024x500", 512.dp, 250.dp) { Feature() }

    @Test fun tvBanner() = graphic("tv-banner-1280x720", 640.dp, 360.dp) { Banner() }

    /** [width] × [height] in dp, each exactly 2 px: half the pixel size Play asks for. */
    private fun graphic(name: String, width: Dp, height: Dp, content: @Composable () -> Unit) {
        compose.setContent {
            // The app scales itself to the TV; here every dp is exactly 2 px.
            MyTvTheme {
                CompositionLocalProvider(LocalDensity provides Density(2f, 1f)) {
                    Box(Modifier.requiredSize(width, height).testTag(name)) { content() }
                }
            }
        }
        compose.onNodeWithTag(name).captureRoboImage("$OUT/graphics/$name.png")
        // The icon may be transparent; Play wants the others without (24-bit).
        if (name != "icon-512") flatten("$OUT/graphics/$name.png")
    }

    /** Rewrites a PNG without its alpha channel. */
    private fun flatten(path: String) {
        val file = java.io.File(path)
        if (!file.exists()) return // only when recording
        val bitmap = android.graphics.BitmapFactory.decodeFile(path).copy(android.graphics.Bitmap.Config.ARGB_8888, true)
        bitmap.setHasAlpha(false)
        file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** The launcher icon, full-bleed (Play rounds the corners itself). */
    @Composable
    private fun Icon() {
        Box(Modifier.fillMaxSize().background(Accent), contentAlignment = Alignment.Center) {
            androidx.compose.foundation.Image(painterResource(R.drawable.ic_launcher_foreground), null, Modifier.fillMaxSize())
        }
    }

    @Composable
    private fun Feature() {
        Row(
            Modifier.fillMaxSize().background(Palette.background).padding(horizontal = 40.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(28.dp)
        ) {
            Box(Modifier.size(130.dp).clip(RoundedCornerShape(28.dp))) { Icon() }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Wordmark(34)
                Text("Live TV, TV guide and catch-up", color = Palette.text, fontSize = 15.sp, softWrap = false)
                Text("Movies and series from your provider", color = Palette.textMuted, fontSize = 15.sp, softWrap = false)
            }
        }
    }

    @Composable
    private fun Banner() {
        Column(
            Modifier.fillMaxSize().background(Palette.background),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Box(Modifier.size(150.dp).clip(RoundedCornerShape(32.dp))) { Icon() }
            Box(Modifier.padding(top = 24.dp)) { Wordmark(56) }
        }
    }

    /** "MyTVPlayer" with "TV" in the accent colour, as in the app's top bar. */
    @Composable
    private fun Wordmark(size: Int) {
        val style = MaterialTheme.typography.titleLarge.copy(fontSize = size.sp)
        Row {
            Text("My", style = style, color = Palette.text, softWrap = false)
            Text("TV", style = style, color = Accent, softWrap = false)
            Text("Player", style = style, color = Palette.text, softWrap = false)
        }
    }

    private companion object {
        const val OUT = "../store"
        val Accent = Color(AccentPalette.DEFAULT)
    }
}
