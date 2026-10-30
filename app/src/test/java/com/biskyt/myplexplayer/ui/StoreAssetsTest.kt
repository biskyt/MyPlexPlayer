package com.biskyt.myplexplayer.ui

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
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.test.core.app.ApplicationProvider
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.SingletonImageLoader
import coil3.annotation.DelicateCoilApi
import com.biskyt.myplexplayer.AppText
import com.biskyt.myplexplayer.InAppLanguage
import com.biskyt.myplexplayer.R
import com.biskyt.myplexplayer.appString
import com.biskyt.myplexplayer.core.ui.AccentPalette
import com.biskyt.myplexplayer.data.ChannelFilter
import com.biskyt.myplexplayer.data.categoryLabel
import com.biskyt.myplexplayer.testutil.FakePlex
import com.biskyt.myplexplayer.testutil.Navigators
import com.biskyt.myplexplayer.testutil.TestApp
import com.biskyt.myplexplayer.testutil.addSyncedHomePlex
import com.biskyt.myplexplayer.testutil.createUiComposeRule
import com.biskyt.myplexplayer.testutil.plexChannel
import com.biskyt.myplexplayer.ui.common.initials
import com.biskyt.myplexplayer.ui.theme.MyPlexTheme
import com.biskyt.myplexplayer.ui.theme.Palette
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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
 * repository root. Demo channels and programmes are invented: no real broadcaster or title appears. They come from a
 * fake Plex server, as a signed-in user's own would send them.
 *
 * xhdpi on a 960×540 dp screen gives the 1920×1080 pixels Play asks for in TV screenshots.
 *
 * With `-PstoreLanguage=<tag>` (one of AppText.languages) the screenshots are in that language, with some settings
 * pages too, in app/build/store-assets/<tag>/: for checking that the translated text fits.
 */
@OptIn(ExperimentalTestApi::class, DelicateCoilApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = TestApp::class, qualifiers = "+w960dp-h540dp-xhdpi")
class StoreAssetsTest {
    @get:Rule val compose = createUiComposeRule()

    @get:Rule(order = Navigators.ORDER)
    val navigators = Navigators()

    private val plex = FakePlex().apply {
        channels = CHANNELS.map { FakePlex.Channel(it.number, it.name, imageAddress("/logos/${it.logo}.png")) }
        programme = { i, n -> if (n == 0) CHANNELS[i].now else FakePlex.Programme(CHANNELS[i].next) }
        // The Blender Foundation's open movies, with their posters (CC BY; see demo-art/CREDITS.md), as the server
        // lists them: by title.
        movies = listOf(
            FakePlex.film(
                "805",
                "Big Buck Bunny",
                2008,
                addedAt = ADDED - 5,
                duration = 596_000,
                summary = BUNNY_PLOT,
                thumb = "/art/big-buck-bunny.jpg",
                art = "/art/big-buck-bunny-still.jpg"
            ),
            FakePlex.film("801", "Charge", 2022, addedAt = ADDED, duration = 240_000, thumb = "/art/charge.jpg"),
            FakePlex.film("802", "Cosmos Laundromat", 2015, addedAt = ADDED - 2, duration = 730_000, thumb = "/art/cosmos-laundromat.jpg"),
            FakePlex.film("806", "Elephants Dream", 2006, addedAt = ADDED - 6, duration = 654_000, thumb = "/art/elephants-dream.jpg"),
            FakePlex.film("804", "Sintel", 2010, addedAt = ADDED - 4, duration = 888_000, thumb = "/art/sintel.jpg"),
            FakePlex.film("803", "Tears of Steel", 2012, addedAt = ADDED - 3, duration = 734_000, thumb = "/art/tears-of-steel.jpg"),
        )
        // And their series: Caminandes, three shorts about Koro the llama.
        shows = listOf(
            with(
                FakePlex.show("901", "Caminandes", 2013, addedAt = ADDED - 1, summary = LLAMA_PLOT, thumb = "/art/caminandes.jpg")
            ) { JsonObject(this + ("art" to JsonPrimitive("/art/caminandes-still.jpg"))) }
        )
        episodes = listOf(
            Triple("Llama Drama", "Koro wants to cross the road. So does the traffic.", 90_000L),
            Triple("Gran Dillama", "The grass is greener beyond the electric fence.", 146_000L),
            Triple("Llamigos", "Winter, a berry bush, and a penguin called Oti.", 150_000L),
        ).mapIndexed { i, (title, plot, duration) ->
            val episode = FakePlex.episode("901", "Caminandes", "901${i + 1}", 1, i + 1, title, duration)
            // With their summary and their series' poster, as the server lists them.
            JsonObject(episode + mapOf("summary" to JsonPrimitive(plot), "grandparentThumb" to JsonPrimitive("/art/caminandes.jpg")))
        }
        images = { path -> javaClass.getResourceAsStream("/demo-art/${path.substringAfterLast('/')}")?.use { it.readBytes() } }
    }
    private val app = ApplicationProvider.getApplicationContext<TestApp>()
    private var playlistId = 0L
    private val language = System.getProperty("storeLanguage").orEmpty()
    private val out = if (language.isEmpty()) OUT else "build/store-assets/$language"

    @Before
    fun setUp() = runBlocking {
        assumeTrue("Run with -PstoreAssets", System.getProperty("storeAssets") == "true")
        require(language.isEmpty() || language in AppText.languages) { "-PstoreLanguage: one of ${AppText.languages}" }
        AppText.setLanguage(app, language)
        val c = app.container
        // Robolectric's native graphics can only decode images on the main thread.
        SingletonImageLoader.setUnsafe(app.newImageLoader(app).newBuilder().decoderCoroutineContext(Dispatchers.Main.immediate).build())
        playlistId = c.addSyncedHomePlex(plex)
        listOf("1", "3", "5", "7").forEach { c.channels.setFavourite(playlistId, plexChannel(it), true) }
        listOf("2", "6", "4").forEach { c.channels.markWatched(playlistId, plexChannel(it)) }
    }

    @After
    fun tearDown() {
        plex.close()
        SingletonImageLoader.reset()
        AppText.setLanguage(app, "")
    }

    /** Runs the main thread until [done]. */
    private fun idleUntil(timeoutMs: Long = 10_000, done: () -> Boolean) = compose.waitUntil(timeoutMs) {
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        done()
    }

    private fun waitFor(text: String) = idleUntil { compose.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty() }

    /** Initials shown in place of a logo or poster until it loads. */
    private val placeholders by lazy {
        (
            CHANNELS.map { it.name } +
                listOf("Big Buck Bunny", "Charge", "Cosmos Laundromat", "Elephants Dream", "Sintel", "Tears of Steel", "Caminandes")
            )
            .map { initials(it) }.toSet()
    }
    private val placeholder = SemanticsMatcher("logo or poster placeholder") { node ->
        node.config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text in placeholders }
    }

    private fun screenshot(name: String, start: Screen, ready: String, then: () -> Unit = {}) {
        val navigator = navigators.new(start)
        compose.setContent {
            // On the app's background, as the app paints it below every screen.
            InAppLanguage {
                MyPlexTheme {
                    Box(Modifier.fillMaxSize().background(Palette.background)) { NavHost(remember { navigator }) { ScreenContent(it) } }
                }
            }
        }
        runCatching { waitFor(ready) }.onFailure {
            compose.onRoot().captureRoboImage("build/store-assets/stuck-$name.png")
            throw AssertionError("'$ready' not shown on $name", it)
        }
        then()
        // Logos and posters load in the background: wait until none is still showing its initials.
        idleUntil(15_000) { compose.onAllNodes(placeholder).fetchSemanticsNodes().isEmpty() }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("$out/screenshots/$name.png")
        flatten("$out/screenshots/$name.png")
    }

    // Part-way through a film and a series (played on the server), as well as the channels.
    @Test fun home() {
        runBlocking {
            plex.watched += "9011"
            plex.viewOffset["804"] = 530_000
            plex.viewOffset["9012"] = 50_000
            plex.continueWatching = listOf("804", "9012")
            app.container.sync.refreshNow(playlistId).getOrThrow()
        }
        screenshot("1-home", Screen.Home, "Sintel")
    }

    @Test fun liveTv() = screenshot("2-live-tv", Screen.Live(ChannelFilter.All), "Evening Bulletin")

    @Test fun tvGuide() = screenshot("3-tv-guide", Screen.Live(ChannelFilter.All, guide = true), "World Tonight")

    @Test fun movies() = screenshot("5-movies", Screen.Movies(), "Elephants Dream")

    @Test fun movieDetails() = screenshot("7-movie-details", Screen.MovieDetails(playlistId, 805), "A giant, gentle rabbit")

    @Test fun seriesDetails() = screenshot("8-series-details", Screen.SeriesDetails(playlistId, 901), "Llamigos")

    // Not in the store listing: settings pages, whose long labels are the likeliest not to fit when translated.
    @Test fun settings() = translatedOnly { screenshot("9-settings", Screen.Settings, appString(R.string.settings_playlists_title)) }

    @Test fun playbackSettings() =
        translatedOnly { screenshot("10-playback-settings", Screen.PlaybackSettings, appString(R.string.settings_subtitle_language)) }

    @Test fun playlists() = translatedOnly { screenshot("11-playlists", Screen.Playlists, "Home Plex") }

    // A Plex server's channels are in one group, without a name of their own.
    @Test fun categories() = translatedOnly { screenshot("12-categories", Screen.Categories(playlistId), categoryLabel("")) }

    private fun translatedOnly(shot: () -> Unit) {
        assumeTrue("Run with -PstoreLanguage", language.isNotEmpty())
        shot()
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
            MyPlexTheme {
                CompositionLocalProvider(LocalDensity provides Density(2f, 1f)) {
                    Box(Modifier.requiredSize(width, height).testTag(name)) { content() }
                }
            }
        }
        compose.onNodeWithTag(name).captureRoboImage("$out/graphics/$name.png")
        // Play's icon may be transparent; everything else (Amazon's icons too) is without (24-bit).
        if (name != "icon-512") flatten("$out/graphics/$name.png")
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
                Text("Live TV and TV guide", color = Palette.text, fontSize = 15.sp, softWrap = false)
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

    /** "MyPlexPlayer" with "Plex" in the accent colour, as in the app's top bar. */
    @Composable
    private fun Wordmark(size: Int) {
        val style = MaterialTheme.typography.titleLarge.copy(fontSize = size.sp)
        Row {
            Text("My", style = style, color = Palette.text, softWrap = false)
            Text("Plex", style = style, color = Accent, softWrap = false)
            Text("Player", style = style, color = Palette.text, softWrap = false)
        }
    }

    private companion object {
        const val BUNNY_PLOT = "A giant, gentle rabbit's lazy day in the meadow is spoiled by three bullying rodents. " +
            "Big mistake: he decides to get even."
        const val LLAMA_PLOT = "Koro the llama will go to any lengths for greener grass in the Patagonian hills. " +
            "Three short comedies from the Blender Foundation."
        const val OUT = "../store"

        /** When the newest demo title was added (in seconds); the others a little before. */
        const val ADDED = 1_760_000_000L

        /** A demo channel: its number, name, logo (in demo-art/), what's on now and the title of what's on after. */
        class DemoChannel(val number: String, val name: String, val logo: String, val now: FakePlex.Programme, val next: String)

        val CHANNELS = listOf(
            DemoChannel(
                "1",
                "Riverside One HD",
                "one",
                FakePlex.Programme("Spring Beds", "The Garden Planners", 3, 4, "Two neighbours turn a bare yard into a summer garden."),
                "Weekend Kitchen"
            ),
            DemoChannel(
                "2",
                "Northbridge News",
                "news",
                FakePlex.Programme("Late Edition", "Evening Bulletin", summary = "The day's main stories, with weather and sport."),
                "World Tonight"
            ),
            DemoChannel(
                "3",
                "Harbour Sport 1",
                "sport1",
                FakePlex.Programme("Semi-final", "Coastal Cup: Live", summary = "The semi-final, live from the harbour stadium."),
                "Coastal Cup: Reaction"
            ),
            DemoChannel(
                "4",
                "Harbour Sport 2",
                "sport2",
                FakePlex.Programme("The Mountain Stage", "Cycling Highlights", summary = "The best of the mountain stage."),
                "Track Day"
            ),
            DemoChannel(
                "5",
                "Little Sky Kids",
                "kids",
                FakePlex.Programme("The Park Pond", "Paper Boats", 1, 6, "Mia and Leo build a fleet for the park pond."),
                "Star Painters"
            ),
            DemoChannel(
                "6",
                "Lantern Drama",
                "drama",
                FakePlex.Programme("Storm Night", "The Lighthouse Keeper", 2, 1, "A storm cuts the island off for the night."),
                "Harbour Lights"
            ),
            DemoChannel(
                "7",
                "Atlas Documentary",
                "docs",
                FakePlex.Programme("Salmon Run", "Wild Rivers", 1, 3, "Following salmon upstream through the autumn."),
                "Night Skies"
            ),
            DemoChannel(
                "8",
                "Meadow Comedy",
                "comedy",
                FakePlex.Programme("The Milk", "Flat Share", 4, 2, "Nobody admits to finishing the milk."),
                "Open Mic"
            ),
        )

        /** The Fire TV background's guide: programme widths per row, in tenths. */
        val GuideRows = listOf(listOf(4, 6), listOf(7, 3), listOf(3, 3, 4), listOf(5, 5), listOf(6, 4), listOf(2, 5, 3))
        const val LIT_ROW = 2
        val Accent = Color(AccentPalette.DEFAULT)
    }
}
