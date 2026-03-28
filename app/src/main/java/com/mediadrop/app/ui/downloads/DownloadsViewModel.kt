package com.mediadrop.app.ui.downloads

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.mediadrop.app.data.local.entity.DownloadEntity
import com.mediadrop.app.domain.model.DownloadStatus
import com.mediadrop.app.domain.repository.DownloadRepository
import com.mediadrop.app.domain.usecase.GetDownloadHistoryUseCase
import com.mediadrop.app.worker.DownloadWorker
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class DownloadFilter { ALL, VIDEO, AUDIO, FAILED }

/** Maps workerId → current progress (0-100) */
typealias ProgressMap = Map<String, Int>

@HiltViewModel
class DownloadsViewModel @Inject constructor(
    private val getDownloadHistoryUseCase: GetDownloadHistoryUseCase,
    private val downloadRepository: DownloadRepository,
    private val workManager: WorkManager
) : ViewModel() {

    private val _filter = MutableStateFlow(DownloadFilter.ALL)
    val filter: StateFlow<DownloadFilter> = _filter.asStateFlow()

    private val allDownloads: StateFlow<List<DownloadEntity>> =
        getDownloadHistoryUseCase()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val filteredDownloads: StateFlow<List<DownloadEntity>> =
        combine(allDownloads, _filter) { downloads, f ->
            when (f) {
                DownloadFilter.ALL    -> downloads
                DownloadFilter.VIDEO  -> downloads.filter {
                    listOf("mp4","mkv","webm","avi","mov").any { ext -> it.format.equals(ext, true) }
                }
                DownloadFilter.AUDIO  -> downloads.filter {
                    listOf("mp3","m4a","aac","opus","flac","wav").any { ext -> it.format.equals(ext, true) }
                }
                DownloadFilter.FAILED -> downloads.filter { it.status == DownloadStatus.FAILED.name }
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * Live WorkManager progress — maps download entity-ID (== WorkInfo UUID) → progress %.
     * StartDownloadUseCase ensures workRequest.id == UUID.fromString(downloadId),
     * so WorkInfo.id.toString() == entity.id.
     */
    val liveProgress: StateFlow<ProgressMap> =
        workManager
            .getWorkInfosByTagFlow(DownloadWorker.TAG_DOWNLOAD)
            .map { infos ->
                infos
                    .filter { it.state == WorkInfo.State.RUNNING }
                    .associate { info ->
                        val id  = info.id.toString()   // == downloadId (entity PK)
                        val pct = info.progress.getInt(DownloadWorker.KEY_PROGRESS, 0)
                        id to pct
                    }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    fun setFilter(f: DownloadFilter) { _filter.value = f }

    fun deleteDownload(id: String) {
        viewModelScope.launch { downloadRepository.deleteDownload(id) }
    }
}
