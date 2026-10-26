package com.biskyt.myplexplayer.ui.vod

import android.text.format.Formatter
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.biskyt.myplexplayer.AppContainer
import com.biskyt.myplexplayer.AppText
import com.biskyt.myplexplayer.R
import com.biskyt.myplexplayer.appString
import com.biskyt.myplexplayer.core.plex.PlexMediaInfo
import com.biskyt.myplexplayer.core.vod.VodDetails
import com.biskyt.myplexplayer.data.db.CAT_MOVIE
import com.biskyt.myplexplayer.ui.Screen
import com.biskyt.myplexplayer.ui.appViewModel
import com.biskyt.myplexplayer.ui.common.LinksRow
import com.biskyt.myplexplayer.ui.common.Muted
import com.biskyt.myplexplayer.ui.common.PillButton
import com.biskyt.myplexplayer.ui.common.SectionLabel
import com.biskyt.myplexplayer.ui.common.formatDate
import com.biskyt.myplexplayer.ui.common.formatRuntime
import com.biskyt.myplexplayer.ui.common.rememberFirstFocus
import com.biskyt.myplexplayer.ui.theme.Palette
import java.io.IOException
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.launch

/**
 * A film's or series' details beyond its page: who made it, when it came out and arrived, its file (a film's), and
 * its pages elsewhere. Kept from when its page opened, so this asks the server for nothing.
 */
@Composable
fun MoreInfoScreen(screen: Screen.MoreInfo) {
    val vm = appViewModel { MoreInfoViewModel(it, screen) }
    vm.error?.let { error ->
        Row(
            Modifier.fillMaxSize().background(Palette.background).padding(horizontal = 72.dp, vertical = 48.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Muted(error, maxLines = 3)
            PillButton(stringResource(R.string.common_retry), vm::load, rememberFirstFocus())
        }
        return
    }
    val (title, d) = vm.loaded ?: return
    val film = screen.type == CAT_MOVIE
    Column(
        Modifier.fillMaxSize().background(Palette.background).verticalScroll(rememberScrollState())
            .padding(horizontal = 72.dp, vertical = 48.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(title, style = MaterialTheme.typography.headlineMedium)
        Muted(
            listOfNotNull(
                d.year?.toString(),
                d.durationSec?.takeIf {
                    it > 0
                }?.let(::formatRuntime),
                d.contentRating
            ).joinToString(" · ")
        )
        Text(stringResource(R.string.vod_more_info), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 8.dp))
        SectionLabel(stringResource(if (film) R.string.vod_about_film else R.string.vod_about_show))
        Facts(about(d))
        d.media?.let {
            SectionLabel(stringResource(R.string.vod_file_info))
            Facts(fileInfo(LocalContext.current, it))
        }
        if (d.links.isNotEmpty()) {
            SectionLabel(stringResource(R.string.vod_links))
            LinksRow(null, d.links, rememberFirstFocus())
        }
    }
}

class MoreInfoViewModel(private val c: AppContainer, private val screen: Screen.MoreInfo) : ViewModel() {
    var loaded by mutableStateOf<Pair<String, VodDetails>?>(null)
        private set

    /** Why the details couldn't be read (the server out of reach, say), until Retry. */
    var error by mutableStateOf<String?>(null)
        private set

    init {
        load()
    }

    fun load() {
        error = null
        viewModelScope.launch {
            val p = c.playlists.get(screen.playlistId) ?: return@launch
            try {
                loaded = if (screen.type == CAT_MOVIE) {
                    c.vod.movie(p.id, screen.id)?.title.orEmpty() to c.vod.movieDetails(p, screen.id, withState = false)
                } else {
                    c.vod.series(p.id, screen.id)?.title.orEmpty() to c.vod.seriesDetails(p, screen.id, withState = false).info
                }
            } catch (e: IOException) {
                error = e.message ?: appString(R.string.vod_episodes_failed)
            }
        }
    }
}

/** Label and value, one to a line, those with a value only. */
@Composable
private fun Facts(facts: List<Pair<String, String?>>) {
    facts.forEach { (label, value) ->
        if (value.isNullOrBlank()) return@forEach
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.widthIn(max = 900.dp)) {
            Muted(label, modifier = Modifier.width(160.dp))
            Text(value, style = MaterialTheme.typography.bodyLarge)
        }
    }
}

private fun about(d: VodDetails): List<Pair<String, String?>> = listOf(
    appString(R.string.vod_original_title) to d.originalTitle,
    appString(R.string.vod_released) to d.released?.let(::date),
    appString(R.string.vod_studio) to d.studio,
    appString(R.string.vod_countries) to d.countries.list(),
    appString(R.string.vod_producers) to d.producers.list(),
    appString(R.string.vod_collections) to d.collections.list(),
    appString(R.string.vod_added) to d.addedAt?.let { formatDate(it * MS_PER_S) },
)

private fun fileInfo(context: android.content.Context, m: PlexMediaInfo): List<Pair<String, String?>> = listOf(
    appString(R.string.vod_picture) to m.picture,
    appString(R.string.vod_sound) to
        m.sound?.let { if (m.otherSoundtracks > 0) appString(R.string.vod_more_soundtracks, it, m.otherSoundtracks) else it },
    appString(R.string.vod_subtitles) to
        m.subtitles.joinToString(", ") { (name, forced) -> if (forced) appString(R.string.vod_forced, name) else name }
            .ifEmpty { null },
    appString(R.string.vod_size) to listOfNotNull(
        m.sizeBytes?.let { Formatter.formatFileSize(context, it) },
        m.bitrateKbps?.let { "%.0f Mbps".format(AppText.locale, it / KBPS_PER_MBPS) }
    ).joinToString(" · ").ifEmpty { null },
    appString(R.string.vod_container) to m.container,
)

private fun List<String>.list() = takeIf { it.isNotEmpty() }?.joinToString(", ")

/** "2019-03-14" as the app writes dates. */
private fun date(iso: String): String? = runCatching {
    formatDate(LocalDate.parse(iso.take(ISO_DATE)).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli())
}.getOrNull()

private const val ISO_DATE = 10
private const val MS_PER_S = 1000L
private const val KBPS_PER_MBPS = 1000.0
