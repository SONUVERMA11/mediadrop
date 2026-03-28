package com.mediadrop.app.ui.downloads

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.mediadrop.app.data.local.entity.DownloadEntity
import com.mediadrop.app.domain.model.DownloadStatus
import com.mediadrop.app.domain.repository.DownloadRepository
import com.mediadrop.app.domain.usecase.GetDownloadHistoryUseCase
import com.mediadrop.app.domain.usecase.StartDownloadUseCase
import com.mediadrop.app.domain.model.MediaInfo
import com.mediadrop.app.domain.model.SupportedPlatform
import com.mediadrop.app.worker.DownloadWorker
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

enum class DownloadFilter { ALL, VIDEO, AUDIO, FAILED }

typealias ProgressMap = Map<String, Int>

@HiltViewModel
class DownloadsViewModel @Inject constructor(
    @ApplicationContext private val context       : Context,
    private val getDownloadHistoryUseCase         : GetDownloadHistoryUseCase,
    private val downloadRepository                : DownloadRepository,
    private val startDownloadUseCase              : StartDownloadUseCase,
    private val workManager                       : WorkManager
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

    /** Live WorkManager progress: workId(==entityId) → progress % */
    val liveProgress: StateFlow<ProgressMap> =
        workManager
            .getWorkInfosByTagFlow(DownloadWorker.TAG_DOWNLOAD)
            .map { infos ->
                infos
                    .filter { it.state == WorkInfo.State.RUNNING }
                    .associate { info ->
                        info.id.toString() to info.progress.getInt(DownloadWorker.KEY_PROGRESS, 0)
                    }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    fun setFilter(f: DownloadFilter) { _filter.value = f }

    /** Cancel an in-progress download */
    fun cancelDownload(id: String) {
        workManager.cancelWorkById(UUID.fromString(id))
        viewModelScope.launch {
            downloadRepository.updateDownloadStatus(id, DownloadStatus.FAILED)
        }
    }

    /** Delete a download record (and optionally its file) */
    fun deleteDownload(id: String) {
        viewModelScope.launch { downloadRepository.deleteDownload(id) }
    }

    /** Retry a failed download using saved metadata */
    fun retryDownload(item: DownloadEntity) {
        viewModelScope.launch {
            val fakeInfo = MediaInfo(
                title        = item.title,
                thumbnailUrl = item.thumbnailUrl,
                duration     = 0L,
                platform     = runCatching { SupportedPlatform.valueOf(item.platform) }
                                   .getOrElse { SupportedPlatform.detect(item.sourceUrl) },
                sourceUrl    = item.sourceUrl,
                videoFormats = emptyList(),
                audioFormats = emptyList()
            )
            // Update status back to QUEUED
            downloadRepository.updateDownloadStatus(item.id, DownloadStatus.QUEUED)
            runCatching {
                startDownloadUseCase(fakeInfo, item.format, item.format, item.quality)
            }
        }
    }
}
