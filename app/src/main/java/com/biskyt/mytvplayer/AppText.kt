package com.biskyt.mytvplayer

import android.content.Context
import android.content.res.Resources
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import java.util.Locale

/**
 * The app's words for code outside composables (view models, repositories, the player), in the app's language.
 * Composables use stringResource / pluralStringResource instead, so they follow a language change straight away.
 */
object AppText {
    @Volatile private var resources: Resources? = null

    /** Called once by the application; again with new resources when the app's language changes. */
    fun init(context: Context) {
        resources = context.resources
    }

    internal val res: Resources get() = checkNotNull(resources) { "AppText.init wasn't called" }

    /**
     * The language dates and day names are written in. English while the app's words are only English, so a date
     * never mixes with words in another language.
     */
    val locale: Locale get() = Locale.ENGLISH
}

/** The string [id], with [args] filled in when given (a string with no args is used as is, % and all). */
fun appString(@StringRes id: Int, vararg args: Any): String =
    if (args.isEmpty()) AppText.res.getString(id) else AppText.res.getString(id, *args)

/** The plural [id] for [count], with [args] filled in ([count] itself when none are given). */
fun appPlural(@PluralsRes id: Int, count: Int, vararg args: Any): String =
    if (args.isEmpty()) AppText.res.getQuantityString(id, count, count) else AppText.res.getQuantityString(id, count, *args)
