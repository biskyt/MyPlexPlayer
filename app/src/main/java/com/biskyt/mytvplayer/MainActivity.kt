package com.biskyt.mytvplayer

import android.content.pm.ActivityInfo
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.biskyt.mytvplayer.ui.AppRoot
import com.biskyt.mytvplayer.ui.common.hasTouchScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // A phone or tablet: the screens are laid out for a TV, so they're shown in landscape.
        if (hasTouchScreen()) requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        setContent { AppRoot() }
    }
}
