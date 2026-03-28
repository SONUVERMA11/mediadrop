package com.mediadrop.app.domain.repository

import com.mediadrop.app.domain.model.MediaInfo

interface MediaRepository {
    suspend fun fetchMediaInfo(url: String): Result<MediaInfo>
    suspend fun downloadMedia(
        url: String,
        formatId: String,
        outputPath: String,
        onProgress: (Int) -> Unit
    ): Result<String>
}
