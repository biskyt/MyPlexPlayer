package com.biskyt.myplexplayer.ui.dvr

import com.biskyt.myplexplayer.core.plex.PlexAiring
import com.biskyt.myplexplayer.core.plex.PlexProgramme
import com.biskyt.myplexplayer.core.plex.PlexRecordingOption
import com.biskyt.myplexplayer.core.plex.PlexRecordingRule
import com.biskyt.myplexplayer.core.plex.PlexScheduledRecording
import com.biskyt.myplexplayer.data.RecordingMark
import com.biskyt.myplexplayer.data.RecordingState
import org.junit.Assert.assertEquals
import org.junit.Test

/** What the DVR tab lists in each section, by each airing's status. */
class DvrListsTest {
    private fun airing(key: String, status: String) = PlexScheduledRecording(
        key = key,
        status = status,
        metadata = PlexProgramme(guid = "plex://episode/$key", airings = listOf(PlexAiring(100, 200, "ch-1")))
    )

    @Test
    fun `not recorded lists those that failed or are held back, not one cancelled, done or still being processed`() {
        val statuses = listOf("scheduled", "inprogress", "postprocessing", "complete", "error", "cancelled", "inactive", "paused")
        val lists = DvrLists(RecordingState(scheduled = statuses.map { airing(it, it) }))
        assertEquals(listOf("inprogress"), lists.now.map { it.key })
        assertEquals(listOf("scheduled"), lists.comingUp.map { it.key })
        assertEquals(listOf("error", "inactive", "paused"), lists.problems.map { it.key })
    }

    @Test
    fun `other recordings are the rules for one programme with nothing scheduled, by title`() {
        fun rule(key: String, type: Int, title: String) = PlexRecordingRule(
            key = key,
            type = type,
            programmes = listOf(PlexProgramme(guid = "plex://episode/$key", title = "Episode $key", grandparentTitle = title))
        )
        val waiting = rule("1", PlexRecordingOption.EPISODE, "Zebras")
        val alsoWaiting = rule("2", PlexRecordingOption.EPISODE, "Ants")
        val scheduled = rule("3", PlexRecordingOption.EPISODE, "Bees")
        val series = rule("4", PlexRecordingOption.SERIES, "Cats")
        val done = rule("5", PlexRecordingOption.EPISODE, "Moths")
        val grabs =
            listOf(airing("g3", "scheduled").copy(mediaSubscriptionID = "3"), airing("g5", "complete").copy(mediaSubscriptionID = "5"))
        val lists = DvrLists(RecordingState(listOf(waiting, alsoWaiting, scheduled, series, done), grabs))
        // Bees is under Coming up; Moths' one airing is done, so it waits for another.
        assertEquals(listOf("Ants", "Moths", "Zebras"), lists.others.map { it.subjectTitle })
    }

    @Test
    fun `every airing of a show set to record as a series is marked, as in Plex's guide, also with none scheduled`() {
        val series = PlexRecordingRule(
            key = "1",
            type = PlexRecordingOption.SERIES,
            shows = listOf(PlexProgramme(guid = "plex://show/s1", title = "Repeats"))
        )
        val state = RecordingState(listOf(series))
        assertEquals(RecordingMark.SERIES, state.mark("plex://episode/e1", "plex://show/s1", 100_000))
        assertEquals(null, state.mark("plex://episode/e2", "plex://show/other", 100_000))
        assertEquals(null, state.mark("plex://episode/e3", null, 100_000))
    }
}
