package com.biskyt.mytvplayer.ui.common

import com.biskyt.mytvplayer.data.db.ChannelEntity
import com.biskyt.mytvplayer.data.db.ChannelProgramme
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val timeFormat = ThreadLocal.withInitial { SimpleDateFormat("HH:mm", Locale.getDefault()) }

fun formatTime(epochMs: Long): String = timeFormat.get()!!.format(Date(epochMs))

fun formatRange(startMs: Long, stopMs: Long) = "${formatTime(startMs)} – ${formatTime(stopMs)}"

fun formatDateTime(epochMs: Long): String = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(epochMs))

fun formatCount(n: Int): String = "%,d".format(n)

/** Channel number as shown in lists ("" when the provider gives none). */
val ChannelEntity.numberLabel: String get() = if (number > 0) number.toString() else ""

fun ChannelProgramme.isLive(nowMs: Long) = startMs <= nowMs && stopMs > nowMs

/** The element [step] places after [current], wrapping around (the first element if [current] isn't in the list). */
fun <T> List<T>.cycle(current: T, step: Int = 1): T = this[(indexOf(current).coerceAtLeast(0) + step).mod(size)]
