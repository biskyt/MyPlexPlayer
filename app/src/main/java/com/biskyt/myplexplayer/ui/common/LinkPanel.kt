package com.biskyt.myplexplayer.ui.common

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.biskyt.myplexplayer.R
import com.biskyt.myplexplayer.appString

/**
 * A web page as a QR code to scan with a phone (paying or reading on a TV isn't for everyone), with its address, an
 * optional [note], and a way to open it here.
 */
@Composable
fun LinkPanel(title: String, url: String, note: String? = null, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var problem by remember { mutableStateOf<String?>(null) }
    SidePanel(onDismiss) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            Muted(stringResource(R.string.common_link_scan_hint), maxLines = 2)
            QrCode(url, Modifier.size(240.dp))
            Text(url.removePrefix("https://"), style = MaterialTheme.typography.titleSmall)
            note?.let { Muted(it, maxLines = 2) }
            PillButton(stringResource(R.string.common_link_open_here), {
                try {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (_: ActivityNotFoundException) {
                    problem = appString(R.string.common_link_no_browser)
                } catch (@Suppress("TooGenericExceptionCaught") e: RuntimeException) {
                    // Some TVs refuse in other ways (e.g. a browser that's switched off): never a crash.
                    problem = appString(R.string.common_link_open_failed, e.javaClass.simpleName)
                }
            }, rememberFirstFocus())
            problem?.let { Muted(it, maxLines = 2) }
        }
    }
}
