package com.biskyt.mytvplayer.ui.common

import androidx.compose.ui.input.key.Key

private val digitKeys = listOf(Key.Zero, Key.One, Key.Two, Key.Three, Key.Four, Key.Five, Key.Six, Key.Seven, Key.Eight, Key.Nine)
private val numPadKeys =
    listOf(Key.NumPad0, Key.NumPad1, Key.NumPad2, Key.NumPad3, Key.NumPad4, Key.NumPad5, Key.NumPad6, Key.NumPad7, Key.NumPad8, Key.NumPad9)

/** The number on a remote's digit key (or number pad), or null for any other key. */
fun Key.digit(): Int? = digitKeys.indexOf(this).takeIf { it >= 0 } ?: numPadKeys.indexOf(this).takeIf { it >= 0 }
