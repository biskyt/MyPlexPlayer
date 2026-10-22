package com.biskyt.mytvplayer

import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.content.res.Resources
import android.os.LocaleList
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * The app's language and its words for code outside composables (view models, repositories, the player). Composables
 * use stringResource / pluralStringResource under [InAppLanguage], which gives them the same language.
 */
object AppText {
    /** The languages the app has words for, by tag; the first is the fallback. */
    val languages = listOf("en-GB", "en-US", "es", "pt-BR", "fr")

    /** Saved here rather than in Settings: the language is needed before anything else, without waiting. */
    private const val PREFS = "app_language"
    private const val KEY = "language"

    @Volatile private var resources: Resources? = null

    /** The chosen language's tag, or "" to follow the device. Screens follow it as it changes. */
    internal val chosen = MutableStateFlow("")

    /** Called by the application at start, when the device's settings change, and by [setLanguage]. */
    fun init(context: Context) {
        val app = context.applicationContext ?: context
        // A language no longer offered counts as none: the device's is followed.
        val tag = prefs(app).getString(KEY, "").orEmpty().takeIf { it in languages }.orEmpty()
        resources = inLanguage(app, tag).resources
        chosen.value = tag // After the words, so whoever sees the new language gets its words too.
    }

    internal val res: Resources get() = checkNotNull(resources) { "AppText.init wasn't called" }

    /** The language the app is shown in: dates, numbers and language names are written in it too. */
    val locale: Locale get() = res.configuration.locales[0]

    /** Saves [tag] ("" to follow the device); the screens switch to it straight away. */
    fun setLanguage(context: Context, tag: String) {
        prefs(context).edit().putString(KEY, tag).apply()
        init(context)
    }

    /**
     * [context] (in the device's languages) showing the app's language: only the locale changes, so everything else
     * (screen size, night mode) still follows the device.
     */
    internal fun inLanguage(context: Context, tag: String = chosen.value): Context {
        val locale = resolve(tag, context.resources.configuration.locales)
        return context.createConfigurationContext(Configuration().apply { setLocales(LocaleList(locale)) })
    }

    /**
     * The language to show: [chosen] if there is one, else the first of the device's languages the app has (by
     * language and country, else language alone: Canadian French is French), else UK English.
     */
    internal fun resolve(chosen: String, device: LocaleList): Locale {
        if (chosen in languages) return Locale.forLanguageTag(chosen)
        return (0 until device.size()).map { device[it] }.firstNotNullOfOrNull { wanted ->
            supported.firstOrNull { it.language == wanted.language && it.country == wanted.country }
                ?: supported.firstOrNull { it.language == wanted.language }
        } ?: supported.first()
    }

    private val supported = languages.map(Locale::forLanguageTag)

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

/**
 * [content] in the app's language. The Activity keeps its own context (to start other apps, ask for permissions), with
 * the app language's resources; it changes in place when the language is changed in Settings.
 */
@Composable
fun InAppLanguage(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val device = LocalConfiguration.current
    val tag by AppText.chosen.collectAsState()
    val wrapped = remember(context, device, tag) {
        val resources = AppText.inLanguage(context).resources
        object : ContextWrapper(context) {
            override fun getResources(): Resources = resources
        }
    }
    CompositionLocalProvider(LocalContext provides wrapped, LocalConfiguration provides wrapped.resources.configuration, content = content)
}

/** The string [id], with [args] filled in when given (a string with no args is used as is, % and all). */
fun appString(@StringRes id: Int, vararg args: Any): String =
    if (args.isEmpty()) AppText.res.getString(id) else AppText.res.getString(id, *args)

/** The plural [id] for [count], with [args] filled in ([count] itself when none are given). */
fun appPlural(@PluralsRes id: Int, count: Int, vararg args: Any): String =
    if (args.isEmpty()) AppText.res.getQuantityString(id, count, count) else AppText.res.getQuantityString(id, count, *args)
