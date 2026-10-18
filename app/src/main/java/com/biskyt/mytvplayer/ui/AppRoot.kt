package com.biskyt.mytvplayer.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import com.biskyt.mytvplayer.container
import com.biskyt.mytvplayer.data.ChannelFilter
import com.biskyt.mytvplayer.data.db.CAT_MOVIE
import com.biskyt.mytvplayer.data.db.CAT_SERIES
import com.biskyt.mytvplayer.ui.guide.GuideMatchingScreen
import com.biskyt.mytvplayer.ui.guide.GuideSourcesScreen
import com.biskyt.mytvplayer.ui.home.HomeScreen
import com.biskyt.mytvplayer.ui.live.LiveScreen
import com.biskyt.mytvplayer.ui.player.PlayerScreen
import com.biskyt.mytvplayer.ui.player.VodPlayerScreen
import com.biskyt.mytvplayer.ui.search.SearchScreen
import com.biskyt.mytvplayer.ui.settings.AboutScreen
import com.biskyt.mytvplayer.ui.settings.AppearanceScreen
import com.biskyt.mytvplayer.ui.settings.CategoriesScreen
import com.biskyt.mytvplayer.ui.settings.PlaybackSettingsScreen
import com.biskyt.mytvplayer.ui.settings.PlaylistEditScreen
import com.biskyt.mytvplayer.ui.settings.PlaylistsScreen
import com.biskyt.mytvplayer.ui.settings.SettingsScreen
import com.biskyt.mytvplayer.ui.settings.WelcomeScreen
import com.biskyt.mytvplayer.ui.theme.MyTvTheme
import com.biskyt.mytvplayer.ui.theme.Palette
import com.biskyt.mytvplayer.ui.vod.MovieDetailsScreen
import com.biskyt.mytvplayer.ui.vod.SeriesDetailsScreen
import com.biskyt.mytvplayer.ui.vod.VodBrowseScreen
import kotlinx.coroutines.flow.first

@Composable
fun AppRoot() {
    val container = LocalContext.current.container
    val settings by container.settings.settings.collectAsState(initial = null)
    val s = settings
    if (s == null) {
        Box(Modifier.fillMaxSize().background(Palette.background))
        return
    }
    // Only the first settings value decides the start screen; later changes (e.g. last channel) mustn't recompose the app.
    val initial = remember { s }
    MyTvTheme(accentArgb = s.accent, largeText = s.largeText) {
        val root = viewModel<RootViewModel>()
        LaunchedEffect(Unit) {
            if (root.navigator != null) return@LaunchedEffect
            // Decide the first screen once: set-up if there is no playlist yet, else Home (or the last channel).
            val s = initial
            val playlist =
                container.playlists.get(s.activePlaylistId)
                    ?: container.playlists.allIds().firstOrNull()?.let { container.playlists.get(it) }
            root.navigator = when {
                playlist == null -> Navigator(Screen.Welcome())
                else -> Navigator(Screen.Home).also { nav ->
                    if (playlist.id != s.activePlaylistId) container.settings.update { it.copy(activePlaylistId = playlist.id) }
                    when {
                        playlist.isStale() -> container.sync.refresh(playlist.id)
                        // Movies and series not loaded yet (e.g. just updated from a version without them).
                        !playlist.isM3u &&
                            container.vod.observeCount(CAT_MOVIE, playlist.id).first() == 0 &&
                            container.vod.observeCount(CAT_SERIES, playlist.id).first() == 0 -> container.sync.refreshVod(playlist.id)
                    }
                    if (s.startOnLastChannel && s.lastChannelId != 0L && container.channels.get(playlist.id, s.lastChannelId) != null) {
                        nav.push(Screen.Player(playlist.id, s.lastChannelId, ChannelFilter.All))
                    }
                }
            }
        }
        val nav = root.navigator ?: run {
            Box(Modifier.fillMaxSize().background(Palette.background))
            return@MyTvTheme
        }
        val activity = LocalActivity.current
        BackHandler {
            when {
                nav.pop() -> Unit
                nav.current.screen != Screen.Home && nav.current.screen !is Screen.Welcome && nav.current.screen !is Screen.PlaylistEdit ->
                    nav.reset(Screen.Home)
                else -> activity?.finish()
            }
        }
        Box(Modifier.fillMaxSize().background(Palette.background)) {
            NavHost(nav) { screen -> ScreenContent(screen) }
        }
    }
}

@Composable
private fun ScreenContent(screen: Screen) {
    when (screen) {
        is Screen.Welcome -> WelcomeScreen(screen.auto)
        Screen.Home -> HomeScreen()
        is Screen.Live -> LiveScreen(screen.filter, screen.guide)
        is Screen.Movies -> VodBrowseScreen(CAT_MOVIE, screen.filter)
        is Screen.Series -> VodBrowseScreen(CAT_SERIES, screen.filter)
        is Screen.MovieDetails -> MovieDetailsScreen(screen)
        is Screen.SeriesDetails -> SeriesDetailsScreen(screen)
        is Screen.VodPlayer -> VodPlayerScreen(screen)
        Screen.Search -> SearchScreen()
        is Screen.Player -> PlayerScreen(screen)
        Screen.Settings -> SettingsScreen()
        Screen.Playlists -> PlaylistsScreen()
        is Screen.PlaylistEdit -> PlaylistEditScreen(screen.playlistId, screen.firstRun)
        is Screen.Categories -> CategoriesScreen(screen.playlistId, screen.firstRun)
        Screen.PlaybackSettings -> PlaybackSettingsScreen()
        Screen.Appearance -> AppearanceScreen()
        Screen.GuideSources -> GuideSourcesScreen()
        is Screen.GuideMatching -> GuideMatchingScreen(screen.playlistId, screen.streamId)
        Screen.About -> AboutScreen()
    }
}

/** Owns the back stack for the activity's lifetime and releases every screen (and the player) with it. */
class RootViewModel : ViewModel() {
    var navigator by mutableStateOf<Navigator?>(null)

    override fun onCleared() {
        navigator?.clear()
    }
}
