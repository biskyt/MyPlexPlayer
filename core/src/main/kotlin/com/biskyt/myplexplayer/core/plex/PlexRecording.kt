package com.biskyt.myplexplayer.core.plex

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A way to record a programme, from its recording template (docs/plex-api.md, 14 and 22): this airing ([EPISODE], or
 * a film) or every episode of its show ([SERIES], the series link). [parameters] is the template's own query (hints
 * and params), sent back as it is; every one of [settings] is sent too.
 */
@Serializable
data class PlexRecordingOption(
    val type: Int = 0,
    val parameters: String = "",
    val targetLibrarySectionID: Long? = null,
    val targetSectionLocationID: Long? = null,
    @SerialName("Setting") @Serializable(with = SkippingList::class) val settings: List<PlexSetting> = emptyList(),
) {
    val isSeries: Boolean get() = type == SERIES

    companion object {
        const val SERIES = 2
        const val EPISODE = 4
    }
}

/**
 * A recording rule ("subscription"): one airing, or a series ([PlexRecordingOption.SERIES], its show in `Directory`;
 * an airing's programme is in `Video`). [key] is what it's removed and changed by; [settings] are as it records.
 */
@Serializable
data class PlexRecordingRule(
    @Serializable(with = PlexIdText::class) val key: String = "",
    val type: Int = 0,
    @SerialName("Directory") @Serializable(with = SkippingList::class) val shows: List<PlexProgramme> = emptyList(),
    @SerialName("Video") @Serializable(with = SkippingList::class) val programmes: List<PlexProgramme> = emptyList(),
    @SerialName("Setting") @Serializable(with = SkippingList::class) val settings: List<PlexSetting> = emptyList(),
) {
    val isSeries: Boolean get() = type == PlexRecordingOption.SERIES

    /** Setting [id] as the rule records now (its value, else its default), or null if it has no such setting. */
    fun setting(id: String): String? = settings.firstOrNull { it.id == id }?.current

    /** What it records: the show of a series, else the programme. */
    val subject: PlexProgramme? get() = shows.firstOrNull() ?: programmes.firstOrNull()
}

/**
 * One airing set to record (a grab), by the rule [mediaSubscriptionID]: its programme, with the showing's times and
 * channel in [PlexProgramme.airings]. [status] is `scheduled`, `inprogress` while recording, then `postprocessing`
 * and `complete`; or `error`, `cancelled`, `inactive` or `paused` (the spec's list). [key] cancels this airing alone.
 */
@Serializable
data class PlexScheduledRecording(
    @Serializable(with = PlexIdText::class) val key: String = "",
    val status: String = "",
    @Serializable(with = PlexIdText::class) val mediaSubscriptionID: String? = null,
    @SerialName("Metadata") val programme: PlexProgramme? = null,
) {
    /** When it starts and ends, in seconds since 1970, and on which channel. */
    val airing: PlexAiring? get() = programme?.airings?.firstOrNull()

    val isScheduled: Boolean get() = status == SCHEDULED
    val isRecording: Boolean get() = status in RECORDING

    /**
     * It won't record as set: it failed, or Plex holds it back (`inactive`, `paused`). Not one cancelled, or still being
     * processed once recorded.
     */
    val needsAttention: Boolean get() = status in NEEDS_ATTENTION

    /** What it's called: its show's title (an episode), else the programme's. */
    val title: String get() = programme?.grandparentTitle ?: programme?.title.orEmpty()

    companion object {
        const val SCHEDULED = "scheduled"
        val NEEDS_ATTENTION = setOf("error", "inactive", "paused")

        /** The tune's own grab (docs/plex-api.md, 13) says `inprogress`; Plezy reads `grabbing` as recording too. */
        val RECORDING = setOf("inprogress", "grabbing")
    }
}

@Serializable
internal class PlexRecordingResponse(@SerialName("MediaContainer") val container: PlexRecordingContainer = PlexRecordingContainer())

@Serializable
internal class PlexRecordingContainer(
    @SerialName("SubscriptionTemplate") val templates: List<PlexRecordingTemplate> = emptyList(),
    @SerialName("MediaSubscription") @Serializable(with = SkippingList::class) val rules: List<PlexRecordingRule> = emptyList(),
    @SerialName("MediaGrabOperation") @Serializable(with = SkippingList::class)
    val scheduled: List<PlexScheduledRecording> = emptyList(),
)

@Serializable
internal class PlexRecordingTemplate(
    @SerialName("MediaSubscription") @Serializable(with = SkippingList::class) val options: List<PlexRecordingOption> = emptyList(),
)
