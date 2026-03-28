package com.mediadrop.app.ui.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class AppSettings(
    val defaultVideoQuality: String = "720p",
    val defaultAudioFormat: String = "mp3",
    val maxConcurrentDownloads: Int = 2,
    val autoClearDays: Int = 0,        // 0 = never, 7 or 30
    val darkMode: String = "system",   // "light","dark","system"
    val notificationsEnabled: Boolean = true
)

object PrefKeys {
    val VIDEO_QUALITY = stringPreferencesKey("default_video_quality")
    val AUDIO_FORMAT = stringPreferencesKey("default_audio_format")
    val MAX_CONCURRENT = intPreferencesKey("max_concurrent_downloads")
    val AUTO_CLEAR_DAYS = intPreferencesKey("auto_clear_days")
    val DARK_MODE = stringPreferencesKey("dark_mode")
    val NOTIFICATIONS = booleanPreferencesKey("notifications_enabled")
}

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val dataStore: DataStore<Preferences>
) : ViewModel() {

    val settings = dataStore.data.map { prefs ->
        AppSettings(
            defaultVideoQuality = prefs[PrefKeys.VIDEO_QUALITY] ?: "720p",
            defaultAudioFormat = prefs[PrefKeys.AUDIO_FORMAT] ?: "mp3",
            maxConcurrentDownloads = prefs[PrefKeys.MAX_CONCURRENT] ?: 2,
            autoClearDays = prefs[PrefKeys.AUTO_CLEAR_DAYS] ?: 0,
            darkMode = prefs[PrefKeys.DARK_MODE] ?: "system",
            notificationsEnabled = prefs[PrefKeys.NOTIFICATIONS] ?: true
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())

    fun setVideoQuality(quality: String) = update { it[PrefKeys.VIDEO_QUALITY] = quality }
    fun setAudioFormat(format: String) = update { it[PrefKeys.AUDIO_FORMAT] = format }
    fun setMaxConcurrent(n: Int) = update { it[PrefKeys.MAX_CONCURRENT] = n }
    fun setAutoClearDays(days: Int) = update { it[PrefKeys.AUTO_CLEAR_DAYS] = days }
    fun setDarkMode(mode: String) = update { it[PrefKeys.DARK_MODE] = mode }
    fun setNotifications(enabled: Boolean) = update { it[PrefKeys.NOTIFICATIONS] = enabled }

    private fun update(block: (MutablePreferences) -> Unit) {
        viewModelScope.launch { dataStore.edit(block) }
    }
}

private typealias MutablePreferences = androidx.datastore.preferences.core.MutablePreferences
