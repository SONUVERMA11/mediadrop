package com.mediadrop.app.ui.home

import android.webkit.URLUtil
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mediadrop.app.domain.model.MediaInfo
import com.mediadrop.app.domain.usecase.FetchMediaInfoUseCase
import com.mediadrop.app.domain.usecase.GetDownloadHistoryUseCase
import com.mediadrop.app.domain.usecase.StartDownloadUseCase
import com.mediadrop.app.util.NetworkUtils
import com.mediadrop.app.util.toMediaError
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed class HomeUiState {
    object Idle : HomeUiState()
    object Loading : HomeUiState()
    data class MediaReady(val mediaInfo: MediaInfo) : HomeUiState()
    data class Error(val message: String) : HomeUiState()
}

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val fetchMediaInfoUseCase: FetchMediaInfoUseCase,
    private val startDownloadUseCase: StartDownloadUseCase,
    private val getDownloadHistoryUseCase: GetDownloadHistoryUseCase,
    private val networkUtils: NetworkUtils
) : ViewModel() {

    private val _uiState = MutableStateFlow<HomeUiState>(HomeUiState.Idle)
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
        if (url.isBlank()) {
            _uiState.value = HomeUiState.Error("Please enter a URL.")
            return
        }
        if (!URLUtil.isValidUrl(url)) {
            _uiState.value = HomeUiState.Error("That doesn't look like a valid URL.")
            return
        }
        if (!networkUtils.isNetworkAvailable()) {
            _uiState.value = HomeUiState.Error("No internet connection. Check your network.")
            return
        }

        viewModelScope.launch {
            _uiState.value = HomeUiState.Loading
            fetchMediaInfoUseCase(url)
                .onSuccess { _uiState.value = HomeUiState.MediaReady(it) }
                .onFailure { _uiState.value = HomeUiState.Error(it.toMediaError().userMessage()) }
        }
    }

    fun startDownload(mediaInfo: MediaInfo, formatId: String, format: String, quality: String) {
        viewModelScope.launch {
            runCatching {
                startDownloadUseCase(mediaInfo, formatId, format, quality)
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
