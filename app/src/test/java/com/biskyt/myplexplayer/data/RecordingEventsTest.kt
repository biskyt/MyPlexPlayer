package com.biskyt.myplexplayer.data

import com.biskyt.myplexplayer.core.plex.PlexAiring
import com.biskyt.myplexplayer.core.plex.PlexProgramme
import com.biskyt.myplexplayer.core.plex.PlexScheduledRecording
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** When a recording starts and stops, for its toast, and when to look again. */
class RecordingEventsTest {
    /** Airing [key] at [times] (seconds), on [channel]. */
    private fun airing(key: String, status: String, times: LongRange, show: String? = "The Harbour", channel: String = "ch-1") =
        PlexScheduledRecording(
            key = key,
            status = status,
            programme = PlexProgramme(
                guid = "plex://episode/$key",
                grandparentTitle = show,
                title = "Low Tide",
                airings = listOf(PlexAiring(times.first, times.last, channel))
            )
        )

    private fun state(vararg airings: PlexScheduledRecording) = RecordingState(scheduled = airings.toList())

    @Test
    fun `an airing that begins recording started, with its show's title`() {
        val events = recordingEvents(state(airing("a", "scheduled", 100L..200L)), state(airing("a", "inprogress", 100L..200L)), nowS = 105)
        assertEquals(listOf(RecordingEvent(RecordingEvent.Kind.STARTED, "The Harbour")), events)
    }

    @Test
    fun `one that ends at its end finished, and one that ends more than a minute early stopped early`() {
        val before = state(airing("a", "inprogress", 100L..200L), airing("b", "inprogress", 100L..1000L, show = null))
        val events = recordingEvents(before, state(airing("a", "complete", 100L..200L)), nowS = 210)
        assertEquals(
            listOf(
                RecordingEvent(RecordingEvent.Kind.FINISHED, "The Harbour"),
                RecordingEvent(RecordingEvent.Kind.STOPPED_EARLY, "Low Tide")
            ),
            events
        )
    }

    @Test
    fun `nothing changed says nothing`() {
        val now = state(airing("a", "inprogress", 100L..200L))
        assertEquals(emptyList<RecordingEvent>(), recordingEvents(now, now, nowS = 150))
    }

    @Test
    fun `the next look is just after the next start or end, never sooner than 15 s nor later than 15 minutes`() {
        assertEquals(1010L - 1000 + 10, nextRecordingCheckS(state(airing("a", "scheduled", 1010L..5000L)), nowS = 1000))
        assertEquals(15L, nextRecordingCheckS(state(airing("a", "inprogress", 900L..1001L)), nowS = 1000))
        assertEquals(15 * 60L, nextRecordingCheckS(state(), nowS = 1000))
    }

    @Test
    fun `one still recording after its end is looked at again within a minute, for when it stops`() {
        assertEquals(60L, nextRecordingCheckS(state(airing("a", "inprogress", 900L..1000L)), nowS = 1010))
    }

    @Test
    fun `the channel recording now, by its key`() {
        val now = state(airing("a", "inprogress", 100L..200L, channel = "ch-2"), airing("b", "scheduled", 300L..400L, channel = "ch-1"))
        assertEquals("a", now.recordingOn("ch-2")?.key)
        assertNull(now.recordingOn("ch-1"))
    }
}
