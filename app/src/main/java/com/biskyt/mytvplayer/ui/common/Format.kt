package com.biskyt.mytvplayer.ui.common

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val timeFormat = ThreadLocal.withInitial { SimpleDateFormat("HH:mm", Locale.getDefault()) }

fun formatTime(epochMs: Long): String = timeFormat.get()!!.format(Date(epochMs))

fun formatRange(startMs: Long, stopMs: Long) = "${formatTime(startMs)} – ${formatTime(stopMs)}"

fun formatCount(n: Int): String = "%,d".format(n)
