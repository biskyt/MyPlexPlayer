package com.biskyt.myplexplayer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.biskyt.myplexplayer.R
import com.biskyt.myplexplayer.data.CrashLog
import com.biskyt.myplexplayer.ui.common.BarsAndCutout
import com.biskyt.myplexplayer.ui.common.Muted
import com.biskyt.myplexplayer.ui.common.PillButton
import com.biskyt.myplexplayer.ui.common.QrCode
import com.biskyt.myplexplayer.ui.common.TvCard
import com.biskyt.myplexplayer.ui.common.openLink
import com.biskyt.myplexplayer.ui.common.rememberFirstFocus
import com.biskyt.myplexplayer.ui.theme.Palette
import com.biskyt.myplexplayer.ui.theme.showsQrCodes

/**
 * After a crash, this screen shows what went wrong and a way to send the report. It also has a way in that does not
 * update the server first, as the update can be what crashed. [onContinue] gets whether to update as usual.
 *
 * On a TV, a QR code opens the report on a phone. A phone or tablet does not show the code: it has a button that opens
 * the report on the same device.
 */
@Composable
fun CrashScreen(report: String, onContinue: (updating: Boolean) -> Unit) {
    val issue = remember(report) { CrashLog.issueUrl(report) }
    // A block of lines per card: a few presses of the remote get through even a long report.
    val blocks = remember(report) { report.lines().filter { it.isNotBlank() }.chunked(10).map { it.joinToString("\n") } }
    // Not on a phone or tablet, which would scan the code with itself (the owner, 2026-11-25).
    if (showsQrCodes()) TvCrashScreen(issue, blocks, onContinue) else TouchCrashScreen(issue, blocks, onContinue)
}

@Composable
private fun TvCrashScreen(issue: String, blocks: List<String>, onContinue: (updating: Boolean) -> Unit) {
    Row(
        Modifier.fillMaxSize().background(Palette.background)
            // Clear of a phone's system bars and camera cutout (they show here, before the app decides).
            .windowInsetsPadding(BarsAndCutout)
            .padding(horizontal = 72.dp, vertical = 36.dp),
        horizontalArrangement = Arrangement.spacedBy(40.dp)
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(stringResource(R.string.app_crash_title), style = MaterialTheme.typography.headlineMedium)
            Muted(stringResource(R.string.app_crash_help), maxLines = 3)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PillButton(stringResource(R.string.app_crash_continue), { onContinue(true) }, rememberFirstFocus(), primary = true)
                PillButton(stringResource(R.string.app_crash_continue_no_update), { onContinue(false) })
            }
            ReportBlocks(blocks, Modifier.weight(1f))
        }
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            QrCode(issue, Modifier.size(320.dp))
            Muted(issuesAddress())
        }
    }
}

/**
 * On a phone or tablet: one column, so that every button fits on a narrow screen. The report's button comes first,
 * in place of the TV's QR code. If the report does not open, the problem shows under the buttons.
 */
@Composable
private fun TouchCrashScreen(issue: String, blocks: List<String>, onContinue: (updating: Boolean) -> Unit) {
    val context = LocalContext.current
    var problem by remember { mutableStateOf<String?>(null) }
    Column(
        Modifier.fillMaxSize().background(Palette.background)
            // Clear of a phone's system bars and camera cutout (they show here, before the app decides).
            .windowInsetsPadding(BarsAndCutout)
            .padding(horizontal = 16.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(stringResource(R.string.app_crash_title), style = MaterialTheme.typography.headlineMedium)
        Muted(stringResource(R.string.app_crash_help_touch), maxLines = Int.MAX_VALUE)
        PillButton(stringResource(R.string.app_crash_report), { problem = openLink(context, issue) }, Modifier.fillMaxWidth())
        PillButton(stringResource(R.string.app_crash_continue), { onContinue(true) }, Modifier.fillMaxWidth(), primary = true)
        PillButton(stringResource(R.string.app_crash_continue_no_update), { onContinue(false) }, Modifier.fillMaxWidth())
        problem?.let { Muted(it, maxLines = Int.MAX_VALUE) }
        Muted(issuesAddress(), maxLines = 2)
        ReportBlocks(blocks, Modifier.weight(1f))
    }
}

/** Where the reports go, as a short address with no "https://". */
private fun issuesAddress() = CrashLog.ISSUES.removePrefix("https://").substringBeforeLast('/')

/** The whole report, in focusable blocks so the remote can scroll through it. */
@Composable
private fun ReportBlocks(blocks: List<String>, modifier: Modifier) {
    LazyColumn(modifier.background(Palette.surfaceLow).padding(12.dp)) {
        items(blocks) { block ->
            TvCard(onClick = {}, color = Palette.surfaceLow) {
                Text(
                    block,
                    style = MaterialTheme.typography.bodySmall,
                    color = Palette.textMuted,
                    modifier = Modifier.padding(6.dp)
                )
            }
        }
    }
}
