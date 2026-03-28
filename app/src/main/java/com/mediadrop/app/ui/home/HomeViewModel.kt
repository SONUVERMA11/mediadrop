package com.mediadrop.app.ui.home

import android.webkit.URLUtil
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mediadrop.app.domain.model.MediaInfo
import com.mediadrop.app.domain.model.PlaylistInfo
import com.mediadrop.app.domain.usecase.FetchMediaInfoUseCase
import com.mediadrop.app.domain.usecase.GetDownloadHistoryUseCase
import com.mediadrop.app.domain.usecase.StartDownloadUseCase
import com.mediadrop.app.domain.repository.MediaRepository
import com.mediadrop.app.util.NetworkUtils
import com.mediadrop.app.util.toMediaError
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed class HomeUiState {
    object Idle                                                     : HomeUiState()
    object Loading                                                  : HomeUiState()
    data class MediaReady(val mediaInfo: MediaInfo)                 : HomeUiState()
    data class PlaylistReady(val playlistInfo: PlaylistInfo)        : HomeUiState()
    data class Error(val message: String)                           : HomeUiState()
}

// Simple heuristic — yt-dlp returns playlist entries differently
private val PLAYLIST_PATTERNS = listOf(
    "playlist", "list=PL", "/sets/", "/album/", "/albums/",
    "watch?list=", "/channel/", "/user/", "/c/"
)
private fun String.looksLikePlaylist() = PLAYLIST_PATTERNS.any { contains(it, ignoreCase = true) }

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val fetchMediaInfoUseCase : FetchMediaInfoUseCase,
    private val startDownloadUseCase  : StartDownloadUseCase,
    private val getDownloadHistoryUseCase: GetDownloadHistoryUseCase,
    private val mediaRepository       : MediaRepository,
    private val networkUtils          : NetworkUtils
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
        val url = _urlInput.value.trim()
        when {
            url.isBlank()            -> { _uiState.value = HomeUiState.Error("Please enter a URL."); return }
            !URLUtil.isValidUrl(url) -> { _uiState.value = HomeUiState.Error("That doesn't look like a valid URL."); return }
            !networkUtils.isNetworkAvailable() -> { _uiState.value = HomeUiState.Error("No internet connection."); return }
        }

        viewModelScope.launch {
            _uiState.value = HomeUiState.Loading
            if (url.looksLikePlaylist()) {
                // Try playlist first
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

    fun startDownload(mediaInfo: MediaInfo, formatId: String, format: String, quality: String, hasAudio: Boolean = true) {
        viewModelScope.launch {
            runCatching { startDownloadUseCase(mediaInfo, formatId, format, quality, hasAudio) }
            dismissSheet()
        }
    }

    /** Batch-enqueue all selected playlist entries */
    fun startBatchDownload(
        playlistInfo: PlaylistInfo,
        selectedIds: Set<String>,
        formatId: String,
        format: String,
        quality: String
    ) {
        val selected = playlistInfo.entries.filter { it.id in selectedIds }
        viewModelScope.launch {
            selected.forEach { entry ->
                val fakeInfo = MediaInfo(
                    title        = entry.title,
                    thumbnailUrl = entry.thumbnailUrl,
                    duration     = entry.duration,
                    platform     = com.mediadrop.app.domain.model.SupportedPlatform.detect(entry.url),
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
        _urlInput.value = url
        fetchMedia()
    }
}
