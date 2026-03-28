package com.mediadrop.app.ui.downloads

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.*
import com.mediadrop.app.data.local.entity.DownloadEntity
import com.mediadrop.app.domain.model.DownloadStatus
import com.mediadrop.app.domain.repository.DownloadRepository
import com.mediadrop.app.domain.usecase.GetDownloadHistoryUseCase
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

    /** Retry a failed download: reuse same DB entry, re-enqueue WorkManager with correct formatId */
    fun retryDownload(item: DownloadEntity) {
        viewModelScope.launch {
            // Reset status first
            downloadRepository.updateDownloadStatus(item.id, DownloadStatus.QUEUED)

            // Re-enqueue WorkManager with the SAME ID and the REAL format ID
            // (item.formatId is the yt-dlp format like "137", not the extension "mp4")
            val formatIdToUse = item.formatId.ifBlank { item.format }

            val inputData = androidx.work.workDataOf(
                DownloadWorker.KEY_DOWNLOAD_ID to item.id,
                DownloadWorker.KEY_MEDIA_URL   to item.sourceUrl,
                DownloadWorker.KEY_FORMAT_ID   to formatIdToUse,
                DownloadWorker.KEY_FORMAT      to item.format,
                DownloadWorker.KEY_QUALITY     to item.quality,
                DownloadWorker.KEY_OUTPUT_PATH to item.localPath,
                DownloadWorker.KEY_TITLE       to item.title,
                DownloadWorker.KEY_THUMBNAIL   to item.thumbnailUrl,
                DownloadWorker.KEY_HAS_AUDIO   to item.hasAudio
            )

            workManager.enqueueUniqueWork(
                item.id,
                androidx.work.ExistingWorkPolicy.REPLACE,
                androidx.work.OneTimeWorkRequestBuilder<DownloadWorker>()
                    .setId(java.util.UUID.fromString(item.id))
                    .setInputData(inputData)
                    .setConstraints(
                        androidx.work.Constraints.Builder()
                            .setRequiredNetworkType(androidx.work.NetworkType.CONNECTED)
                            .build()
                    )
                    .addTag(DownloadWorker.TAG_DOWNLOAD)
                    .build()
            )
        }
    }
}
