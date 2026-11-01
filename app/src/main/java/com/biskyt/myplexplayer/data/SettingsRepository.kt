package com.biskyt.myplexplayer.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.biskyt.myplexplayer.core.player.SubtitleMode
import com.biskyt.myplexplayer.core.ui.AccentPalette
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

enum class BufferSize { SMALL, NORMAL, LARGE }

/** Serializable for backups (see BackupRepository). */
@Serializable
data class Settings(
    val activePlaylistId: Long = 0,
    val skipBackSec: Int = 10,
    val skipForwardSec: Int = 30,
    val audioPassthrough: Boolean = true,
    val subtitleMode: SubtitleMode = SubtitleMode.FORCED_ONLY,
    /** Empty means "follow the TV's system language". */
    val subtitleLanguage: String = "",
    val bufferSize: BufferSize = BufferSize.NORMAL,
    val startOnLastChannel: Boolean = false,
    /** Up and down on the remote change channel while watching (else only the channel buttons do). */
    val zapWithUpDown: Boolean = false,
    val lastChannelId: Long = 0,
    val accent: Int = AccentPalette.DEFAULT,
    val largeText: Boolean = false,
    /** Seconds the Skip pill counts down before skipping an intro by itself; [AUTO_SKIP_OFF]: only when pressed. */
    val autoSkipIntroSec: Int = 3,
    /**
     * The same for credits: off to start with (the owner: credits aren't skipped unless asked to). Named anew in 0.8.1,
     * here and in backups, so the 3 that 0.8.0 saved with every setting isn't read as chosen.
     */
    @SerialName("autoSkipCreditsAfterSec") val autoSkipCreditsSec: Int = AUTO_SKIP_OFF,
    /** Show in the top bar whether Android reports a VPN (see VpnMonitor). */
    val warnVpn: Boolean = false
) {
    val effectiveSubtitleLanguage: String get() = subtitleLanguage.ifEmpty { Locale.getDefault().language }

    companion object {
        const val AUTO_SKIP_OFF = -1
    }
}

internal val Context.dataStore by preferencesDataStore("settings")

class SettingsRepository(private val context: Context) {
    private object K {
        val activePlaylist = longPreferencesKey("active_playlist")
        val skipBack = intPreferencesKey("skip_back")
        val skipForward = intPreferencesKey("skip_forward")
        val passthrough = booleanPreferencesKey("audio_passthrough")
        val subtitleMode = stringPreferencesKey("subtitle_mode")
        val subtitleLang = stringPreferencesKey("subtitle_lang")
        val buffer = stringPreferencesKey("buffer")
        val startLast = booleanPreferencesKey("start_last")
        val zapUpDown = booleanPreferencesKey("zap_up_down")
        val lastChannel = longPreferencesKey("last_channel")
        val accent = intPreferencesKey("accent")
        val largeText = booleanPreferencesKey("large_text")
        val autoSkipIntro = intPreferencesKey("auto_skip_intro")
        val autoSkipCredits = intPreferencesKey("auto_skip_credits_after")
        val warnVpn = booleanPreferencesKey("warn_vpn")
    }

    val settings: Flow<Settings> = context.dataStore.data.map { it.toSettings() }.distinctUntilChanged()

    suspend fun current(): Settings = settings.first()

    /** The playlist in use; 0 (none yet) is skipped so screens never query for it. */
    val activePlaylistId: Flow<Long> = settings.map { it.activePlaylistId }.filter { it != 0L }.distinctUntilChanged()

    /**
     * How Plex tells this install apart from the user's other devices: made once and kept. Not in [Settings], so a
     * backup restored on another device doesn't take it there.
     */
    suspend fun plexClientId(): String {
        var id = ""
        context.dataStore.edit { prefs -> id = prefs[PLEX_CLIENT_ID] ?: UUID.randomUUID().toString().also { prefs[PLEX_CLIENT_ID] = it } }
        return id
    }

    /** The Plex account's token, for its server list; null when not signed in. Not in [Settings] (nor backups). */
    suspend fun plexToken(): String? = context.dataStore.data.first()[PLEX_TOKEN]

    suspend fun setPlexToken(token: String?) {
        context.dataStore.edit { if (token == null) it.remove(PLEX_TOKEN) else it[PLEX_TOKEN] = token }
    }

    private fun Preferences.toSettings(): Settings {
        val d = Settings()
        return Settings(
            activePlaylistId = this[K.activePlaylist] ?: d.activePlaylistId,
            skipBackSec = this[K.skipBack] ?: d.skipBackSec,
            skipForwardSec = this[K.skipForward] ?: d.skipForwardSec,
            audioPassthrough = this[K.passthrough] ?: d.audioPassthrough,
            subtitleMode = enumOr(this[K.subtitleMode], d.subtitleMode),
            subtitleLanguage = this[K.subtitleLang] ?: d.subtitleLanguage,
            bufferSize = enumOr(this[K.buffer], d.bufferSize),
            startOnLastChannel = this[K.startLast] ?: d.startOnLastChannel,
            zapWithUpDown = this[K.zapUpDown] ?: d.zapWithUpDown,
            lastChannelId = this[K.lastChannel] ?: d.lastChannelId,
            accent = this[K.accent] ?: d.accent,
            largeText = this[K.largeText] ?: d.largeText,
            autoSkipIntroSec = this[K.autoSkipIntro] ?: d.autoSkipIntroSec,
            autoSkipCreditsSec = this[K.autoSkipCredits] ?: d.autoSkipCreditsSec,
            warnVpn = this[K.warnVpn] ?: d.warnVpn,
        )
    }

    suspend fun update(transform: (Settings) -> Settings) {
        context.dataStore.edit { prefs ->
            val s = transform(prefs.toSettings())
            // Where Live TV opened, until 1.2.1 (it now opens on Favourites each time), and the live stream format, an
            // Xtream setting (until 0.6.0), with what each Xtream account fell back to.
            prefs.remove(stringPreferencesKey("last_live_filter"))
            prefs.remove(stringPreferencesKey("stream_format"))
            prefs.asMap().keys.filter { it.name.startsWith(FORMAT_FOR) }.forEach { prefs.remove(it) }
            prefs.remove(intPreferencesKey("auto_skip_credits")) // 0.8.0's, saved as 3 whether chosen or not
            prefs[K.activePlaylist] = s.activePlaylistId
            prefs[K.skipBack] = s.skipBackSec
            prefs[K.skipForward] = s.skipForwardSec
            prefs[K.passthrough] = s.audioPassthrough
            prefs[K.subtitleMode] = s.subtitleMode.name
            prefs[K.subtitleLang] = s.subtitleLanguage
            prefs[K.buffer] = s.bufferSize.name
            prefs[K.startLast] = s.startOnLastChannel
            prefs[K.zapUpDown] = s.zapWithUpDown
            prefs[K.lastChannel] = s.lastChannelId
            prefs[K.accent] = s.accent
            prefs[K.largeText] = s.largeText
            prefs[K.autoSkipIntro] = s.autoSkipIntroSec
            prefs[K.autoSkipCredits] = s.autoSkipCreditsSec
            prefs[K.warnVpn] = s.warnVpn
        }
    }

    private inline fun <reified E : Enum<E>> enumOr(name: String?, default: E): E = enumOrNull<E>(name) ?: default

    private inline fun <reified E : Enum<E>> enumOrNull(name: String?): E? = name?.let { n -> enumValues<E>().firstOrNull { it.name == n } }

    private companion object {
        const val FORMAT_FOR = "stream_format_for_"
        val PLEX_CLIENT_ID = stringPreferencesKey("plex_client_id")
        val PLEX_TOKEN = stringPreferencesKey("plex_token")
    }
}
