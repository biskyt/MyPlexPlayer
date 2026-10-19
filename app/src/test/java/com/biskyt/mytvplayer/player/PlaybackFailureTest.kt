package com.biskyt.mytvplayer.player

import androidx.media3.common.PlaybackException
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import java.net.UnknownHostException
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class PlaybackFailureTest {
    private fun failure(code: Int, cause: Throwable? = null) = PlaybackException("test", cause, code)

    private fun http(status: Int) = failure(
        PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
        HttpDataSource.InvalidResponseCodeException(
            status,
            null,
            null,
            emptyMap(),
            DataSpec(android.net.Uri.parse("https://x/s.m3u8")),
            ByteArray(0)
        )
    )

    private fun says(e: PlaybackException, vararg words: String) {
        val text = explainFailure(e)
        words.forEach { assertTrue("'$it' in '$text'", text.contains(it)) }
    }

    @Test
    fun `server answers say whether the stream is refused, gone or broken`() {
        says(http(403), "refused", "403", "country")
        says(http(404), "isn't at its address", "404")
        says(http(502), "server has a problem", "502")
    }

    @Test
    fun `network and format failures say which side the problem is on`() {
        says(failure(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED, UnknownHostException("x")), "can't be found")
        says(failure(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED), "Couldn't connect")
        says(failure(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT), "didn't answer")
        says(failure(PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED), "This TV box can't decode")
        says(failure(PlaybackException.ERROR_CODE_DRM_LICENSE_ACQUISITION_FAILED), "encrypted")
        says(failure(PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED), "can't read")
        says(failure(PlaybackException.ERROR_CODE_UNSPECIFIED), "ERROR_CODE_UNSPECIFIED")
    }
}
