package com.biskyt.myplexplayer.data

import androidx.annotation.StringRes
import com.biskyt.myplexplayer.appString
import java.io.IOException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Something that didn't work, said in the corner of the screen (NoticeToast): a [title] and why ([detail]). */
data class Notice(val title: String, val detail: String?)

/** The notice shown now, if any: the newest replaces one still showing. */
class Notices {
    private val shown = MutableStateFlow<Notice?>(null)
    val current: StateFlow<Notice?> = shown.asStateFlow()

    fun post(notice: Notice) {
        shown.value = notice
    }

    /** Takes [notice] down, unless a newer one has replaced it. */
    fun dismiss(notice: Notice) {
        shown.compareAndSet(notice, null)
    }
}

/** Does [change] on Plex: false, with a notice ([failed] and why), when it can't be reached or refuses. */
suspend fun Notices.inPlex(@StringRes failed: Int, change: suspend () -> Unit): Boolean = try {
    change()
    true
} catch (e: IOException) {
    post(Notice(appString(failed), userMessage(e)))
    false
}
