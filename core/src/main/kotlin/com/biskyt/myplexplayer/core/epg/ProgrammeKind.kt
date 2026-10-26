package com.biskyt.myplexplayer.core.epg

/** What a guide programme is: used to offer "Find series" or "Find in Movies" for it. */
object ProgrammeKind {
    const val UNKNOWN = 0
    const val SERIES = 1
    const val MOVIE = 2

    /** News, sport and the like: neither a series nor a film. */
    const val OTHER = 3
}
