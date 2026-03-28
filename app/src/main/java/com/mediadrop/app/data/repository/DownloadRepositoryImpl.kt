package com.mediadrop.app.data.repository

import com.mediadrop.app.data.local.dao.DownloadDao
import com.mediadrop.app.data.local.entity.DownloadEntity
import com.mediadrop.app.domain.model.DownloadStatus
import com.mediadrop.app.domain.repository.DownloadRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

class DownloadRepositoryImpl @Inject constructor(
    private val downloadDao: DownloadDao
) : DownloadRepository {

    override fun getAllDownloads(): Flow<List<DownloadEntity>> =
        downloadDao.getAllDownloads()

    override fun getRecentDownloads(): Flow<List<DownloadEntity>> =
        downloadDao.getRecentDownloads()

    override suspend fun insertDownload(download: DownloadEntity) =
        downloadDao.insertDownload(download)

    override suspend fun updateDownloadStatus(id: String, status: DownloadStatus) =
        downloadDao.updateStatus(id, status.name)

    override suspend fun updateProgress(id: String, progress: Int, speed: String, eta: String) =
        downloadDao.updateProgress(id, progress, speed, eta)

    override suspend fun updateCompletion(id: String, localPath: String, fileSize: Long) =
        downloadDao.updateCompletion(id, DownloadStatus.COMPLETED.name, localPath, fileSize)

    override suspend fun deleteDownload(id: String) =
        downloadDao.deleteDownload(id)

    override suspend fun getDownloadById(id: String): DownloadEntity? =
        downloadDao.getDownloadById(id)
}
