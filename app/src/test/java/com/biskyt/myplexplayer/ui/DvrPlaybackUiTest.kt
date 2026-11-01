package com.biskyt.myplexplayer.ui

import androidx.compose.runtime.remember
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performSemanticsAction
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import com.biskyt.myplexplayer.data.ChannelFilter
import com.biskyt.myplexplayer.testutil.FakePlex
import com.biskyt.myplexplayer.testutil.Navigators
import com.biskyt.myplexplayer.testutil.TestApp
import com.biskyt.myplexplayer.testutil.addSyncedHomePlex
import com.biskyt.myplexplayer.testutil.createUiComposeRule
import com.biskyt.myplexplayer.testutil.focusedText
import com.biskyt.myplexplayer.testutil.idleFrames
import com.biskyt.myplexplayer.testutil.plexChannel
import com.biskyt.myplexplayer.testutil.waitAdvancingClock
import com.biskyt.myplexplayer.testutil.waitForText
import com.biskyt.myplexplayer.ui.common.ItemAction
import com.biskyt.myplexplayer.ui.player.PlayerViewModel
import com.biskyt.myplexplayer.ui.theme.MyPlexTheme
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Watching recordings: a channel that records now offers, once its picture starts, to play from the recording's start
 * (only when the capture buffer holds more than a minute), with no second tune; and the recently recorded ones,
 * played from the library.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestApp::class, qualifiers = "+w960dp-h540dp-tvdpi")
class DvrPlaybackUiTest {
    @get:Rule val compose = createUiComposeRule()

    @get:Rule(order = Navigators.ORDER)
    val navigators = Navigators()

    private val plex = FakePlex()
    private val app = ApplicationProvider.getApplicationContext<TestApp>()
    private var source = 0L
    private lateinit var nav: Navigator
    private lateinit var vm: PlayerViewModel

    @Before
    fun setUp() {
        source = runBlocking { app.container.addSyncedHomePlex(plex) }
    }

    @After
    fun tearDown() = plex.close()

    private fun show(screen: Screen) {
        nav = navigators.new(screen)
        compose.setContent { MyPlexTheme { NavHost(remember { nav }) { ScreenContent(it) } } }
    }

    /** Show 0's series, recording now on channel One. */
    private fun recordingNow() {
        plex.rules += FakePlex.Rule("1", 2, "plex://show/Show-0", mapOf("onlyNewAirings" to "1"))
        plex.recordingNow += "plex://episode/ch-1-0"
    }

    private fun click(text: String) =
        compose.onAllNodes(hasText(text, substring = true) and hasClickAction())[0].performSemanticsAction(SemanticsActions.OnClick)

    private fun shown(text: String) = compose.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty()

    /**
     * Channel One played (Show 0 recording on it when [recording]), its capture buffer holding [heldS] seconds, until
     * its picture starts.
     */
    private fun play(heldS: Double, recording: Boolean = true) {
        plex.bufferHeldS = heldS
        if (recording) recordingNow()
        runBlocking { app.container.recordings.refresh(source) }
        show(Screen.Player(source, plexChannel("1"), ChannelFilter.All))
        vm = ViewModelProvider(nav.current)[PlayerViewModel::class.java]
        compose.waitAdvancingClock(50) { plex.liveOffsets.isNotEmpty() }
        compose.runOnIdle { vm.pictureStarted() }
        compose.idleFrames()
    }

    private fun menuOffers(label: String) = compose.runOnIdle { vm.menuActions().any { it.label == label } }

    private fun menuOffersStart() = menuOffers("Watch from start")

    @Test
    fun `a channel recording now, with more than a minute held, offers its start, which plays with no second tune`() {
        play(heldS = 600.0)
        compose.waitForText("Watch from start", stepMs = 50)
        assertTrue("live has the focus", compose.focusedText().contains("Watch live"))
        click("Watch from start")
        compose.waitAdvancingClock(50) { plex.liveOffsets.size == 2 && menuOffers("Watch live") }
        assertEquals(listOf(null, "0"), plex.liveOffsets.toList())
        assertEquals(listOf("ch-1"), plex.tuned.toList())
        assertTrue("the session isn't stopped", plex.stopped.isEmpty() && plex.liveStopped.isEmpty())
        assertTrue("More goes back to live", !menuOffersStart())
    }

    @Test
    fun `from the start, More's Watch live goes back to live on the same session`() {
        play(heldS = 600.0)
        compose.waitForText("Watch from start", stepMs = 50)
        click("Watch from start")
        compose.waitAdvancingClock(50) { plex.liveOffsets.size == 2 && menuOffers("Watch live") }
        compose.runOnIdle { vm.menuActions().first { it.label == "Watch live" }.let { (it as ItemAction.Run).run() } }
        // The server has the request before the player has its answer: the menu changes with the stream.
        compose.waitAdvancingClock(50) { plex.liveOffsets.size == 3 && menuOffersStart() }
        assertEquals(listOf(null, "0", null), plex.liveOffsets.toList())
        assertEquals(listOf("ch-1"), plex.tuned.toList())
        assertTrue("the session isn't stopped", plex.stopped.isEmpty() && plex.liveStopped.isEmpty())
    }

    @Test
    fun `Watch live carries on live, and More still offers the start`() {
        play(heldS = 600.0)
        compose.waitForText("Watch live", stepMs = 50)
        click("Watch live")
        compose.waitAdvancingClock(50) { !shown("Watch from start") }
        compose.idleFrames()
        assertEquals(listOf<String?>(null), plex.liveOffsets.toList())
        assertTrue(menuOffersStart())
    }

    @Test
    fun `a minute or less held offers no start`() {
        play(heldS = 60.0)
        assertTrue(!shown("Watch from start") && !shown("Watch live"))
        assertTrue(!menuOffersStart())
    }

    @Test
    fun `a channel not recording offers no start`() {
        play(heldS = 600.0, recording = false)
        assertTrue(!shown("Watch from start") && !shown("Watch live"))
        assertTrue(!menuOffersStart())
    }

    @Test
    fun `recently recorded lists those still in the library, newest first, and OK plays one`() {
        val now = System.currentTimeMillis() / 1000
        plex.recorded += FakePlex.Recorded("Big Buck Bunny", now - 3 * 86_400, "/library/metadata/11")
        plex.recorded += FakePlex.Recorded("Pilot", now - 86_400, "/library/metadata/211", show = "Test Show")
        plex.recorded += FakePlex.Recorded("Deleted Film", now - 3_600, linkedKey = null)
        show(Screen.Dvr)
        compose.waitForText("Recently recorded", stepMs = 50)
        compose.waitForText("Big Buck Bunny", stepMs = 50)
        assertTrue(!shown("Deleted Film"))
        fun top(text: String) = compose.onAllNodes(hasText(text) and hasClickAction()).fetchSemanticsNodes()[0].boundsInRoot.top
        assertTrue("newest first", top("Test Show") < top("Big Buck Bunny"))
        // The focus starts on the first row.
        compose.waitAdvancingClock(50) { compose.focusedText().contains("Test Show") }

        click("Test Show")
        compose.waitAdvancingClock(50) { nav.current.screen is Screen.VodPlayer }
        assertEquals(Screen.VodPlayer(source, 211, seriesId = 21), nav.current.screen)
    }

    @Test
    fun `a film recorded since the last sync is synced first, then plays`() {
        plex.movies = plex.movies + FakePlex.film("31", "Recorded Film")
        plex.recorded += FakePlex.Recorded("Recorded Film", System.currentTimeMillis() / 1000 - 3_600, "/library/metadata/31")
        show(Screen.Dvr)
        compose.waitForText("Recorded Film", stepMs = 50)
        click("Recorded Film")
        compose.waitAdvancingClock(50) { nav.current.screen is Screen.VodPlayer }
        assertEquals(Screen.VodPlayer(source, 31), nav.current.screen)
        assertTrue("in the app's library before it plays", runBlocking { app.container.vod.movie(source, 31) } != null)
    }

    @Test
    fun `holding OK on a recently recorded film opens its options, which open its page`() {
        plex.recorded += FakePlex.Recorded("Big Buck Bunny", System.currentTimeMillis() / 1000 - 3_600, "/library/metadata/11")
        show(Screen.Dvr)
        compose.waitForText("Big Buck Bunny", stepMs = 50)
        compose.onAllNodes(hasText("Big Buck Bunny") and hasClickAction())[0].performSemanticsAction(SemanticsActions.OnLongClick)
        compose.waitForText("More info", stepMs = 50)
        click("More info")
        compose.waitAdvancingClock(50) { nav.current.screen is Screen.MovieDetails }
        assertEquals(Screen.MovieDetails(source, 11), nav.current.screen)
    }
}
