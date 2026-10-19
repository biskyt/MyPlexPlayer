package com.biskyt.mytvplayer.player

import android.content.Context
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioCapabilities
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import com.biskyt.mytvplayer.core.player.SubtitlePolicy
import com.biskyt.mytvplayer.core.player.TextTrack
import com.biskyt.mytvplayer.data.BufferSize
import com.biskyt.mytvplayer.data.Settings

/**
 * Builds and drives one ExoPlayer for a playback session.
 *
 * Audio passthrough (default on): the stock audio sink sends Dolby/DTS bitstreams to the TV or soundbar when the
 * HDMI device says it can decode them. When the user turns it off, the sink is limited to PCM so everything is
 * decoded on the box (hardware decoders first, then the FFmpeg extension).
 */
@OptIn(UnstableApi::class)
class PlaybackEngine(private val context: Context, private val settings: Settings) {

    private val http = DefaultHttpDataSource.Factory()
        .setUserAgent(DEFAULT_USER_AGENT)
        .setAllowCrossProtocolRedirects(true)
        .setConnectTimeoutMs(10_000)
        .setReadTimeoutMs(15_000)

    val player: ExoPlayer = ExoPlayer.Builder(context, renderersFactory())
        .setLoadControl(loadControl(settings.bufferSize))
        .setMediaSourceFactory(
            DefaultMediaSourceFactory(
                http,
                DefaultExtractorsFactory()
                    // Start live MPEG-TS faster and cope with streams that don't flag keyframes.
                    .setTsExtractorFlags(
                        DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES or DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS
                    )
            )
        )
        .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
        .setHandleAudioBecomingNoisy(true)
        .build()

    /**
     * Called with an address that can't be played at all (a stream type the player has no support for), instead of
     * the player throwing and closing the app.
     */
    var onUnsupported: (url: String) -> Unit = {}

    /** True once the default subtitle choice has been applied to the current stream (the user may change it after). */
    private var subtitlesApplied = false

    // URLs whose format was worked out from the address and turned out to be HLS (some M3U links don't say).
    private val hlsUrls = HashSet<String>()
    private var current: String? = null

    /** The current stream's address. */
    val currentUrl: String? get() = current
    private var currentInferred = false

    /**
     * [hls]: null works the format out from the stream (retrying once as HLS when that fails, see [retryAsHls]).
     * [userAgent] / [referrer]: some M3U streams only play when these headers are sent.
     */
    fun play(url: String, hls: Boolean?, userAgent: String? = null, referrer: String? = null) {
        subtitlesApplied = false
        current = url
        currentInferred = hls == null && url !in hlsUrls
        http.setUserAgent(userAgent ?: DEFAULT_USER_AGENT)
        http.setDefaultRequestProperties(if (referrer != null) mapOf("Referer" to referrer) else emptyMap())
        val asHls = hls ?: (url in hlsUrls)
        val item = MediaItem.Builder().setUri(url).apply { if (asHls) setMimeType(MimeTypes.APPLICATION_M3U8) }.build()
        if (!start(item)) return
        player.playWhenReady = true
    }

    /** Sets and prepares [item]; false (and [onUnsupported]) when the player can't handle its type. */
    private fun start(item: MediaItem): Boolean = try {
        player.setMediaItem(item)
        player.prepare()
        true
    } catch (e: IllegalStateException) {
        // E.g. a SmoothStreaming (.ism) address: the media source factory has no module for it.
        Log.w(TAG, "Can't play ${item.localConfiguration?.uri}", e)
        // Nothing of the previous stream may be left to start again on a later prepare().
        player.stop()
        player.clearMediaItems()
        current = null
        onUnsupported(item.localConfiguration?.uri?.toString().orEmpty())
        false
    }

    /** A stream whose format was guessed and couldn't be read is tried once more as HLS. True if it was. */
    fun retryAsHls(e: PlaybackException): Boolean {
        val url = current ?: return false
        if (!currentInferred || e.errorCode != PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED) return false
        currentInferred = false
        hlsUrls += url
        return start(MediaItem.Builder().setUri(url).setMimeType(MimeTypes.APPLICATION_M3U8).build())
    }

    /** Applies the user's default subtitle setting the first time tracks are known for a stream. */
    fun applyDefaultSubtitles(tracks: Tracks) {
        if (subtitlesApplied) return
        if (tracks.groups.isEmpty()) return
        subtitlesApplied = true
        val options = tracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }.flatMap { g -> (0 until g.length).map { g to it } }
        val candidates = options.mapIndexed { i, (g, ti) ->
            val f = g.getTrackFormat(ti)
            TextTrack(i.toString(), f.language, (f.selectionFlags and C.SELECTION_FLAG_FORCED) != 0)
        }
        val choice = SubtitlePolicy.choose(candidates, settings.subtitleMode, settings.effectiveSubtitleLanguage)
        val (group, index) = choice?.let { options[it.id.toInt()] } ?: (null to 0)
        selectTrack(C.TRACK_TYPE_TEXT, group, index)
    }

    /** Selects a track of [type] (audio or text); null turns that type off. */
    fun selectTrack(type: Int, group: Tracks.Group?, index: Int) {
        val params = player.trackSelectionParameters.buildUpon()
        if (group == null) {
            params.setTrackTypeDisabled(type, true)
        } else {
            params.setTrackTypeDisabled(type, false).setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, index))
        }
        player.trackSelectionParameters = params.build()
    }

    /**
     * How the sound is played, for the options menu, e.g. "Passthrough · Dolby Atmos". Read from the selected audio track in [tracks]: when tracks change,
     * the player's own audio format isn't known yet (the audio output starts a moment later).
     */
    fun audioOutputLabel(tracks: Tracks = player.currentTracks): String {
        val f = selectedAudioFormat(tracks) ?: player.audioFormat ?: return "No audio"
        val name = encodedName(f.sampleMimeType)
        if (name != null && settings.audioPassthrough) {
            val caps = AudioCapabilities.getCapabilities(context, AudioAttributes.DEFAULT, null)
            if (caps.isPassthroughPlaybackSupported(f, AudioAttributes.DEFAULT)) return "Passthrough · $name"
        }
        val channels = f.channelCount.takeIf { it != Format.NO_VALUE } ?: 2
        val decoded = if (name != null) "$name decoded" else "Audio"
        return if (channels > 2) "$decoded · ${channels}ch" else "$decoded · Stereo"
    }

    private fun selectedAudioFormat(tracks: Tracks): Format? = tracks.groups
        .firstOrNull { it.type == C.TRACK_TYPE_AUDIO && it.isSelected }
        ?.let { g -> (0 until g.length).firstOrNull(g::isTrackSelected)?.let(g::getTrackFormat) }

    fun release() = player.release()

    private fun renderersFactory() = object : DefaultRenderersFactory(context) {
        override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean): AudioSink? =
            if (settings.audioPassthrough) {
                super.buildAudioSink(context, enableFloatOutput, enableAudioTrackPlaybackParams)
            } else {
                @Suppress("DEPRECATION")
                DefaultAudioSink.Builder(context).setAudioCapabilities(AudioCapabilities.DEFAULT_AUDIO_CAPABILITIES).build()
            }
    }.setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON).setEnableDecoderFallback(true)

    private fun loadControl(size: BufferSize): DefaultLoadControl {
        // Byte caps matter on low-RAM boxes: high-bitrate streams would otherwise fill memory before the time limit.
        val (minMs, maxMs, bytes) = when (size) {
            BufferSize.SMALL -> Triple(8_000, 20_000, 8 * MB)
            BufferSize.NORMAL -> Triple(15_000, 30_000, 16 * MB)
            BufferSize.LARGE -> Triple(30_000, 60_000, 32 * MB)
        }
        return DefaultLoadControl.Builder()
            .setBufferDurationsMs(minMs, maxMs, 1_500, 3_000)
            .setTargetBufferBytes(bytes)
            .setPrioritizeTimeOverSizeThresholds(false)
            .build()
    }

    private companion object {
        const val TAG = "PlaybackEngine"
        const val MB = 1024 * 1024
        const val DEFAULT_USER_AGENT = "MyTVPlayer"

        fun encodedName(mime: String?): String? = when (mime) {
            MimeTypes.AUDIO_AC3 -> "Dolby Digital"
            MimeTypes.AUDIO_E_AC3 -> "Dolby Digital Plus"
            MimeTypes.AUDIO_E_AC3_JOC -> "Dolby Atmos"
            MimeTypes.AUDIO_AC4 -> "Dolby AC-4"
            MimeTypes.AUDIO_TRUEHD -> "Dolby TrueHD"
            MimeTypes.AUDIO_DTS -> "DTS"
            MimeTypes.AUDIO_DTS_HD -> "DTS-HD"
            MimeTypes.AUDIO_DTS_X -> "DTS:X"
            else -> null
        }
    }
}
