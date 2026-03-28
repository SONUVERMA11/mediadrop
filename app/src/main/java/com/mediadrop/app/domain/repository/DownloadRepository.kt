package com.mediadrop.app.domain.repository

import com.mediadrop.app.data.remote.dto.DownloadUrlDto
import com.mediadrop.app.data.local.entity.DownloadEntity
import com.mediadrop.app.domain.model.DownloadStatus
import com.mediadrop.app.domain.model.MediaInfo
import com.mediadrop.app.domain.model.PlaylistInfo
import kotlinx.coroutines.flow.Flow

interface MediaRepository {
    suspend fun fetchMediaInfo(url: String): Result<MediaInfo>
    /** Returns full DownloadUrlDto including optional audio_url for video-only formats */
    suspend fun getDownloadUrlDto(mediaUrl: String, formatId: String, hasAudio: Boolean): Result<DownloadUrlDto>
    suspend fun fetchPlaylistInfo(url: String): Result<PlaylistInfo>
    suspend fun downloadMedia(url: String, formatId: String, outputPath: String, onProgress: (Int) -> Unit): Result<String>
}

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
