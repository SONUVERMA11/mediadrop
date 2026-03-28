package com.mediadrop.app.domain.repository

import com.mediadrop.app.domain.model.MediaInfo
import com.mediadrop.app.domain.model.PlaylistInfo

interface MediaRepository {
    suspend fun fetchMediaInfo(url: String): Result<MediaInfo>
    suspend fun getDownloadUrl(mediaUrl: String, formatId: String): Result<String>
    suspend fun fetchPlaylistInfo(url: String): Result<PlaylistInfo>
    suspend fun downloadMedia(
        url: String,
        formatId: String,
        outputPath: String,
        onProgress: (Int) -> Unit
    ): Result<String>
}
