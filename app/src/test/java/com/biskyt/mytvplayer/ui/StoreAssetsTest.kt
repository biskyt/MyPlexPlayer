package com.biskyt.mytvplayer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performSemanticsAction
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
import com.biskyt.mytvplayer.data.db.CAT_MOVIE
import com.biskyt.mytvplayer.testutil.FakeProvider
import com.biskyt.mytvplayer.testutil.TestApp
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
 * The Google Play and Amazon Appstore listings' graphics and screenshots, drawn from the app itself so they always match it. Only run when
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
            FakeProvider.Stream(102, 2, "Northbridge News", "1", "news", archiveDays = 3),
            FakeProvider.Stream(103, 3, "Harbour Sport 1", "2", "sport1", archiveDays = 3),
            FakeProvider.Stream(104, 4, "Harbour Sport 2", "2", "sport2"),
            FakeProvider.Stream(105, 5, "Little Sky Kids", "3", "kids", archiveDays = 2),
            FakeProvider.Stream(106, 6, "Lantern Drama", "4", "drama", archiveDays = 7),
            FakeProvider.Stream(107, 7, "Atlas Documentary", "5", "docs", archiveDays = 7),
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
        movieCategories = listOf("30" to "Drama", "31" to "Family", "32" to "Documentary")
        movies = listOf(
            Triple(801L, "The Quiet Harbour (2023)", "30"),
            Triple(802L, "Northern Lights Express (2022)", "31"),
            Triple(803L, "A Summer of Kites (2021)", "31"),
            Triple(804L, "The Last Lighthouse (2020)", "30"),
            Triple(805L, "Salt and Stone (2024)", "32"),
            Triple(806L, "Midnight Bakery (2019)", "30"),
            Triple(807L, "Paper Planets (2023)", "31"),
            Triple(808L, "Rivers of the North (2022)", "32"),
            Triple(809L, "The Long Way Home (2018)", "30"),
            Triple(810L, "Clockwork Garden (2024)", "31"),
        )
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

    private fun screenshot(name: String, start: Screen, ready: String, then: () -> Unit = {}) {
        val navigator = Navigator(start)
        compose.setContent { MyTvTheme { NavHost(remember { navigator }) { ScreenContent(it) } } }
        runCatching { waitFor(ready) }.onFailure {
            compose.onRoot().captureRoboImage("build/store-assets/stuck-$name.png")
            throw AssertionError("'$ready' not shown on $name", it)
        }
        then()
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("$OUT/screenshots/$name.png")
        flatten("$OUT/screenshots/$name.png")
    }

    @Test fun home() = screenshot("1-home", Screen.Home, "The Garden Planners")

    @Test fun liveTv() = screenshot("2-live-tv", Screen.Live(ChannelFilter.All), "Evening Bulletin")

    @Test fun tvGuide() = screenshot("3-tv-guide", Screen.Live(ChannelFilter.All, guide = true), "World Tonight")

    // Its last row, so the list has all its channels.
    @Test fun catchup() = screenshot("4-catch-up", Screen.Live(ChannelFilter.Catchup), "Wild Rivers")

    @Test fun movies() {
        runBlocking {
            val c = app.container
            c.db.categories().setIncluded(playlistId, CAT_MOVIE, provider.movieCategories.map { it.first }, true)
            c.vod.sync(c.playlists.get(playlistId)!!)
        }
        screenshot("5-movies", Screen.Movies(), "Clockwork Garden")
    }

    // One channel set by hand (an hour's shift), and another open on its suggestions.
    @Test fun guideMatching() {
        runBlocking {
            val source = app.container.db.epg().enabledSources().first().id
            app.container.epg.setManual(playlistId, 108, source, "comedy", offsetMinutes = 60)
        }
        screenshot("6-guide-matching", Screen.GuideMatching(playlistId), "Meadow Comedy") {
            // As AppUiTest does: performClick() doesn't reach the TV card's onClick under Robolectric.
            compose.onAllNodes(hasText("Harbour Sport 2") and hasClickAction())[0].performSemanticsAction(SemanticsActions.OnClick)
            waitFor("Time shift")
        }
    }

    @Test fun icon() = graphic("icon-512", 256.dp, 256.dp) { Icon() }

    @Test fun featureGraphic() = graphic("feature-graphic-1024x500", 512.dp, 250.dp) { Feature() }

    @Test fun tvBanner() = graphic("tv-banner-1280x720", 640.dp, 360.dp) { Banner() }

    // Amazon's small and large icons, if the console asks for them (it wants no transparency).
    @Test fun amazonIconSmall() = graphic("amazon-icon-114", 57.dp, 57.dp) { Icon() }

    @Test fun amazonIconLarge() = graphic("amazon-icon-512", 256.dp, 256.dp) { Icon() }

    @Test fun fireTvBackground() = graphic("fire-tv-background-1920x1080", 960.dp, 540.dp) { Background() }

    /** [width] × [height] in dp, each exactly 2 px: half the pixel size the store asks for. */
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
        // Play's icon may be transparent; everything else (Amazon's icons too) is without (24-bit).
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

    /**
     * Amazon's Fire TV background: shown behind the app's details, which sit on the left, so it has no text. A soft glow
     * of the accent colour over the app's dark ground, with a few rows of a TV guide on the right (the one on now lit).
     */
    @Composable
    private fun Background() {
        val glow = Accent.copy(alpha = 0.28f)
        Box(
            Modifier.fillMaxSize().background(Palette.background).drawBehind {
                val center = Offset(size.width * 0.78f, size.height * 0.28f)
                drawRect(Brush.radialGradient(listOf(glow, Color.Transparent), center, size.width * 0.68f))
            }
        ) {
            Column(
                Modifier.align(Alignment.CenterEnd).padding(end = 48.dp).width(430.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                GuideRows.forEachIndexed { row, cells -> GuideRow(cells, onNow = row == LIT_ROW) }
            }
        }
    }

    /** One row of programme blocks; [cells] are their widths in tenths of the row, and the first is on now. */
    @Composable
    private fun GuideRow(cells: List<Int>, onNow: Boolean) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            cells.forEachIndexed { i, width ->
                val colour = when {
                    i == 0 && onNow -> Accent.copy(alpha = 0.55f)
                    i == 0 -> Palette.surface.copy(alpha = 0.9f)
                    else -> Palette.surface.copy(alpha = 0.6f)
                }
                Box(Modifier.weight(width.toFloat()).height(46.dp).clip(RoundedCornerShape(12.dp)).background(colour))
            }
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

        /** The Fire TV background's guide: programme widths per row, in tenths. */
        val GuideRows = listOf(listOf(4, 6), listOf(7, 3), listOf(3, 3, 4), listOf(5, 5), listOf(6, 4), listOf(2, 5, 3))
        const val LIT_ROW = 2
        val Accent = Color(AccentPalette.DEFAULT)
    }
}
