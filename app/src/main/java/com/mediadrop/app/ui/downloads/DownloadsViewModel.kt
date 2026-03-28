package com.mediadrop.app.ui.downloads

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mediadrop.app.data.local.entity.DownloadEntity
import com.mediadrop.app.domain.model.DownloadStatus
import com.mediadrop.app.domain.usecase.GetDownloadHistoryUseCase
import com.mediadrop.app.domain.repository.DownloadRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class DownloadFilter { ALL, VIDEO, AUDIO, FAILED }

@HiltViewModel
class DownloadsViewModel @Inject constructor(
    private val getDownloadHistoryUseCase: GetDownloadHistoryUseCase,
    private val downloadRepository: DownloadRepository
) : ViewModel() {

    private val _filter = MutableStateFlow(DownloadFilter.ALL)
    val filter: StateFlow<DownloadFilter> = _filter.asStateFlow()

    private val allDownloads = getDownloadHistoryUseCase()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val filteredDownloads: StateFlow<List<DownloadEntity>> =
        combine(allDownloads, _filter) { downloads, f ->
            when (f) {
                DownloadFilter.ALL -> downloads
                DownloadFilter.VIDEO -> downloads.filter {
                    listOf("mp4", "mkv", "webm", "avi", "mov").any { ext -> it.format.equals(ext, true) }
                }
                DownloadFilter.AUDIO -> downloads.filter {
                    listOf("mp3", "m4a", "aac", "opus", "flac", "wav").any { ext -> it.format.equals(ext, true) }
                }
                DownloadFilter.FAILED -> downloads.filter { it.status == DownloadStatus.FAILED.name }
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun setFilter(f: DownloadFilter) { _filter.value = f }

    fun deleteDownload(id: String) {
        viewModelScope.launch { downloadRepository.deleteDownload(id) }
    }
}
