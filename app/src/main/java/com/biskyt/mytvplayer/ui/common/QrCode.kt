package com.biskyt.mytvplayer.ui.common

import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.biskyt.mytvplayer.R
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import kotlin.math.floor

/**
 * [text] (e.g. a web address) as a QR code for a phone camera: black on white with the standard quiet border, so it
 * scans from a dark TV screen too. Drawn as large as fits, centred.
 */
@Composable
fun QrCode(text: String, modifier: Modifier = Modifier) {
    val matrix = remember(text) { encode(text) }
    val description = stringResource(R.string.common_qr_description, text)
    Spacer(
        modifier.semantics { contentDescription = description }.drawWithCache {
            // Whole pixels per module (no blurred seams between them for a camera to trip on), centred.
            val cell = floor(size.minDimension / matrix.width).coerceAtLeast(1f)
            val side = cell * matrix.width
            val origin = Offset((size.width - side) / 2, (size.height - side) / 2)
            val dark = Path()
            for (y in 0 until matrix.height) {
                for (x in 0 until matrix.width) {
                    if (matrix[x, y]) dark.addRect(Rect(origin + Offset(x * cell, y * cell), Size(cell, cell)))
                }
            }
            onDrawBehind {
                drawRect(Color.White, origin, Size(side, side))
                drawPath(dark, Color.Black)
            }
        }
    )
}

/** The code's modules, with ZXing's default quiet border (4 modules) included. */
internal fun encode(text: String): BitMatrix =
    QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M))
