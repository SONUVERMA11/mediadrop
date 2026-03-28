package com.mediadrop.app.domain.usecase

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.work.*
import com.mediadrop.app.data.local.entity.DownloadEntity
import com.mediadrop.app.domain.model.DownloadStatus
import com.mediadrop.app.domain.model.MediaInfo
import com.mediadrop.app.domain.repository.DownloadRepository
import com.mediadrop.app.ui.settings.PrefKeys
import com.mediadrop.app.ui.settings.SaveLocation
import com.mediadrop.app.util.FileUtils
import com.mediadrop.app.worker.DownloadWorker
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import java.util.UUID
import javax.inject.Inject

class StartDownloadUseCase @Inject constructor(
    @ApplicationContext private val context         : Context,
    private val downloadRepository                  : DownloadRepository,
    private val dataStore                           : DataStore<Preferences>
) {
    suspend operator fun invoke(
        mediaInfo : MediaInfo,
        formatId  : String,
        format    : String,
        quality   : String,
        hasAudio  : Boolean = true
    ): String {
        val downloadId = UUID.randomUUID().toString()

        // Read save-location preference
        val prefs        = dataStore.data.first()
        val saveLocation = prefs[PrefKeys.SAVE_LOCATION]?.let {
            runCatching { SaveLocation.valueOf(it) }.getOrNull()
        } ?: SaveLocation.SMART

        val outputPath = FileUtils.buildOutputPath(
            context      = context,
            title        = mediaInfo.title,
            format       = format,
            platform     = mediaInfo.platform.name.lowercase(),
            saveLocation = saveLocation
        )

        downloadRepository.insertDownload(DownloadEntity(
            id           = downloadId,
            title        = mediaInfo.title,
            sourceUrl    = mediaInfo.sourceUrl,
            localPath    = outputPath,
            format       = format,
            quality      = quality,
            fileSize     = 0L,
            platform     = mediaInfo.platform.name,
            thumbnailUrl = mediaInfo.thumbnailUrl,
            status       = DownloadStatus.QUEUED.name
        ))

        val inputData = workDataOf(
            DownloadWorker.KEY_DOWNLOAD_ID to downloadId,
            DownloadWorker.KEY_MEDIA_URL   to mediaInfo.sourceUrl,
            DownloadWorker.KEY_FORMAT_ID   to formatId,
            DownloadWorker.KEY_FORMAT      to format,
            DownloadWorker.KEY_QUALITY     to quality,
            DownloadWorker.KEY_OUTPUT_PATH to outputPath,
            DownloadWorker.KEY_TITLE       to mediaInfo.title,
            DownloadWorker.KEY_THUMBNAIL   to mediaInfo.thumbnailUrl,
            DownloadWorker.KEY_HAS_AUDIO   to hasAudio
        )

        WorkManager.getInstance(context).enqueueUniqueWork(
            downloadId,
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<DownloadWorker>()
                .setId(UUID.fromString(downloadId))
                .setInputData(inputData)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .addTag(DownloadWorker.TAG_DOWNLOAD)
                .build()
        )
        return downloadId
    }
}
