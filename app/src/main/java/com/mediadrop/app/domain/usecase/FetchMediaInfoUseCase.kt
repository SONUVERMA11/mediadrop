package com.mediadrop.app.domain.usecase

import com.mediadrop.app.domain.model.MediaInfo
import com.mediadrop.app.domain.repository.MediaRepository
import javax.inject.Inject

class FetchMediaInfoUseCase @Inject constructor(
    private val mediaRepository: MediaRepository
) {
    suspend operator fun invoke(url: String): Result<MediaInfo> =
        mediaRepository.fetchMediaInfo(url)
}
