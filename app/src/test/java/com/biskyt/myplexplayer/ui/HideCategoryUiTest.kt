package com.biskyt.myplexplayer.ui

import androidx.compose.runtime.remember
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.core.app.ApplicationProvider
import com.biskyt.myplexplayer.data.ChannelFilter
import com.biskyt.myplexplayer.data.db.CAT_LIVE
import com.biskyt.myplexplayer.testutil.FakeProvider
import com.biskyt.myplexplayer.testutil.Navigators
import com.biskyt.myplexplayer.testutil.TestApp
import com.biskyt.myplexplayer.testutil.addHomeTv
import com.biskyt.myplexplayer.testutil.createUiComposeRule
import com.biskyt.myplexplayer.testutil.focusedText
import com.biskyt.myplexplayer.testutil.waitAdvancingClock
import com.biskyt.myplexplayer.testutil.waitForText
import com.biskyt.myplexplayer.ui.theme.MyPlexTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Hiding a category from the column keeps the place: the one after it (or before, for the last) is chosen. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestApp::class, qualifiers = "+w960dp-h540dp-tvdpi")
class HideCategoryUiTest {
    @get:Rule val compose = createUiComposeRule()

    @get:Rule(order = Navigators.ORDER)
    val navigators = Navigators()

    private val provider = FakeProvider()
    private val app = ApplicationProvider.getApplicationContext<TestApp>()
    private var playlistId = 0L

    /** The playlist's live categories, as the column lists them. */
    private lateinit var shown: List<String>

    @Before
    fun setUp() = runBlocking {
        playlistId = app.container.addHomeTv(provider)
        shown = app.container.db.categories().observeIncluded(playlistId, CAT_LIVE).first().map { it.categoryId }
    }

    @After
    fun tearDown() = provider.close()

    private fun label(id: String) = provider.categories.first { it.first == id }.second

    /** Opens Live TV on [id] and hides it from its options in the column. */
    private fun hide(id: String) {
        val navigator = navigators.new(Screen.Live(ChannelFilter.Category(id)))
        compose.setContent { MyPlexTheme { NavHost(remember { navigator }) { ScreenContent(it) } } }
        compose.waitForText(label(id), stepMs = 50)
        compose.onAllNodes(hasText(label(id)) and hasClickAction())[0].performSemanticsAction(SemanticsActions.OnLongClick)
        compose.waitForText("Hide category", stepMs = 50)
        compose.onNode(hasText("Hide category")).performSemanticsAction(SemanticsActions.OnClick)
    }

    /** [hidden] has gone, and [label] is the category shown (in the column and above its list), with focus. */
    private fun chosen(label: String, hidden: String) = compose.waitAdvancingClock(stepMs = 50) {
        compose.onAllNodes(hasText(label(hidden))).fetchSemanticsNodes().isEmpty() &&
            compose.onAllNodes(hasText(label)).fetchSemanticsNodes().size >= 2 &&
            compose.focusedText().contains(label)
    }

    @Test
    fun `hiding a category chooses the one after it`() {
        hide(shown.first())
        chosen(label(shown[1]), hidden = shown.first())
    }

    @Test
    fun `hiding the last category chooses the one before it`() {
        hide(shown.last())
        chosen(label(shown[shown.size - 2]), hidden = shown.last())
    }
}
