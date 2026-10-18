package com.biskyt.mytvplayer.ui.common

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/** The handful of icons the app needs, drawn as vectors (a full icon library would bloat the APK). */
object AppIcons {
    private fun icon(name: String, fill: Boolean, block: PathBuilder.() -> Unit) = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
        if (fill) {
            path(fill = SolidColor(Color.White), pathBuilder = block)
        } else {
            path(stroke = SolidColor(Color.White), strokeLineWidth = 2.2f, strokeLineCap = StrokeCap.Round, pathBuilder = block)
        }
    }.build()

    val Play = icon("play", true) {
        moveTo(7f, 5f)
        lineTo(19f, 12f)
        lineTo(7f, 19f)
        close()
    }
    val Pause =
        icon("pause", true) {
            moveTo(6f, 5f)
            horizontalLineTo(10f)
            verticalLineTo(19f)
            horizontalLineTo(6f)
            close()
            moveTo(14f, 5f)
            horizontalLineTo(18f)
            verticalLineTo(19f)
            horizontalLineTo(14f)
            close()
        }
    val Stop = icon("stop", true) {
        moveTo(6f, 6f)
        horizontalLineTo(18f)
        verticalLineTo(18f)
        horizontalLineTo(6f)
        close()
    }
    val Rewind =
        icon("rewind", true) {
            moveTo(11f, 6f)
            lineTo(4f, 12f)
            lineTo(11f, 18f)
            close()
            moveTo(20f, 6f)
            lineTo(13f, 12f)
            lineTo(20f, 18f)
            close()
        }
    val Forward =
        icon("forward", true) {
            moveTo(13f, 6f)
            lineTo(20f, 12f)
            lineTo(13f, 18f)
            close()
            moveTo(4f, 6f)
            lineTo(11f, 12f)
            lineTo(4f, 18f)
            close()
        }
    val Search = icon("search", false) {
        moveTo(18f, 11f)
        arcTo(7f, 7f, 0f, true, true, 4f, 11f)
        arcTo(7f, 7f, 0f, true, true, 18f, 11f)
        moveTo(16f, 16f)
        lineTo(20f, 20f)
    }
    val Heart = icon("heart", true) {
        moveTo(12f, 20f)
        curveTo(12f, 20f, 4f, 15f, 4f, 9.5f)
        curveTo(4f, 7f, 6f, 5f, 8.5f, 5f)
        curveTo(10f, 5f, 11.3f, 5.8f, 12f, 7f)
        curveTo(12.7f, 5.8f, 14f, 5f, 15.5f, 5f)
        curveTo(18f, 5f, 20f, 7f, 20f, 9.5f)
        curveTo(20f, 15f, 12f, 20f, 12f, 20f)
        close()
    }
    val Guide =
        icon("guide", false) {
            moveTo(4f, 6f)
            horizontalLineTo(20f)
            moveTo(4f, 12f)
            horizontalLineTo(20f)
            moveTo(4f, 18f)
            horizontalLineTo(14f)
        }
    val Filter =
        icon("filter", false) {
            moveTo(4f, 6f)
            horizontalLineTo(20f)
            moveTo(7f, 12f)
            horizontalLineTo(17f)
            moveTo(10f, 18f)
            horizontalLineTo(14f)
        }
    val Settings = icon("settings", false) {
        moveTo(15f, 12f)
        arcTo(3f, 3f, 0f, true, true, 9f, 12f)
        arcTo(3f, 3f, 0f, true, true, 15f, 12f)
        moveTo(12f, 2.5f)
        verticalLineTo(5f)
        moveTo(12f, 19f)
        verticalLineTo(21.5f)
        moveTo(2.5f, 12f)
        horizontalLineTo(5f)
        moveTo(19f, 12f)
        horizontalLineTo(21.5f)
        moveTo(5.3f, 5.3f)
        lineTo(7f, 7f)
        moveTo(17f, 17f)
        lineTo(18.7f, 18.7f)
        moveTo(5.3f, 18.7f)
        lineTo(7f, 17f)
        moveTo(17f, 7f)
        lineTo(18.7f, 5.3f)
    }
}
