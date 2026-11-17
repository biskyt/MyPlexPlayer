package com.biskyt.myplexplayer.ui

import androidx.compose.runtime.remember
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import com.biskyt.myplexplayer.testutil.FakeTmdb
import com.biskyt.myplexplayer.testutil.Navigators
import com.biskyt.myplexplayer.testutil.TestApp
import com.biskyt.myplexplayer.testutil.createUiComposeRule
import com.biskyt.myplexplayer.testutil.waitAdvancingClock
import com.biskyt.myplexplayer.testutil.waitForText
import com.biskyt.myplexplayer.ui.settings.TmdbCheck
import com.biskyt.myplexplayer.ui.settings.TmdbSettingsViewModel
import com.biskyt.myplexplayer.ui.theme.MyPlexTheme
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Settings › TMDB: off by default, and a key is saved only when TMDB does not refuse it (the owner, 2026-11-17: the
 * user's own key adds "More like this").
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestApp::class, qualifiers = "+w960dp-h540dp-tvdpi")
class TmdbSettingsUiTest {
    @get:Rule val compose = createUiComposeRule()

    @get:Rule(order = Navigators.ORDER)
    val navigators = Navigators()

    private val tmdb = FakeTmdb()
    private val app = ApplicationProvider.getApplicationContext<TestApp>()

    @After
    fun tearDown() = tmdb.close()

    private fun savedKey() = runBlocking { app.container.settings.current().tmdbKey }

    @Test
    fun `the row says TMDB is off until a key is added`() {
        val navigator = navigators.new(Screen.Settings)
        compose.setContent { MyPlexTheme { NavHost(remember { navigator }) { ScreenContent(it) } } }
        compose.waitForText("Playback")
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("TMDB"))
        compose.waitForText("Off. Add your own free TMDB key to show “More like this” on film and series pages.")
    }

    @Test
    fun `a key TMDB accepts is saved, one it refuses is not`() {
        tmdb.use(app.container, key = null)
        val vm = TmdbSettingsViewModel(app.container)
        vm.save("wrong-key")
        compose.waitAdvancingClock { vm.check == TmdbCheck.REFUSED }
        assertEquals("", savedKey())
        // A key pasted with a space and a line end is saved without them.
        vm.save(" ${FakeTmdb.TEST_KEY}\n")
        compose.waitAdvancingClock { vm.check == TmdbCheck.SAVED }
        assertEquals(FakeTmdb.TEST_KEY, savedKey())
    }
}
