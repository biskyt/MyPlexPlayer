package com.biskyt.mytvplayer.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester

/**
 * Modifier that takes focus once the element is shown (and again whenever [key] changes, e.g. when data arrives),
 * so each screen opens with the remote already on its main content.
 */
@Composable
fun rememberFirstFocus(key: Any? = Unit): Modifier {
    val r = remember { FocusRequester() }
    LaunchedEffect(key) { runCatching { r.requestFocus() } }
    return Modifier.focusRequester(r)
}
