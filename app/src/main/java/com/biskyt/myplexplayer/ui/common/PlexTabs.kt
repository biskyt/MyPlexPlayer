package com.biskyt.myplexplayer.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.platform.LocalContext
import com.biskyt.myplexplayer.container
import com.biskyt.myplexplayer.ui.Navigator
import com.biskyt.myplexplayer.ui.Screen

// MyPlexPlayer's own parts of the top bar (TopBar.kt is kept as MyTVPlayer has it).

/** Whether the server in use has Live TV (see AppContainer.liveTvShown). */
@Composable
fun liveTvShown(): Boolean = LocalContext.current.container.liveTvShown.collectAsState().value

/** The top bar's tabs: Live TV and DVR only when the server in use has Live TV (the owner, 2026-11-11). */
@Composable
fun shownTabs(): List<Tab> = if (liveTvShown()) Tab.entries else Tab.entries.filter { it != Tab.LIVE && it != Tab.DVR }

/** On Live TV or DVR when the server turns out to have no Live TV (a switch, or a sync): Home instead. */
@Composable
fun LeaveLiveTvWithoutIt(nav: Navigator) {
    val liveTv = liveTvShown()
    LaunchedEffect(liveTv) {
        val screen = nav.current.screen
        if (!liveTv && (screen is Screen.Live || screen == Screen.Dvr)) nav.reset(Screen.Home)
    }
}
