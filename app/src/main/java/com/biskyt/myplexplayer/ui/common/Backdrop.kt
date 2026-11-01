package com.biskyt.myplexplayer.ui.common

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.allowRgb565
import com.biskyt.myplexplayer.ui.theme.Palette
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** A title's art, faded behind its page. */
@Composable
fun Backdrop(backdropUrl: String?) {
    if (backdropUrl != null) {
        BackdropImage(backdropUrl, Modifier.testTag("backdrop"))
        BackdropShade()
    }
}

/**
 * A title's art at 35%, filling its space ([onLoaded] once it shows), for under [BackdropShade]. Every page that shows
 * a title's art (its own, Home) loads it here, so each picture is fetched and kept once ([backdropRequest]).
 */
@Composable
fun BackdropImage(url: String, modifier: Modifier = Modifier, onLoaded: () -> Unit = {}) {
    val context = LocalContext.current
    val request = remember(url) { backdropRequest(context, url) }
    AsyncImage(request, null, contentScale = ContentScale.Crop, modifier = modifier.fillMaxSize(), alpha = 0.35f, onSuccess = {
        onLoaded()
    })
}

/** The dark fade over a title's art: darkest on the left, where a page's text is. */
@Composable
fun BackdropShade() = Box(
    Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(Palette.background, Palette.background.copy(alpha = 0.6f))))
)

/**
 * The one request for a title's art at [url], wherever it shows. Faded behind a gradient, a third of the screen's
 * resolution is plenty (and a ninth of the memory). Kept by [backdropCacheKey], so the same picture is one copy in the
 * memory and disk caches, also after the server's token changes.
 */
fun backdropRequest(context: Context, url: String): ImageRequest {
    val key = backdropCacheKey(url)
    return ImageRequest.Builder(context).data(url).size(BACKDROP_WIDTH, BACKDROP_WIDTH * 9 / 16).allowRgb565(true)
        .memoryCacheKey(key).diskCacheKey(key).build()
}

/**
 * What a picture's cache entry is kept by: its server (address and port: two servers can have the same path for two
 * pictures), Plex's path and size for it and the size it's decoded to, but not the server's token.
 */
fun backdropCacheKey(url: String): String {
    val u = url.toHttpUrlOrNull() ?: return url
    val path = u.queryParameter("url") ?: return url
    return "backdrop:${u.host}:${u.port}$path@${u.queryParameter("width")}x${u.queryParameter("height")}/$BACKDROP_WIDTH"
}

private const val BACKDROP_WIDTH = 640
