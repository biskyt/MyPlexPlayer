package com.biskyt.mytvplayer.ui.common

import com.biskyt.mytvplayer.AppText
import com.biskyt.mytvplayer.R
import com.biskyt.mytvplayer.appString
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

// Day names are in the app's language (AppText.locale), not the device's. Built once per language.
private class DateFormats(val locale: Locale) {
    val day: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE", locale)
    val date: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE d MMM", locale)
}

@Volatile private var formats = DateFormats(AppText.locale)

private fun formats(): DateFormats = formats.takeIf { it.locale == AppText.locale } ?: DateFormats(AppText.locale).also { formats = it }

/** "Mon 19 Oct". */
val dateFormat: DateTimeFormatter get() = formats().date

/** When something starts, relative to [now]: "Today 20:00", "Tomorrow 06:30", "Wed 21:00" or "Wed 3 Dec 21:00". */
fun formatDayTime(epochMs: Long, now: Long = System.currentTimeMillis()): String {
    val zone = ZoneId.systemDefault()
    val date = Instant.ofEpochMilli(epochMs).atZone(zone).toLocalDate()
    val time = formatTime(epochMs)
    return when (val days = ChronoUnit.DAYS.between(Instant.ofEpochMilli(now).atZone(zone).toLocalDate(), date)) {
        0L -> appString(R.string.common_today_at, time)
        1L -> appString(R.string.common_tomorrow_at, time)
        else -> "${(if (days in 2L..6L) formats().day else dateFormat).format(date)} $time"
    }
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
        minutes < 60 -> appString(R.string.common_runtime_min, minutes)
        minutes % 60 == 0 -> appString(R.string.common_runtime_h, minutes / 60)
        else -> appString(R.string.common_runtime_h_min, minutes / 60, minutes % 60)
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
