package com.biskyt.myplexplayer.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import com.biskyt.myplexplayer.R
import com.biskyt.myplexplayer.ui.LocalNavigator
import com.biskyt.myplexplayer.ui.Screen
import com.biskyt.myplexplayer.ui.common.Muted
import com.biskyt.myplexplayer.ui.common.PillButton
import com.biskyt.myplexplayer.ui.common.rememberFirstFocus
import com.biskyt.myplexplayer.ui.theme.Palette

/** First launch: sign in to Plex (or restore a backup). */
@Composable
fun WelcomeScreen() {
    val nav = LocalNavigator.current
    SettingsColumn(
        Modifier.background(Palette.background),
        spacing = 18.dp,
        tvPadding = PaddingValues(horizontal = 96.dp, vertical = 72.dp)
    ) {
        // The first screen: nothing to go back to.
        SettingsTitle(stringResource(R.string.settings_welcome_title), style = MaterialTheme.typography.displaySmall, onBack = null)
        Muted(
            stringResource(R.string.settings_welcome_intro),
            Modifier.widthIn(max = 820.dp),
            maxLines = touchLines(3, 5),
        )
        ButtonRow {
            PillButton(
                stringResource(R.string.settings_plex_sign_in),
                { nav.replace(Screen.PlexSignIn(firstRun = true)) },
                rememberFirstFocus(),
                primary = true,
            )
            // After reinstalling: everything back from a backup file.
            PillButton(stringResource(R.string.settings_welcome_restore), { nav.push(Screen.Backup) })
        }
    }
}
