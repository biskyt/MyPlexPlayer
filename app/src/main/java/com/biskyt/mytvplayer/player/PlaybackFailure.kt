package com.biskyt.mytvplayer.player

import androidx.annotation.OptIn
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.ExoPlaybackException
import androidx.media3.exoplayer.mediacodec.MediaCodecRenderer
import java.net.UnknownHostException

/** What went wrong ([message]: what happened and what to do) and, when known, why ([reason]). */
data class PlaybackError(val message: String, val reason: String? = null)

/** "Stream from lb.example.net.", naming where a stream comes from (lenient, like the player, about odd addresses). */
fun streamSource(url: String?): String? = url?.let { android.net.Uri.parse(it).host }?.let { "Stream from $it." }

/**
 * Why a stream didn't play, in words for the viewer: whether the stream itself is unavailable (its server refused,
 * it's gone, it didn't answer) or this TV box can't play what it sent (its format, its encryption).
 */
@OptIn(UnstableApi::class)
fun explainFailure(e: PlaybackException): String {
    val http = e.causes().firstNotNullOfOrNull { it as? HttpDataSource.InvalidResponseCodeException }
    return when {
        http != null -> httpReason(http.responseCode)
        e.causes().any { it is UnknownHostException } ->
            "The stream's server can't be found: its address may no longer exist."
        e.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ->
            "The stream's server didn't answer. It may be down or busy."
        e.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ->
            "Couldn't connect to the stream's server. It may be down, or not reachable from your network."
        e.errorCode == PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE ->
            "The stream's address sent something that isn't video (such as a web page)."
        e.errorCode in DRM -> "The stream is encrypted (DRM), which MyTVPlayer can't play."
        e.errorCode in AUDIO_OUTPUT ->
            "The sound couldn't be sent to your TV or amplifier. Try turning audio passthrough off in Settings › Playback."
        e.errorCode in DECODING -> "This TV box can't decode the stream's ${decoderFormat(e) ?: "video or audio"}."
        e.errorCode in PARSING -> "The stream sent data MyTVPlayer can't read (an unsupported or damaged format)."
        e.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW -> "The stream kept falling behind its live point."
        e.errorCode in NETWORK -> "The stream stopped arriving: the connection to its server failed."
        else -> "The player couldn't play it (${e.errorCodeName.removePrefix("ERROR_CODE_").lowercase().replace('_', ' ')})."
    }
}

private fun httpReason(code: Int) = when (code) {
    401, 403 ->
        "The stream's server refused to send it (error $code). It may only play in its own country, or only on its own " +
            "website or app."
    404, 410 -> "The stream isn't at its address any more (error $code). The channel list may be out of date."
    in 500..599 -> "The stream's server has a problem (error $code). It may be down for now."
    else -> "The stream's server answered with error $code."
}

/** The format the decoder couldn't handle, e.g. "video (video/hevc)", when the player says. */
@OptIn(UnstableApi::class)
private fun decoderFormat(e: PlaybackException): String? {
    val mime = e.causes().firstNotNullOfOrNull { (it as? MediaCodecRenderer.DecoderInitializationException)?.mimeType }
        ?: (e as? ExoPlaybackException)?.takeIf { it.type == ExoPlaybackException.TYPE_RENDERER }?.rendererFormat?.sampleMimeType
        ?: return null
    val kind = mime.substringBefore('/').takeIf { it == "video" || it == "audio" } ?: "media"
    return "$kind ($mime)"
}

private fun Throwable.causes(): Sequence<Throwable> = generateSequence(this) { it.cause }.take(MAX_CAUSES)

private const val MAX_CAUSES = 10

private val DRM = PlaybackException.ERROR_CODE_DRM_UNSPECIFIED..PlaybackException.ERROR_CODE_DRM_LICENSE_EXPIRED

private val NETWORK = PlaybackException.ERROR_CODE_IO_UNSPECIFIED..PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE

private val AUDIO_OUTPUT = setOf(
    PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED,
    PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED,
    PlaybackException.ERROR_CODE_AUDIO_TRACK_OFFLOAD_INIT_FAILED,
    PlaybackException.ERROR_CODE_AUDIO_TRACK_OFFLOAD_WRITE_FAILED
)

private val DECODING = setOf(
    PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
    PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
    PlaybackException.ERROR_CODE_DECODING_FAILED,
    PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
    PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED
)

private val PARSING = setOf(
    PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
    PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
    PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
    PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED
)
