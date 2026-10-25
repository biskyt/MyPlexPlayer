package com.biskyt.myplexplayer.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import com.biskyt.myplexplayer.AppContainer
import com.biskyt.myplexplayer.container
import com.biskyt.myplexplayer.data.ChannelFilter
import com.biskyt.myplexplayer.data.Settings
import com.biskyt.myplexplayer.data.db.CAT_MOVIE
import com.biskyt.myplexplayer.data.db.CAT_SERIES
import com.biskyt.myplexplayer.ui.common.SyncToast
import com.biskyt.myplexplayer.ui.common.onGuideKey
import com.biskyt.myplexplayer.ui.guide.GuideMatchingScreen
import com.biskyt.myplexplayer.ui.guide.GuideSourcesScreen
import com.biskyt.myplexplayer.ui.home.HomeScreen
import com.biskyt.myplexplayer.ui.live.CatchupScreen
import com.biskyt.myplexplayer.ui.live.LiveScreen
import com.biskyt.myplexplayer.ui.player.CatchupPlayerScreen
import com.biskyt.myplexplayer.ui.player.PlayerScreen
import com.biskyt.myplexplayer.ui.player.VodPlayerScreen
import com.biskyt.myplexplayer.ui.search.SearchScreen
import com.biskyt.myplexplayer.ui.settings.AboutScreen
import com.biskyt.myplexplayer.ui.settings.AppearanceScreen
import com.biskyt.myplexplayer.ui.settings.BackupScreen
import com.biskyt.myplexplayer.ui.settings.CategoriesScreen
import com.biskyt.myplexplayer.ui.settings.LicencesScreen
import com.biskyt.myplexplayer.ui.settings.PlaybackSettingsScreen
import com.biskyt.myplexplayer.ui.settings.PlaylistEditScreen
import com.biskyt.myplexplayer.ui.settings.PlaylistsScreen
import com.biskyt.myplexplayer.ui.settings.PlexSignInScreen
import com.biskyt.myplexplayer.ui.settings.SettingsScreen
import com.biskyt.myplexplayer.ui.settings.WelcomeScreen
import com.biskyt.myplexplayer.ui.theme.MyPlexTheme
import com.biskyt.myplexplayer.ui.theme.Palette
import com.biskyt.myplexplayer.ui.vod.MovieDetailsScreen
import com.biskyt.myplexplayer.ui.vod.SeriesDetailsScreen
import com.biskyt.myplexplayer.ui.vod.VodBrowseScreen
import kotlinx.coroutines.flow.filterNotNull

@Composable
fun AppRoot() {
    val container = LocalContext.current.container
    // A crash last time: shown first, before anything that might crash again runs (see CrashLog).
    val crash = remember { container.crashLog.read() }
    val root = viewModel<RootViewModel>()
    if (crash != null && !root.crashSeen) {
        MyPlexTheme {
            CrashScreen(crash) { updating ->
                container.crashLog.clear()
                root.safeStart = !updating
                root.crashSeen = true
            }
        }
        return
    }
    val settings by container.settings.settings.collectAsState(initial = null)
    val s = settings
    if (s == null) {
        Box(Modifier.fillMaxSize().background(Palette.background))
        return
    }
    // Only the first settings value decides the start screen; later changes (e.g. last channel) mustn't recompose the app.
    val initial = remember { s }
    MyPlexTheme(accentArgb = s.accent, largeText = s.largeText) {
        LaunchedEffect(Unit) {
            if (root.navigator == null) root.navigator = firstScreen(container, initial, root.safeStart)
        }
        val nav = root.navigator ?: run {
            Box(Modifier.fillMaxSize().background(Palette.background))
            return@MyPlexTheme
        }
        // A backup file opened with MyPlexPlayer from another app: Backup & restore offers to restore it.
        LaunchedEffect(nav) {
            container.openedFile.filterNotNull().collect { if (nav.current.screen != Screen.Backup) nav.push(Screen.Backup) }
        }
        val activity = LocalActivity.current
        BackHandler {
            when {
                nav.pop() -> Unit
                nav.current.screen != Screen.Home && nav.current.screen != Screen.Welcome && nav.current.screen !is Screen.PlaylistEdit ->
                    nav.reset(Screen.Home)
                else -> activity?.finish()
            }
        }
        Box(
            Modifier.fillMaxSize().background(Palette.background)
                // Clear of a phone's camera cutout (TVs have none).
                .windowInsetsPadding(WindowInsets.displayCutout)
                .guideButton(nav, hasPlaylist = s.activePlaylistId != 0L)
        ) {
            NavHost(nav) { screen -> ScreenContent(screen) }
            SyncToast(nav, Modifier.align(Alignment.BottomEnd).padding(24.dp))
        }
    }
}

/**
 * The first screen, decided once: set-up if there is no playlist yet, else Home (or the last channel), updating the
 * playlist if it's due. [safeStart] (after a crash): neither the update nor the last channel, in case that's what crashed.
 */
private suspend fun firstScreen(container: AppContainer, s: Settings, safeStart: Boolean): Navigator {
    val playlist = container.playlists.get(s.activePlaylistId)
        ?: container.playlists.allIds().firstOrNull()?.let { container.playlists.get(it) }
        ?: return Navigator(Screen.Welcome)
    val nav = Navigator(Screen.Home)
    if (playlist.id != s.activePlaylistId) container.settings.update { it.copy(activePlaylistId = playlist.id) }
    if (safeStart) return nav
    when {
        playlist.isStale() -> container.sync.refresh(playlist.id)
        // Movies and series not loaded yet (e.g. just updated from a version without them).
        !container.vod.hasTitles(playlist.id) -> container.sync.refreshVod(playlist.id)
    }
    // Not when opened with a backup file: that's what to show (see MainActivity).
    if (s.startOnLastChannel &&
        container.openedFile.value == null &&
        s.lastChannelId != 0L &&
        container.channels.get(playlist.id, s.lastChannelId) != null
    ) {
        nav.push(Screen.Player(playlist.id, s.lastChannelId, ChannelFilter.All))
    }
    return nav
}

/**
 * The remote's Guide button opens the TV guide from anywhere a screen hasn't used it already (the channel player shows
 * the channel's schedule). Opened on top, so Back returns to whatever was there, unchanged.
 */
private fun Modifier.guideButton(nav: Navigator, hasPlaylist: Boolean) =
    onGuideKey({ hasPlaylist && guideButtonOpensGuide(nav.current.screen) }) { nav.push(Screen.Live(guide = true)) }

/**
 * Not while watching something (leaving would stop it), during set-up, or on the live screen (which switches between
 * list and guide itself, wherever focus is on it).
 */
internal fun guideButtonOpensGuide(screen: Screen) = !screen.isPlayback && !screen.isSetup && screen !is Screen.Live

@Composable
internal fun ScreenContent(screen: Screen) {
    when (screen) {
        Screen.Welcome -> WelcomeScreen()
        Screen.Home -> HomeScreen()
        is Screen.Live -> LiveScreen(screen.filter, screen.guide)
        is Screen.Movies -> VodBrowseScreen(CAT_MOVIE, screen.filter)
        is Screen.Series -> VodBrowseScreen(CAT_SERIES, screen.filter)
        is Screen.MovieDetails -> MovieDetailsScreen(screen)
        is Screen.SeriesDetails -> SeriesDetailsScreen(screen)
        is Screen.VodPlayer -> VodPlayerScreen(screen)
        is Screen.Search -> SearchScreen(screen.query)
        is Screen.Player -> PlayerScreen(screen)
        is Screen.Catchup -> CatchupScreen(screen)
        is Screen.CatchupPlayer -> CatchupPlayerScreen(screen)
        Screen.Settings -> SettingsScreen()
        Screen.Playlists -> PlaylistsScreen()
        is Screen.PlaylistEdit -> PlaylistEditScreen(screen.playlistId, screen.firstRun)
        is Screen.PlexSignIn -> PlexSignInScreen(screen.firstRun)
        is Screen.Categories -> CategoriesScreen(screen.playlistId, screen.firstRun)
        Screen.PlaybackSettings -> PlaybackSettingsScreen()
        Screen.Appearance -> AppearanceScreen()
        Screen.GuideSources -> GuideSourcesScreen()
        is Screen.GuideMatching -> GuideMatchingScreen(screen.playlistId, screen.streamId)
        Screen.Backup -> BackupScreen()
        Screen.About -> AboutScreen()
        Screen.Licences -> LicencesScreen()
    }
}

/** Owns the back stack for the activity's lifetime and releases every screen (and the player) with it. */
class RootViewModel : ViewModel() {
    var navigator by mutableStateOf<Navigator?>(null)

    /** The last crash's report has been seen (and dealt with) this time. */
    var crashSeen by mutableStateOf(false)

    /** Chosen after a crash: start without updating the playlist or opening the last channel, in case that crashed. */
    var safeStart = false

    override fun onCleared() {
        navigator?.clear()
    }
}
