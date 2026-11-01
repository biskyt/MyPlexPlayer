package com.biskyt.myplexplayer.ui

import androidx.compose.runtime.remember
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.core.app.ApplicationProvider
import com.biskyt.myplexplayer.testutil.FakePlex
import com.biskyt.myplexplayer.testutil.Navigators
import com.biskyt.myplexplayer.testutil.TestApp
import com.biskyt.myplexplayer.testutil.addSyncedHomePlex
import com.biskyt.myplexplayer.testutil.createUiComposeRule
import com.biskyt.myplexplayer.testutil.settle
import com.biskyt.myplexplayer.testutil.waitForText
import com.biskyt.myplexplayer.ui.common.formatDate
import com.biskyt.myplexplayer.ui.theme.MyPlexTheme
import java.time.LocalDate
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

/** An episode's options on the series page: "Episode description", for an episode with a description or a date. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestApp::class, qualifiers = "+w960dp-h540dp-tvdpi")
class EpisodeDescriptionUiTest {
    @get:Rule val compose = createUiComposeRule()

    @get:Rule(order = Navigators.ORDER)
    val navigators = Navigators()

    private val plex = FakePlex()
    private val app = ApplicationProvider.getApplicationContext<TestApp>()
    private var source = 0L

    @Before
    fun setUp() = runBlocking {
        plex.episodes = listOf(
            FakePlex.episode("21", "Test Show", "211", 1, 1, "Pilot", summary = "First.", aired = "2014-06-26"),
            FakePlex.episode("21", "Test Show", "212", 1, 2, "Second"),
            FakePlex.episode("21", "Test Show", "213", 1, 3, "Third", aired = "2014-07-03")
        )
        source = app.container.addSyncedHomePlex(plex)
    }

    @After
    fun tearDown() = plex.close()

    /** The series page, with the options of its episode called [name] open. */
    private fun options(name: String) {
        val navigator = navigators.new(Screen.SeriesDetails(source, 21))
        compose.setContent { MyPlexTheme { NavHost(remember { navigator }) { ScreenContent(it) } } }
        compose.waitForText(name)
        compose.onAllNodes(hasText(name) and hasClickAction())[0].performSemanticsAction(SemanticsActions.OnLongClick)
        compose.waitForText("Mark as watched")
    }

    private fun count(text: String) = compose.onAllNodes(hasText(text)).fetchSemanticsNodes().size

    @Test
    fun `an episode's description says what it is about and when it was first shown`() {
        options("Pilot")
        // The page behind shows these too: the panel adds its own.
        val texts = listOf("Test Show", "First.")
        val before = texts.map(::count)
        compose.onNode(hasText("Episode description") and hasClickAction()).performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForText("First shown ${formatDate(LocalDate.of(2014, 6, 26))}")
        compose.settle()
        assertEquals(before.map { it + 1 }, texts.map(::count))
    }

    @Test
    fun `an episode with only a date has a description too`() {
        options("Third")
        compose.onNode(hasText("Episode description") and hasClickAction()).performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForText("First shown ${formatDate(LocalDate.of(2014, 7, 3))}")
    }

    @Test
    fun `an episode with no description or date has no Episode description item`() {
        options("Second")
        compose.settle()
        assertTrue("no Episode description item", count("Episode description") == 0)
    }
}
