package com.mediadrop.app.domain.repository

import com.mediadrop.app.data.remote.dto.DownloadUrlDto
import com.mediadrop.app.domain.model.MediaInfo
import com.mediadrop.app.domain.model.PlaylistInfo

interface MediaRepository {
    suspend fun fetchMediaInfo(url: String): Result<MediaInfo>
    /** Returns full DownloadUrlDto — includes audio_url when format is video-only */
    suspend fun getDownloadUrlDto(mediaUrl: String, formatId: String, hasAudio: Boolean): Result<DownloadUrlDto>
    suspend fun fetchPlaylistInfo(url: String): Result<PlaylistInfo>
    suspend fun downloadMedia(
        url        : String,
        formatId   : String,
        outputPath : String,
        onProgress : (Int) -> Unit
    ): Result<String>
}
