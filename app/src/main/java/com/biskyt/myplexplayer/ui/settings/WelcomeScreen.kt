package com.biskyt.myplexplayer.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.biskyt.myplexplayer.R
import com.biskyt.myplexplayer.ui.LocalNavigator
import com.biskyt.myplexplayer.ui.Screen
import com.biskyt.myplexplayer.ui.common.Muted
import com.biskyt.myplexplayer.ui.common.PillButton
import com.biskyt.myplexplayer.ui.common.rememberFirstFocus
import com.biskyt.myplexplayer.ui.theme.Palette

/** First launch: sign in to Plex (or add a provider, or restore a backup). */
@Composable
fun WelcomeScreen() {
    val nav = LocalNavigator.current
    Column(
        Modifier.fillMaxSize().background(Palette.background).padding(horizontal = 96.dp, vertical = 72.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Text(stringResource(R.string.settings_welcome_title), style = MaterialTheme.typography.displaySmall)
        Muted(
            stringResource(R.string.settings_welcome_intro),
            Modifier.widthIn(max = 820.dp),
            maxLines = 3,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            PillButton(
                stringResource(R.string.settings_plex_sign_in),
                { nav.replace(Screen.PlexSignIn(firstRun = true)) },
                rememberFirstFocus(),
                primary = true,
            )
            PillButton(stringResource(R.string.settings_welcome_add_provider), { nav.replace(Screen.PlaylistEdit(null, firstRun = true)) })
            // After reinstalling: everything back from a backup file.
            PillButton(stringResource(R.string.settings_welcome_restore), { nav.push(Screen.Backup) })
        }
    }
}
