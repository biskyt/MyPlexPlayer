package com.biskyt.myplexplayer.dvdiag

import android.content.Context
import androidx.media3.common.Format
import androidx.media3.exoplayer.mediacodec.MediaCodecAdapter
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil

// Media3 1.8 (MyPlexPlayer's): the decoder checks take no context.

fun sortedByFormatSupport(context: Context, infos: List<MediaCodecInfo>, format: Format): List<MediaCodecInfo> =
    MediaCodecUtil.getDecoderInfosSortedByFormatSupport(infos, format)

fun MediaCodecInfo.supports(context: Context, format: Format): Boolean = isFormatSupported(format)

fun probeAdapter(codec: MediaCodecAdapter, configuration: MediaCodecAdapter.Configuration, result: PlaybackResult): MediaCodecAdapter =
    ProbeAdapter(codec, configuration, result)
