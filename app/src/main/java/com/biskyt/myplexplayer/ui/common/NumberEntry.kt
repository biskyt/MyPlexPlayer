package com.biskyt.myplexplayer.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Digits typed on the remote; after a short pause [onNumber] is called with the full number. */
class NumberEntry(private val scope: CoroutineScope, private val onNumber: suspend (Int) -> Unit) {
    var typed by mutableStateOf("")
        private set
    private var job: Job? = null

    fun type(digit: Int) {
        if (typed.length >= MAX_DIGITS) return
        typed += digit
        job?.cancel()
        job = scope.launch {
            delay(PAUSE_MS)
            val n = typed.toIntOrNull()
            typed = ""
            if (n != null) onNumber(n)
        }
    }

    private companion object {
        const val MAX_DIGITS = 5
        const val PAUSE_MS = 1_500L
    }
}

/** Big label floating over the screen: typed channel numbers, "+30s", short notices. */
@Composable
fun FlashLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.headlineMedium,
        modifier = modifier.padding(
            40.dp
        ).clip(RoundedCornerShape(14.dp)).background(Color(0xCC080A0D)).padding(horizontal = 24.dp, vertical = 12.dp)
    )
}
