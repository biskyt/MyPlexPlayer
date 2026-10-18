package com.biskyt.mytvplayer.ui.common

import com.biskyt.mytvplayer.data.db.ChannelEntity
import com.biskyt.mytvplayer.data.db.ChannelProgramme
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Date
import java.util.Locale

private val timeFormat = ThreadLocal.withInitial { SimpleDateFormat("HH:mm", Locale.getDefault()) }

fun formatTime(epochMs: Long): String = timeFormat.get()!!.format(Date(epochMs))

fun formatRange(startMs: Long, stopMs: Long) = "${formatTime(startMs)} – ${formatTime(stopMs)}"

fun formatDateTime(epochMs: Long): String = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(epochMs))

private val dayFormat = DateTimeFormatter.ofPattern("EEE", Locale.getDefault())

/** When something starts, relative to [now]: "Today 20:00", "Tomorrow 06:30" or "Wed 21:00". */
fun formatDayTime(epochMs: Long, now: Long = System.currentTimeMillis()): String {
    val zone = ZoneId.systemDefault()
    val date = Instant.ofEpochMilli(epochMs).atZone(zone).toLocalDate()
    val day = when (ChronoUnit.DAYS.between(Instant.ofEpochMilli(now).atZone(zone).toLocalDate(), date)) {
        0L -> "Today"
        1L -> "Tomorrow"
        else -> dayFormat.format(date)
    }
    return "$day ${formatTime(epochMs)}"
}

fun formatCount(n: Int): String = "%,d".format(n)

/** A playback position: "12:34" or "1:02:03". */
fun formatDuration(ms: Long): String {
    val total = ms.coerceAtLeast(0) / 1000
    val h = total / 3600
    val m = total / 60 % 60
    val sec = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}

/** A running time: "1 h 52 min" or "45 min". */
fun formatRuntime(seconds: Int): String {
    val minutes = (seconds + 30) / 60
    return when {
        minutes < 60 -> "$minutes min"
        minutes % 60 == 0 -> "${minutes / 60} h"
        else -> "${minutes / 60} h ${minutes % 60} min"
    }
}

/** Channel number as shown in lists ("" when the provider gives none). */
val ChannelEntity.numberLabel: String get() = if (number > 0) number.toString() else ""

fun ChannelProgramme.isLive(nowMs: Long) = isLive(startMs, stopMs, nowMs)

fun isLive(startMs: Long, stopMs: Long, nowMs: Long) = startMs <= nowMs && stopMs > nowMs

/** The element [step] places after [current], wrapping around (the first element if [current] isn't in the list). */
fun <T> List<T>.cycle(current: T, step: Int = 1): T {
    val i = indexOf(current)
    return if (i < 0) first() else this[(i + step).mod(size)]
}
