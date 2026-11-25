package com.biskyt.myplexplayer.ui.phone

import android.content.pm.PackageManager
import androidx.compose.runtime.remember
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import com.biskyt.myplexplayer.testutil.BoardTitles
import com.biskyt.myplexplayer.testutil.FakeCredit
import com.biskyt.myplexplayer.testutil.FakePerson
import com.biskyt.myplexplayer.testutil.FakePlex
import com.biskyt.myplexplayer.testutil.FakePlex.Companion.guidId
import com.biskyt.myplexplayer.testutil.Navigators
import com.biskyt.myplexplayer.testutil.OnAppBackground
import com.biskyt.myplexplayer.testutil.TestApp
import com.biskyt.myplexplayer.testutil.addSyncedHomePlex
import com.biskyt.myplexplayer.testutil.createUiComposeRule
import com.biskyt.myplexplayer.testutil.idleFrames
import com.biskyt.myplexplayer.testutil.waitAdvancingClock
import com.biskyt.myplexplayer.testutil.waitForText
import com.biskyt.myplexplayer.ui.NavHost
import com.biskyt.myplexplayer.ui.Navigator
import com.biskyt.myplexplayer.ui.Screen
import com.biskyt.myplexplayer.ui.ScreenContent
import com.biskyt.myplexplayer.ui.common.Tab
import com.biskyt.myplexplayer.ui.theme.MyPlexTheme
import com.biskyt.myplexplayer.ui.theme.autoFindsTv
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * DVR and a person's page on a phone (the owner, 2026-11-25): what a tap and a long press do, and screenshots (also
 * unfolded) to look at.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestApp::class, qualifiers = "w390dp-h844dp-xxhdpi")
class PhoneDvrAndPersonTest {
    @get:Rule val compose = createUiComposeRule()

    @get:Rule(order = Navigators.ORDER)
    val navigators = Navigators()

    private val plex = FakePlex()
    private val app = ApplicationProvider.getApplicationContext<TestApp>()
    private var source = 0L
    private lateinit var navigator: Navigator

    @Before
    fun setUp() {
        shadowOf(app.packageManager).setSystemFeature(PackageManager.FEATURE_TOUCHSCREEN, true)
        autoFindsTv = { false } // the phone layout (TestApp sets the TV's)
        plex.images = BoardTitles::art
        plex.movies = listOf(BoardTitles.film(), FakePlex.film("12", "Sintel", 2010))
        plex.shows = listOf(BoardTitles.show())
        plex.episodes = BoardTitles.episodes()
        plex.people = listOf(TOM, KOFI)
        // Show 0's series records now on channel One; one programme is set to record; a film was recorded.
        plex.rules += FakePlex.Rule("1", 2, "plex://show/Show-0", mapOf("onlyNewAirings" to "1"))
        plex.rules += FakePlex.Rule("2", 4, "plex://episode/ch-2-1", emptyMap())
        plex.recordingNow += "plex://episode/ch-1-0"
        plex.recorded += FakePlex.Recorded("The Long Harbour", System.currentTimeMillis() / 1000 - 3_600, "/library/metadata/51")
        source = runBlocking { app.container.addSyncedHomePlex(plex) }
        runBlocking { app.container.recordings.refresh(source) }
    }

    @After
    fun tearDown() = plex.close()

    private fun show(screen: Screen) {
        navigator = navigators.new(screen)
        compose.setContent { MyPlexTheme { OnAppBackground { NavHost(remember { navigator }) { ScreenContent(it) } } } }
    }

    private fun dvr() {
        show(Screen.Dvr)
        compose.waitForText("Recently recorded", stepMs = 50)
        compose.waitForText("The Long Harbour", stepMs = 50)
    }

    private fun person() {
        show(
            Screen.Person(
                source,
                BoardTitles.CAST,
                BoardTitles.tagKey(BoardTitles.CAST),
                "Tom Hale",
                null,
                "Elias",
                "The Long Harbour",
                Tab.MOVIES
            )
        )
        compose.waitForText("In your library", stepMs = 50)
    }

    @Test
    fun `a tap on a recording plays it`() {
        dvr()
        compose.onNode(hasText("The Long Harbour") and hasClickAction()).performSemanticsAction(SemanticsActions.OnClick)
        compose.waitAdvancingClock(stepMs = 50) { navigator.current.screen is Screen.VodPlayer }
        assertEquals(Screen.VodPlayer(source, BoardTitles.FILM.toLong()), navigator.current.screen)
    }

    @Test
    fun `a long press on a recording opens its options in a sheet`() {
        dvr()
        compose.onNode(hasText("The Long Harbour") and hasClickAction()).performTouchInput { longClick() }
        compose.waitForText("More info", stepMs = 50)
        assertEquals("a window of its own", 2, compose.onAllNodes(isRoot()).fetchSemanticsNodes().size)
        assertEquals(Screen.Dvr, navigator.current.screen)
    }

    @Test
    fun `a poster in In your library opens its page`() {
        person()
        compose.onNode(hasText("Coastline") and hasClickAction()).performSemanticsAction(SemanticsActions.OnClick)
        compose.waitAdvancingClock(stepMs = 50) { navigator.current.screen == Screen.SeriesDetails(source, BoardTitles.SHOW.toLong()) }
    }

    @Test
    fun `cancelling a recording asks first, and nothing is cancelled until the user says yes`() {
        dvr()
        compose.onNode(hasText("Film 1") and hasClickAction()).performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForText("Cancel this recording", stepMs = 50)
        compose.onNode(hasText("Cancel this recording") and hasClickAction()).performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForText("Cancel the recording of “Film 1”?", stepMs = 50)
        assertTrue(plex.rules.any { it.key == "2" })
        compose.onNode(hasText("Keep it") and hasClickAction()).performSemanticsAction(SemanticsActions.OnClick)
        compose.idleFrames()
        assertTrue(plex.rules.any { it.key == "2" })
    }

    @Test
    fun `a short biography has no Full biography`() {
        show(Screen.Person(source, KOFI.id, KOFI.tagKey, KOFI.name, null, null, "The Long Harbour", Tab.MOVIES))
        compose.waitForText("In your library", stepMs = 50)
        compose.waitForText(KOFI.summary!!, stepMs = 50)
        compose.idleFrames()
        assertTrue(compose.onAllNodes(hasText("Full biography")).fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun `the full biography's sheet has no part with focus`() {
        person()
        compose.onNode(hasText("Full biography") and hasClickAction()).performSemanticsAction(SemanticsActions.OnClick)
        compose.waitAdvancingClock(stepMs = 50) { compose.onAllNodes(isRoot()).fetchSemanticsNodes().size == 2 }
        compose.idleFrames()
        // On a touch layout a sheet scrolls by touch: no paragraph takes focus, so none shows a dark fill.
        assertTrue(compose.onAllNodes(isFocused()).fetchSemanticsNodes().none { n -> n.config.toString().contains("Tom Hale is") })
    }

    private fun capture(name: String) {
        repeat(SETTLE_STEPS) { compose.waitAdvancingClock(stepMs = 50) { true } }
        compose.idleFrames()
        compose.onRoot().captureRoboImage("screenshots/phone/$name.png")
    }

    @Test
    fun `DVR on a phone`() {
        dvr()
        capture("dvr-phone")
    }

    @Test
    @Config(qualifiers = "w840dp-h720dp-xhdpi")
    fun `DVR unfolded`() {
        dvr()
        capture("dvr-unfolded")
    }

    @Test
    fun `a person's page on a phone`() {
        person()
        capture("person-phone")
    }

    @Test
    @Config(qualifiers = "w840dp-h720dp-xhdpi")
    fun `a person's page unfolded`() {
        person()
        capture("person-unfolded")
    }

    private companion object {
        const val SETTLE_STEPS = 10

        /** Acts in the board's film and show, and in a title that is not on the server. */
        val TOM = FakePerson(
            BoardTitles.CAST,
            BoardTitles.tagKey(BoardTitles.CAST),
            "Tom Hale",
            summary = "Tom Hale is an English actor and writer. He trained at a drama school in Glasgow and worked in theatre " +
                "for ten years before his first leading role on television.",
            bornAt = "1984-05-03",
            birthPlace = "Leeds, England",
            creditTypes = mapOf("actor" to 3),
            credits = mapOf(
                "actor" to listOf(
                    FakeCredit(guidId(BoardTitles.FILM), "The Long Harbour", year = 2021, role = "Elias"),
                    FakeCredit(guidId(BoardTitles.SHOW), "Coastline", show = true, year = 2021, role = "Rose"),
                    FakeCredit("d-1", "Glass Coast", year = 2022, role = "Iona"),
                )
            ),
            media = listOf(BoardTitles.FILM, BoardTitles.SHOW)
        )

        /** In the film, with a biography of one short line. */
        val KOFI = FakePerson(
            BoardTitles.CAST + 2,
            BoardTitles.tagKey(BoardTitles.CAST + 2),
            "Kofi Mensah",
            summary = "An actor from Accra.",
            creditTypes = mapOf("actor" to 1),
            credits = mapOf("actor" to listOf(FakeCredit(guidId(BoardTitles.FILM), "The Long Harbour", year = 2021, role = "Sam"))),
            media = listOf(BoardTitles.FILM)
        )
    }
}
