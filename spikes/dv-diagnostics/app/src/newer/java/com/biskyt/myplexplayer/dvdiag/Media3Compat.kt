package com.biskyt.myplexplayer.dvdiag

import android.content.Context
import androidx.media3.common.Format
import androidx.media3.common.util.ThrowingRunnable
import androidx.media3.exoplayer.mediacodec.MediaCodecAdapter
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil

// Newer Media3: the decoder checks take a context (to ask the display too).

fun sortedByFormatSupport(context: Context, infos: List<MediaCodecInfo>, format: Format): List<MediaCodecInfo> =
    MediaCodecUtil.getDecoderInfosSortedByFormatSupport(context, infos, format)

fun MediaCodecInfo.supports(context: Context, format: Format): Boolean = isFormatSupported(context, format)

fun probeAdapter(codec: MediaCodecAdapter, configuration: MediaCodecAdapter.Configuration, result: PlaybackResult): MediaCodecAdapter =
    object : ProbeAdapter(codec, configuration, result) {
        override fun <E : Exception> useBuffer(runnable: ThrowingRunnable<E>) = codec.useBuffer(runnable)
    }
