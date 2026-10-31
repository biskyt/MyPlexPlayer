package com.biskyt.myplexplayer.ui.dvr

import com.biskyt.myplexplayer.core.plex.PlexAiring
import com.biskyt.myplexplayer.core.plex.PlexProgramme
import com.biskyt.myplexplayer.core.plex.PlexScheduledRecording
import com.biskyt.myplexplayer.data.RecordingState
import org.junit.Assert.assertEquals
import org.junit.Test

/** What the DVR tab lists in each section, by each airing's status. */
class DvrListsTest {
    private fun airing(key: String, status: String) = PlexScheduledRecording(
        key = key,
        status = status,
        programme = PlexProgramme(guid = "plex://episode/$key", airings = listOf(PlexAiring(100, 200, "ch-1")))
    )

    @Test
    fun `not recorded lists those that failed or are held back, not one cancelled, done or still being processed`() {
        val statuses = listOf("scheduled", "inprogress", "postprocessing", "complete", "error", "cancelled", "inactive", "paused")
        val lists = DvrLists(RecordingState(scheduled = statuses.map { airing(it, it) }))
        assertEquals(listOf("inprogress"), lists.now.map { it.key })
        assertEquals(listOf("scheduled"), lists.comingUp.map { it.key })
        assertEquals(listOf("error", "inactive", "paused"), lists.problems.map { it.key })
    }
}
