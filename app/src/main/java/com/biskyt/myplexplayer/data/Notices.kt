package com.biskyt.myplexplayer.data

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
