package com.biskyt.mytvplayer.ui.settings

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.biskyt.mytvplayer.ui.common.Muted
import com.biskyt.mytvplayer.ui.common.PillButton
import com.biskyt.mytvplayer.ui.common.QrCode
import com.biskyt.mytvplayer.ui.common.SidePanel
import com.biskyt.mytvplayer.ui.common.rememberFirstFocus

/** Where to support the app: the name on its button, and the page's address (also in README.md and .github/FUNDING.yml). */
enum class SupportLink(val label: String, val url: String) {
    KO_FI("Ko-fi", "https://ko-fi.com/biskyt"),
    GITHUB_SPONSORS("GitHub Sponsors", "https://github.com/sponsors/biskyt")
}

/** At the foot of Settings: how to support the app. Each button shows a QR code to finish on a phone. */
@Composable
fun SupportSection() {
    var shown by remember { mutableStateOf<SupportLink?>(null) }
    Column(Modifier.padding(top = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Support MyTVPlayer", style = MaterialTheme.typography.titleLarge)
        Muted("It's free, with no ads and no tracking. If you find it useful, you can support its development.", maxLines = 2)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SupportLink.entries.forEach { link -> PillButton(link.label, { shown = link }) }
        }
    }
    shown?.let { link -> SupportPanel(link) { shown = null } }
}

/** A QR code to scan with a phone (paying on a TV isn't for everyone), with the address and a way to open it here. */
@Composable
private fun SupportPanel(link: SupportLink, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var problem by remember { mutableStateOf<String?>(null) }
    SidePanel(onDismiss) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(link.label, style = MaterialTheme.typography.titleLarge)
            Muted("Scan with your phone's camera to open the page there.", maxLines = 2)
            QrCode(link.url, Modifier.size(240.dp))
            Text(link.url.removePrefix("https://"), style = MaterialTheme.typography.titleSmall)
            PillButton("Open on this TV", {
                try {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link.url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (_: ActivityNotFoundException) {
                    problem = "This TV has no web browser. Scan the code with your phone instead."
                } catch (@Suppress("TooGenericExceptionCaught") e: RuntimeException) {
                    // Some TVs refuse in other ways (e.g. a browser that's switched off): never a crash.
                    problem = "The page couldn't be opened on this TV (${e.javaClass.simpleName}). Scan the code with your phone instead."
                }
            }, rememberFirstFocus())
            problem?.let { Muted(it, maxLines = 2) }
        }
    }
}
