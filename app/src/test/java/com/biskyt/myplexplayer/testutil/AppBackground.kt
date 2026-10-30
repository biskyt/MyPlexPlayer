package com.biskyt.myplexplayer.testutil

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.biskyt.myplexplayer.ui.theme.Palette

/** [content] on the app's background, as the app paints it once below every screen (screens paint none). */
@Composable
fun OnAppBackground(content: @Composable () -> Unit) = Box(Modifier.fillMaxSize().background(Palette.background)) { content() }
