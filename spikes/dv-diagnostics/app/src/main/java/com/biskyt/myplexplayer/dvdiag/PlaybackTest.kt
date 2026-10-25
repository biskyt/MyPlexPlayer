package com.biskyt.myplexplayer.dvdiag

import android.content.Context
import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Bundle
import android.os.Handler
import android.os.SystemClock
import android.view.SurfaceView
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.mediacodec.MediaCodecAdapter
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer
import androidx.media3.exoplayer.video.VideoRendererEventListener

/** The ways a title is test played. */
enum class Way(val label: String, val detail: String, val dolbyVisionOnly: Boolean, val switchOffHdr10Plus: Boolean) {
    AS_APP("A: as MyPlexPlayer plays it", "Media3's own choice of decoder, as in the app", false, false),
    DV_DECODER("B: Dolby Vision decoder", "only the Dolby Vision decoder may play it", true, false),
    DV_NO_HDR10PLUS("C: Dolby Vision decoder, HDR10+ off", "as B, with the HDR10+ data switched off (Plezy cuts it out)", true, true)
}

/** One test play: what the player did, filled in as it plays. */
class PlaybackResult(val way: Way) {
    var decoder: String? = null
    var codecType: String? = null
    var inputFormat: Format? = null
    val outputFormats = mutableListOf<String>()
    var trackSupport: String? = null
    var videoSize: String? = null
    var firstFrameMs: Long? = null
    var dropped = 0
    val errors = mutableListOf<String>()
    val scanner = NalScanner()
    var hdr10PlusParameters = 0
    var seen: String = "not asked"

    fun describe(): String = buildString {
        appendLine("-- ${way.label} (${way.detail})")
        appendLine("  TV showed: $seen")
        appendLine("  Decoder: $decoder (as $codecType)")
        appendLine("  Input: ${inputFormat?.let { DeviceReport.describe(it) }}")
        outputFormats.forEachIndexed { i, f -> appendLine("  Decoder's output format ${i + 1}: $f") }
        appendLine("  Track support: $trackSupport")
        appendLine("  Video size: $videoSize; first frame after ${firstFrameMs ?: "-"} ms; dropped frames $dropped")
        appendLine(
            "  Samples scanned ${scanner.samples}: Dolby Vision RPUs ${scanner.rpus}, enhancement layer " +
                "${scanner.enhancementLayers}, HDR10+ in the stream ${scanner.hdr10Plus} (NALs switched off " +
                "${scanner.switchedOff}, left on in shared NALs ${scanner.leftOn}), HDR10+ passed as a decoder " +
                "parameter $hdr10PlusParameters"
        )
        errors.forEach { appendLine("  Error: $it") }
    }
}

/** [text] without web addresses, IP addresses or plex.direct names (the report never holds where the server is). */
fun redacted(text: String): String = text
    .replace(Regex("https?://[^\\s;,)]+"), "<address>")
    .replace(Regex("[\\w.-]+\\.plex\\.direct"), "<server>")
    .replace(Regex("\\d+\\.\\d+\\.\\d+\\.\\d+"), "<ip>")

/** Plays [url] from [startMs] the [Way] asked, filling in [result]. Release it when done. */
class PlaybackTest(context: Context, url: String, startMs: Long, surface: SurfaceView, val result: PlaybackResult) {
    private val started = SystemClock.elapsedRealtime()

    private val player: ExoPlayer = ExoPlayer.Builder(context, ProbeRenderersFactory(context, result))
        .setMediaSourceFactory(DefaultMediaSourceFactory(DefaultHttpDataSource.Factory().setUserAgent(USER_AGENT)))
        .build()

    init {
        player.addAnalyticsListener(object : AnalyticsListener {
            override fun onVideoInputFormatChanged(eventTime: AnalyticsListener.EventTime, format: Format, decoderReuseEvaluation: androidx.media3.exoplayer.DecoderReuseEvaluation?) {
                result.inputFormat = format
            }

            override fun onTracksChanged(eventTime: AnalyticsListener.EventTime, tracks: Tracks) {
                result.trackSupport = tracks.groups.filter { it.type == C.TRACK_TYPE_VIDEO }.joinToString { group ->
                    (0 until group.length).joinToString {
                        "${group.getTrackFormat(it).codecs}: ${DeviceReport.formatSupport(group.getTrackSupport(it))}, selected=${group.isTrackSelected(it)}"
                    }
                }
            }

            override fun onVideoSizeChanged(eventTime: AnalyticsListener.EventTime, videoSize: VideoSize) {
                result.videoSize = "${videoSize.width}x${videoSize.height}"
            }

            override fun onRenderedFirstFrame(eventTime: AnalyticsListener.EventTime, output: Any, renderTimeMs: Long) {
                if (result.firstFrameMs == null) result.firstFrameMs = SystemClock.elapsedRealtime() - started
            }

            override fun onDroppedVideoFrames(eventTime: AnalyticsListener.EventTime, droppedFrames: Int, elapsedMs: Long) {
                result.dropped += droppedFrames
            }

            override fun onVideoCodecError(eventTime: AnalyticsListener.EventTime, videoCodecError: Exception) {
                result.errors += redacted("codec: $videoCodecError")
            }

            override fun onPlayerError(eventTime: AnalyticsListener.EventTime, error: PlaybackException) {
                result.errors += redacted("${error.errorCodeName}: ${error.message}; cause ${error.cause}")
            }
        })
        player.setVideoSurfaceView(surface)
        player.setMediaItem(MediaItem.fromUri(url), startMs)
        player.prepare()
        player.play()
    }

    fun release() = player.release()

    private companion object {
        const val USER_AGENT = "MyPlexPlayer-DvDiagnostics/1 (+https://github.com/biskyt/MyPlexPlayer)"
    }
}

/**
 * MyPlexPlayer's renderers (extensions on, decoder fallback), left as Media3 builds them: the probe sits between the
 * video renderer and its decoder ([ProbeAdapter]), so nothing is rebuilt. For [Way.dolbyVisionOnly], only the
 * device's Dolby Vision decoders may play video, whatever type the file's track was given, and no other video
 * renderers are built. Neither build has MyPlexPlayer's FFmpeg decoders (there are none for the newer Media3): they
 * only play what the TV can't, so leave Dolby Vision alone, and without them the builds differ only in their Media3.
 */
private class ProbeRenderersFactory(context: Context, private val result: PlaybackResult) : DefaultRenderersFactory(context) {
    init {
        setExtensionRendererMode(EXTENSION_RENDERER_MODE_ON)
        setEnableDecoderFallback(true)
        if (result.way.dolbyVisionOnly) setMediaCodecSelector(DOLBY_VISION_ONLY)
    }

    override fun getCodecAdapterFactory(): MediaCodecAdapter.Factory {
        val plain = super.getCodecAdapterFactory()
        return MediaCodecAdapter.Factory { configuration ->
            val adapter = plain.createAdapter(configuration)
            if (MimeTypes.isVideo(configuration.format.sampleMimeType)) probeAdapter(adapter, configuration, result) else adapter
        }
    }

    override fun buildVideoRenderers(
        context: Context,
        extensionRendererMode: Int,
        mediaCodecSelector: MediaCodecSelector,
        enableDecoderFallback: Boolean,
        eventHandler: Handler,
        eventListener: VideoRendererEventListener,
        allowedVideoJoiningTimeMs: Long,
        out: ArrayList<Renderer>
    ) {
        val first = out.size
        super.buildVideoRenderers(
            context, extensionRendererMode, mediaCodecSelector, enableDecoderFallback, eventHandler, eventListener,
            allowedVideoJoiningTimeMs, out
        )
        if (!result.way.dolbyVisionOnly) return
        val built = out.subList(first, out.size)
        built.retainAll { it is MediaCodecVideoRenderer }
    }

    private companion object {
        /** The Dolby Vision decoders, asked for Dolby Vision or HEVC video (a track may be read as either). */
        val DOLBY_VISION_ONLY = MediaCodecSelector { mimeType, secure, tunneling ->
            when {
                mimeType == MimeTypes.VIDEO_DOLBY_VISION || mimeType == MimeTypes.VIDEO_H265 ->
                    MediaCodecSelector.DEFAULT.getDecoderInfos(MimeTypes.VIDEO_DOLBY_VISION, secure, tunneling)
                MimeTypes.isVideo(mimeType) -> emptyList()
                else -> MediaCodecSelector.DEFAULT.getDecoderInfos(mimeType, secure, tunneling)
            }
        }
    }
}

/**
 * Between the video renderer and its decoder: notes the decoder and its output format, reads each sample as it is
 * queued (counting the first few hundred; for [Way.DV_NO_HDR10PLUS], switching HDR10+ off in all), and sees the
 * HDR10+ that Media3 passes to the decoder as a parameter (from a Matroska file's block additions, not the stream).
 * Made by [probeAdapter].
 */
open class ProbeAdapter(
    protected val codec: MediaCodecAdapter,
    configuration: MediaCodecAdapter.Configuration,
    private val result: PlaybackResult
) : MediaCodecAdapter by codec {
    init {
        result.decoder = configuration.codecInfo.name
        result.codecType = configuration.codecInfo.codecMimeType
    }

    override fun queueInputBuffer(index: Int, offset: Int, size: Int, presentationTimeUs: Long, flags: Int) {
        val count = result.scanner.samples < SCANNED
        if (size > 0 && (count || result.way.switchOffHdr10Plus)) {
            codec.getInputBuffer(index)?.duplicate()?.let { data ->
                data.limit(offset + size).position(offset)
                runCatching { result.scanner.scan(data, count, result.way.switchOffHdr10Plus) }
                    .onFailure { if (result.errors.none { e -> e.startsWith("probe") }) result.errors += "probe: $it" }
            }
        }
        codec.queueInputBuffer(index, offset, size, presentationTimeUs, flags)
    }

    override fun setParameters(params: Bundle) {
        if (!params.containsKey(MediaCodec.PARAMETER_KEY_HDR10_PLUS_INFO)) return codec.setParameters(params)
        result.hdr10PlusParameters++
        if (!result.way.switchOffHdr10Plus) return codec.setParameters(params)
        val rest = Bundle(params).apply { remove(MediaCodec.PARAMETER_KEY_HDR10_PLUS_INFO) }
        if (!rest.isEmpty) codec.setParameters(rest)
    }

    // Kotlin's delegation leaves out the interface's default methods: these pass them on (Media3's asynchronous
    // adapter has its own). The newer Media3's useBuffer is passed on in its build's Media3Compat.
    override fun registerOnBufferAvailableListener(listener: MediaCodecAdapter.OnBufferAvailableListener): Boolean =
        codec.registerOnBufferAvailableListener(listener)

    /** Each different output format, as a decoder can add its HDR details after the first frames. */
    override fun getOutputFormat(): MediaFormat = codec.outputFormat.also {
        val text = it.toString()
        if (result.outputFormats.size < MAX_FORMATS && result.outputFormats.lastOrNull() != text) result.outputFormats += text
    }

    private companion object {
        const val SCANNED = 600
        const val MAX_FORMATS = 5
    }
}
