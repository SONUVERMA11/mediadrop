package com.mediadrop.app.domain.usecase

import com.mediadrop.app.data.local.entity.DownloadEntity
import com.mediadrop.app.domain.repository.DownloadRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

class GetDownloadHistoryUseCase @Inject constructor(
    private val downloadRepository: DownloadRepository
) {
    operator fun invoke(): Flow<List<DownloadEntity>> =
        downloadRepository.getAllDownloads()

    fun getRecent(): Flow<List<DownloadEntity>> =
        downloadRepository.getRecentDownloads()
}
