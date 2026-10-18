package com.biskyt.mytvplayer.ui.vod

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.biskyt.mytvplayer.AppContainer
import com.biskyt.mytvplayer.core.xtream.EpisodeInfo
import com.biskyt.mytvplayer.core.xtream.SeriesDetails
import com.biskyt.mytvplayer.data.db.CAT_SERIES
import com.biskyt.mytvplayer.data.db.SeriesEntity
import com.biskyt.mytvplayer.data.db.WatchProgressEntity
import com.biskyt.mytvplayer.ui.LocalNavigator
import com.biskyt.mytvplayer.ui.Screen
import com.biskyt.mytvplayer.ui.appViewModel
import com.biskyt.mytvplayer.ui.common.Muted
import com.biskyt.mytvplayer.ui.common.PillButton
import com.biskyt.mytvplayer.ui.common.ProgressLine
import com.biskyt.mytvplayer.ui.common.TvCard
import com.biskyt.mytvplayer.ui.common.formatRuntime
import com.biskyt.mytvplayer.ui.common.rememberFirstFocus
import com.biskyt.mytvplayer.ui.player.shortEpisodeLabel
import com.biskyt.mytvplayer.ui.theme.LocalAccent
import com.biskyt.mytvplayer.ui.theme.Palette
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SeriesDetailsViewModel(private val c: AppContainer, private val playlistId: Long, private val seriesId: Long) : ViewModel() {
    var series by mutableStateOf<SeriesEntity?>(null)
        private set
    var details by mutableStateOf<SeriesDetails?>(null)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    /** The season the user picked (until then, the one with the episode to carry on with). */
    var season by mutableStateOf<Int?>(null)

    /** Progress per episode id. */
    val progress = c.vod.observeSeriesProgress(playlistId, seriesId).map { list -> list.associateBy { it.itemId } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())
    val favourite = c.vod.observeFavourite(playlistId, CAT_SERIES, seriesId).stateIn(viewModelScope, SharingStarted.Eagerly, false)

    init {
        load()
    }

    fun load() = viewModelScope.launch {
        error = null
        series = c.vod.series(playlistId, seriesId)
        val p = c.playlists.get(playlistId) ?: return@launch
        try {
            val d = c.vod.seriesDetails(p, seriesId)
            details = d
        } catch (e: java.io.IOException) {
            error = e.message ?: "Couldn't load the episodes"
        }
    }

    fun toggleFavourite() = viewModelScope.launch { c.vod.setFavourite(playlistId, CAT_SERIES, seriesId, !favourite.value) }
}

/**
 * Where to carry on: the last episode watched if it isn't finished, otherwise the one after it; the first episode
 * when nothing has been watched yet.
 */
fun continueEpisode(episodes: List<EpisodeInfo>, progress: Map<Long, WatchProgressEntity>): EpisodeInfo? {
    val last = progress.values.maxByOrNull { it.updatedAt } ?: return episodes.firstOrNull()
    val index = episodes.indexOfFirst { it.id == last.itemId }
    if (index < 0) return episodes.firstOrNull()
    return if (last.finished) episodes.getOrNull(index + 1) ?: episodes[index] else episodes[index]
}

@Composable
fun SeriesDetailsScreen(screen: Screen.SeriesDetails) {
    val vm = appViewModel { SeriesDetailsViewModel(it, screen.playlistId, screen.seriesId) }
    val nav = LocalNavigator.current
    val show = vm.series ?: return
    val d = vm.details
    val progress by vm.progress.collectAsState()
    val favourite by vm.favourite.collectAsState()
    val play = { e: EpisodeInfo -> nav.push(Screen.VodPlayer(screen.playlistId, e.id, screen.seriesId)) }

    DetailsLayout(backdropUrl = d?.info?.backdropUrl, posterUrl = show.coverUrl ?: d?.info?.posterUrl, title = show.title) {
        Text(show.title, style = MaterialTheme.typography.displaySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Muted(metaLine(show.year ?: d?.info?.year, null, d?.info?.genre, show.rating ?: d?.info?.rating))
        d?.info?.plot?.let { Text(it, style = MaterialTheme.typography.bodyLarge, maxLines = 3, overflow = TextOverflow.Ellipsis) }
        val next = d?.let { continueEpisode(it.episodes, progress) }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (next != null) {
                val started = progress.isNotEmpty()
                PillButton(
                    (if (started) "Continue: " else "Play ") + shortEpisodeLabel(next.season, next.number),
                    { play(next) },
                    rememberFirstFocus(next.id),
                    primary = true
                )
            }
            PillButton(if (favourite) "Remove from favourites" else "Add to favourites", { vm.toggleFavourite() })
        }
        when {
            vm.error != null -> Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Muted(vm.error.orEmpty())
                PillButton("Try again", { vm.load() }, if (next == null) rememberFirstFocus() else Modifier)
            }
            d == null -> Muted("Loading episodes…")
            d.episodes.isEmpty() -> Muted("No episodes are available for this series.")
            else -> Episodes(vm, d, vm.season ?: next?.season ?: d.seasons.firstOrNull()?.number, progress, play)
        }
    }
}

@Composable
private fun Episodes(
    vm: SeriesDetailsViewModel,
    d: SeriesDetails,
    season: Int?,
    progress: Map<Long, WatchProgressEntity>,
    play: (EpisodeInfo) -> Unit
) {
    if (d.seasons.size > 1) {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(d.seasons, key = { it.number }) { s ->
                PillButton(s.name, { vm.season = s.number }, selected = s.number == season)
            }
        }
    }
    val shown = remember(d, season) { d.episodes.filter { season == null || it.season == season } }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
        items(shown, key = { it.id }) { e -> EpisodeRow(e, progress[e.id]) { play(e) } }
    }
}

@Composable
private fun EpisodeRow(e: EpisodeInfo, progress: WatchProgressEntity?, onClick: () -> Unit) {
    TvCard(onClick = onClick, color = Palette.surfaceLow, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("${e.number}", style = MaterialTheme.typography.titleSmall, color = Palette.textDim, modifier = Modifier.width(36.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(e.title, style = MaterialTheme.typography.titleSmall, maxLines = 1)
                e.plot?.let { Muted(it) }
                if (progress != null && !progress.finished && progress.durationMs > 0) {
                    ProgressLine(progress.fraction, Modifier.width(220.dp).padding(top = 4.dp))
                }
            }
            val duration = e.durationSec
            when {
                progress?.finished == true -> Text(
                    "Watched",
                    style = MaterialTheme.typography.labelMedium,
                    color = LocalAccent.current.color
                )
                duration != null -> Muted(formatRuntime(duration))
            }
        }
    }
}
