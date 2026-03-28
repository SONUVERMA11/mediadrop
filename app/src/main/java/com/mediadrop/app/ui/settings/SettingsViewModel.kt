package com.mediadrop.app.ui.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

// ── Theme mode ────────────────────────────────────────────────────────────────
enum class ThemeMode(val displayName: String) {
    DARK   ("Dark"),
    LIGHT  ("Light"),
    SYSTEM ("Follow System")
}

// ── Save-location options ─────────────────────────────────────────────────────
enum class SaveLocation(val displayName: String, val hint: String) {
    SMART    ("Smart (auto)",    "Videos → Movies, Audio → Music"),
    DOWNLOADS("Downloads/DC/",   "Everything in public Downloads"),
    MOVIES   ("Movies/DC/",      "All files in public Movies folder"),
    MUSIC    ("Music/DC/",       "All files in public Music folder"),
}

data class AppSettings(
    val defaultVideoQuality    : String      = "720p",
    val defaultAudioFormat     : String      = "mp3",
    val maxConcurrentDownloads : Int         = 3,
    val autoClearDays          : Int         = 0,
    val notificationsEnabled   : Boolean     = true,
    val themeMode              : ThemeMode   = ThemeMode.DARK,
    val saveLocation           : SaveLocation= SaveLocation.SMART
)

object PrefKeys {
    val VIDEO_QUALITY   = stringPreferencesKey("default_video_quality")
    val AUDIO_FORMAT    = stringPreferencesKey("default_audio_format")
    val MAX_CONCURRENT  = intPreferencesKey("max_concurrent_downloads")
    val AUTO_CLEAR_DAYS = intPreferencesKey("auto_clear_days")
    val NOTIFICATIONS   = booleanPreferencesKey("notifications_enabled")
    val THEME_MODE      = stringPreferencesKey("theme_mode")
    val SAVE_LOCATION   = stringPreferencesKey("save_location")
}

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val dataStore: DataStore<Preferences>
) : ViewModel() {

    val settings = dataStore.data.map { prefs ->
        AppSettings(
            defaultVideoQuality    = prefs[PrefKeys.VIDEO_QUALITY]   ?: "720p",
            defaultAudioFormat     = prefs[PrefKeys.AUDIO_FORMAT]    ?: "mp3",
            maxConcurrentDownloads = prefs[PrefKeys.MAX_CONCURRENT]  ?: 3,
            autoClearDays          = prefs[PrefKeys.AUTO_CLEAR_DAYS] ?: 0,
            notificationsEnabled   = prefs[PrefKeys.NOTIFICATIONS]   ?: true,
            themeMode              = prefs[PrefKeys.THEME_MODE]?.let {
                runCatching { ThemeMode.valueOf(it) }.getOrNull()
            } ?: ThemeMode.DARK,
            saveLocation           = prefs[PrefKeys.SAVE_LOCATION]?.let {
                runCatching { SaveLocation.valueOf(it) }.getOrNull()
            } ?: SaveLocation.SMART
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())

    fun setVideoQuality(q: String)         = update { it[PrefKeys.VIDEO_QUALITY]   = q }
    fun setAudioFormat(f: String)          = update { it[PrefKeys.AUDIO_FORMAT]    = f }
    fun setMaxConcurrent(n: Int)           = update { it[PrefKeys.MAX_CONCURRENT]  = n }
    fun setAutoClearDays(d: Int)           = update { it[PrefKeys.AUTO_CLEAR_DAYS] = d }
    fun setNotifications(e: Boolean)       = update { it[PrefKeys.NOTIFICATIONS]   = e }
    fun setThemeMode(mode: ThemeMode)      = update { it[PrefKeys.THEME_MODE]      = mode.name }
    fun setSaveLocation(loc: SaveLocation) = update { it[PrefKeys.SAVE_LOCATION]   = loc.name }

    private fun update(block: (MutablePreferences) -> Unit) {
        viewModelScope.launch { dataStore.edit(block) }
    }
}

private typealias MutablePreferences = androidx.datastore.preferences.core.MutablePreferences
