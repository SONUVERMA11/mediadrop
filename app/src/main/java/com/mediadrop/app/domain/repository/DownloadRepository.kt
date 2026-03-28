package com.mediadrop.app.domain.repository

import com.mediadrop.app.data.local.entity.DownloadEntity
import com.mediadrop.app.domain.model.DownloadStatus
import kotlinx.coroutines.flow.Flow

interface DownloadRepository {
    fun getAllDownloads(): Flow<List<DownloadEntity>>
    fun getRecentDownloads(): Flow<List<DownloadEntity>>
    suspend fun insertDownload(download: DownloadEntity)
    suspend fun updateDownloadStatus(id: String, status: DownloadStatus)
    suspend fun updateProgress(id: String, progress: Int, speed: String, eta: String)
    suspend fun updateCompletion(id: String, localPath: String, fileSize: Long)
    suspend fun deleteDownload(id: String)
    suspend fun getDownloadById(id: String): DownloadEntity?
}
