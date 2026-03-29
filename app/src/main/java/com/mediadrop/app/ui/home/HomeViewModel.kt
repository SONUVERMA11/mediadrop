package com.mediadrop.app.ui.home

import android.webkit.URLUtil
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mediadrop.app.domain.model.MediaInfo
import com.mediadrop.app.domain.model.PlaylistInfo
import com.mediadrop.app.domain.model.SupportedPlatform
import com.mediadrop.app.domain.repository.MediaRepository
import com.mediadrop.app.domain.usecase.FetchMediaInfoUseCase
import com.mediadrop.app.domain.usecase.GetDownloadHistoryUseCase
import com.mediadrop.app.domain.usecase.StartDownloadUseCase
import com.mediadrop.app.util.NetworkUtils
import com.mediadrop.app.util.toMediaError
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed class HomeUiState {
    object Idle                                              : HomeUiState()
    object Loading                                          : HomeUiState()
    data class MediaReady(val mediaInfo: MediaInfo)         : HomeUiState()
    data class PlaylistReady(val playlistInfo: PlaylistInfo): HomeUiState()
    data class Error(val message: String)                   : HomeUiState()
}

// Simple heuristic — yt-dlp returns playlist entries differently
private val PLAYLIST_PATTERNS = listOf(
    "playlist", "list=PL", "/sets/", "/album/", "/albums/",
    "watch?list=", "/channel/", "/user/", "/c/"
)
private fun String.looksLikePlaylist() =
    PLAYLIST_PATTERNS.any { contains(it, ignoreCase = true) }

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val fetchMediaInfoUseCase    : FetchMediaInfoUseCase,
    private val startDownloadUseCase     : StartDownloadUseCase,
    private val getDownloadHistoryUseCase: GetDownloadHistoryUseCase,
    private val mediaRepository          : MediaRepository,
    private val networkUtils             : NetworkUtils
) : ViewModel() {

    private val _uiState  = MutableStateFlow<HomeUiState>(HomeUiState.Idle)
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    private val _urlInput = MutableStateFlow("")
    val urlInput: StateFlow<String> = _urlInput.asStateFlow()

    val recentDownloads = getDownloadHistoryUseCase.getRecent()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun onUrlChanged(url: String) {
        _urlInput.value = url
        if (_uiState.value is HomeUiState.Error) _uiState.value = HomeUiState.Idle
    }

    fun fetchMedia() {
        val rawUrl = _urlInput.value.trim()
        val url    = normalizeUrl(rawUrl)

        // Update the field so user sees the normalized version
        if (url != rawUrl) _urlInput.value = url

        when {
            url.isBlank() -> {
                _uiState.value = HomeUiState.Error("Please enter a URL.")
                return
            }
            !URLUtil.isNetworkUrl(url) -> {
                _uiState.value = HomeUiState.Error(
                    "That doesn't look like a valid URL.\nMake sure you copy the full link."
                )
                return
            }
            !networkUtils.isNetworkAvailable() -> {
                _uiState.value = HomeUiState.Error("No internet connection.")
                return
            }
        }

        viewModelScope.launch {
            _uiState.value = HomeUiState.Loading
            if (url.looksLikePlaylist()) {
                mediaRepository.fetchPlaylistInfo(url)
                    .onSuccess { playlist ->
                        if (playlist.entries.isEmpty()) fetchSingleMedia(url)
                        else _uiState.value = HomeUiState.PlaylistReady(playlist)
                    }
                    .onFailure { fetchSingleMedia(url) }
            } else {
                fetchSingleMedia(url)
            }
        }
    }

    private suspend fun fetchSingleMedia(url: String) {
        fetchMediaInfoUseCase(url)
            .onSuccess { _uiState.value = HomeUiState.MediaReady(it) }
            .onFailure { _uiState.value = HomeUiState.Error(it.toMediaError().userMessage()) }
    }

    fun startDownload(
        mediaInfo: MediaInfo,
        formatId : String,
        format   : String,
        quality  : String,
        hasAudio : Boolean = true
    ) {
        viewModelScope.launch {
            runCatching { startDownloadUseCase(mediaInfo, formatId, format, quality, hasAudio) }
            dismissSheet()
        }
    }

    /** Batch-enqueue all selected playlist entries */
    fun startBatchDownload(
        playlistInfo: PlaylistInfo,
        selectedIds : Set<String>,
        formatId    : String,
        format      : String,
        quality     : String
    ) {
        val selected = playlistInfo.entries.filter { it.id in selectedIds }
        viewModelScope.launch {
            selected.forEach { entry ->
                val fakeInfo = MediaInfo(
                    title        = entry.title,
                    thumbnailUrl = entry.thumbnailUrl,
                    duration     = entry.duration,
                    platform     = SupportedPlatform.detect(entry.url),
                    sourceUrl    = entry.url,
                    videoFormats = emptyList(),
                    audioFormats = emptyList()
                )
                runCatching { startDownloadUseCase(fakeInfo, formatId, format, quality) }
            }
            dismissSheet()
        }
    }

    fun dismissSheet() {
        _uiState.value = HomeUiState.Idle
        _urlInput.value = ""
    }

    fun setUrlFromShare(url: String) {
        _urlInput.value = normalizeUrl(url)
        fetchMedia()
    }

    // ── URL normalization ──────────────────────────────────────────────────────
    /**
     * Cleans and normalizes a pasted URL:
     * - Strips invisible/zero-width characters (common in mobile copy-paste)
     * - Auto-adds https:// if the URL is missing a scheme
     * - Handles common bare-domain patterns (youtu.be/, instagram.com/, etc.)
     */
    private fun normalizeUrl(raw: String): String {
        // Strip zero-width spaces and soft-hyphens that corrupt mobile pastes
        var url = raw
            .replace(Regex("[\u200B-\u200D\uFEFF\u00AD]"), "")
            .trim()

        if (url.isBlank()) return url

        // Already has a proper scheme — return as-is
        if (url.startsWith("http://") || url.startsWith("https://")) return url

        // Common domains that get pasted without https://
        val knownDomains = listOf(
            "youtu.be/", "youtube.com/", "www.youtube.com/", "m.youtube.com/",
            "instagram.com/", "www.instagram.com/",
            "tiktok.com/", "www.tiktok.com/", "vm.tiktok.com/",
            "twitter.com/", "x.com/", "t.co/",
            "reddit.com/", "redd.it/",
            "facebook.com/", "fb.watch/",
            "twitch.tv/", "vimeo.com/", "dailymotion.com/"
        )
        if (knownDomains.any { url.startsWith(it) }) {
            return "https://$url"
        }

        // Generic: has a dot, no spaces, looks like a domain/path
        if (url.contains(".") && !url.contains(" ") && url.length > 5) {
            return "https://$url"
        }

        return url
    }
}
