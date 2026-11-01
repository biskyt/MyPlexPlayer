package com.biskyt.myplexplayer.ui.vod

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.biskyt.myplexplayer.R
import com.biskyt.myplexplayer.appPlural
import com.biskyt.myplexplayer.appString
import com.biskyt.myplexplayer.core.plex.PlexAutoDelete
import com.biskyt.myplexplayer.data.VodRepository
import com.biskyt.myplexplayer.ui.common.ItemAction
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * A series' "Delete watched episodes" while its menu is open: read from its server, stepped with left and right, and
 * saved to Plex once the remote rests on a choice (one request, not one a press). Plex not taking it puts it back.
 */
class AutoDeleteChoice(
    private val scope: CoroutineScope,
    private val vod: VodRepository,
    private val playlistId: Long,
    private val seriesId: Long,
) {
    /** As shown; null until read, or when it can't be (then the menu leaves it out). */
    var value by mutableStateOf<PlexAutoDelete?>(null)
        private set
    private var saved: PlexAutoDelete? = null
    private var waiting: Job? = null
    private val sending = Mutex()

    init {
        scope.launch {
            saved = vod.autoDeleteWatched(playlistId, seriesId)
            value = saved
        }
    }

    fun step(by: Int) {
        val now = value ?: return
        val choices = PlexAutoDelete.entries
        val next = choices[(now.ordinal + by).mod(choices.size)]
        value = next
        waiting?.cancel()
        waiting = scope.launch {
            delay(SAVE_AFTER_MS)
            // Once sent, a newer press waits for it rather than cancelling it halfway.
            withContext(NonCancellable) {
                sending.withLock {
                    if (next == saved) return@withLock
                    if (vod.setAutoDeleteWatched(playlistId, seriesId, next)) {
                        saved = next
                    } else if (value == next) {
                        value = saved
                    }
                }
            }
        }
    }

    /** Its row in a menu, once read. */
    fun action(): ItemAction? = value?.let {
        ItemAction.Cycle(appString(R.string.vod_auto_delete), autoDeleteName(it), ::step)
    }

    private companion object {
        const val SAVE_AFTER_MS = 800L
    }
}

fun autoDeleteName(policy: PlexAutoDelete): String = appString(
    when (policy) {
        PlexAutoDelete.NEVER -> R.string.vod_auto_delete_never
        PlexAutoDelete.AFTER_A_DAY -> R.string.vod_auto_delete_day
        PlexAutoDelete.AFTER_A_WEEK -> R.string.vod_auto_delete_week
        PlexAutoDelete.AFTER_A_MONTH -> R.string.vod_auto_delete_month
        PlexAutoDelete.ON_NEXT_REFRESH -> R.string.vod_auto_delete_refresh
    }
)

/**
 * "Delete the series…", asking first whether to delete series [title] from the server with its episodes, files and
 * all: how many, when known.
 */
fun deleteSeriesAction(title: String, episodes: Int?, delete: () -> Unit) = ItemAction.Confirmed(
    appString(R.string.vod_delete_series),
    if (episodes != null && episodes > 0) {
        appPlural(R.plurals.vod_delete_series_confirm, episodes, title, episodes)
    } else {
        appString(R.string.vod_delete_series_confirm_all, title)
    },
    appString(R.string.vod_delete_yes),
    delete
)

/** "Delete…", asking first whether to delete [title] from the server, file and all. */
fun deleteAction(title: String, delete: () -> Unit) = ItemAction.Confirmed(
    appString(R.string.vod_delete),
    appString(R.string.vod_delete_confirm, title),
    appString(R.string.vod_delete_yes),
    delete
)
