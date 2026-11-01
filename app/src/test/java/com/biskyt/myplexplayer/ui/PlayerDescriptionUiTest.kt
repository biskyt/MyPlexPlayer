package com.biskyt.myplexplayer.ui

import androidx.compose.runtime.remember
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performSemanticsAction
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import com.biskyt.myplexplayer.testutil.FakePlex
import com.biskyt.myplexplayer.testutil.Navigators
import com.biskyt.myplexplayer.testutil.TestApp
import com.biskyt.myplexplayer.testutil.addSyncedHomePlex
import com.biskyt.myplexplayer.testutil.createUiComposeRule
import com.biskyt.myplexplayer.testutil.waitAdvancingClock
import com.biskyt.myplexplayer.testutil.waitForText
import com.biskyt.myplexplayer.ui.common.formatDate
import com.biskyt.myplexplayer.ui.player.PlaybackViewModel
import com.biskyt.myplexplayer.ui.player.VodPlayerViewModel
import com.biskyt.myplexplayer.ui.theme.MyPlexTheme
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** An episode's description, from the player's More. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestApp::class, qualifiers = "+w960dp-h540dp-tvdpi")
class PlayerDescriptionUiTest {
    @get:Rule val compose = createUiComposeRule()

    @get:Rule(order = Navigators.ORDER)
    val navigators = Navigators()

    private val plex = FakePlex()
    private val app = ApplicationProvider.getApplicationContext<TestApp>()
    private lateinit var vm: VodPlayerViewModel

    @After
    fun tearDown() = plex.close()

    /** Test Show's first episode (211), with [summary] as its description and first shown on [aired], playing. */
    private fun playPilot(summary: String?, aired: String? = null) {
        plex.episodes = listOf(
            FakePlex.episode("21", "Test Show", "211", 1, 1, "Pilot", summary = summary, aired = aired),
            FakePlex.episode("21", "Test Show", "212", 1, 2, "Second")
        )
        val source = runBlocking { app.container.addSyncedHomePlex(plex) }
        val navigator = navigators.new(Screen.VodPlayer(source, 211, seriesId = 21, fromStart = true))
        compose.setContent { MyPlexTheme { NavHost(remember { navigator }) { ScreenContent(it) } } }
        vm = ViewModelProvider(navigator.current)[VodPlayerViewModel::class.java]
        compose.waitAdvancingClock { vm.engine?.player?.currentMediaItem != null }
    }

    @Test
    fun `More shows the episode's description over the player, with when it was first shown`() {
        playPilot("The crew meet for the first time.", aired = "2014-06-26")
        compose.runOnIdle { vm.sheet = PlaybackViewModel.Sheet.MORE }
        compose.waitForText("Episode description")
        compose.onNode(hasText("Episode description") and hasClickAction()).performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForText("The crew meet for the first time.")
        compose.waitForText("First shown ${formatDate(LocalDate.of(2014, 6, 26))}")
        compose.waitForText("Test Show")
        assertTrue("still playing", vm.engine?.player?.playWhenReady == true)
    }

    @Test
    fun `an episode with only a date has the item too`() {
        playPilot(null, aired = "2014-07-03")
        assertTrue(compose.runOnIdle { vm.menuActions().any { it.label == "Episode description" } })
    }

    @Test
    fun `an episode with no description or date has no Episode description item`() {
        playPilot(null)
        assertFalse(compose.runOnIdle { vm.menuActions().any { it.label == "Episode description" } })
    }
}
