package com.mediadrop.app.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.mediadrop.app.domain.model.DownloadStatus
import com.mediadrop.app.domain.repository.DownloadRepository
import com.mediadrop.app.domain.repository.MediaRepository
import com.mediadrop.app.util.FileUtils
import com.mediadrop.app.util.NotificationHelper
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@HiltWorker
class DownloadWorker @AssistedInject constructor(
    @Assisted private val context: Context,
    @Assisted private val params: WorkerParameters,
    private val mediaRepository: MediaRepository,
    private val downloadRepository: DownloadRepository,
    private val notificationHelper: NotificationHelper
) : CoroutineWorker(context, params) {

    companion object {
        const val KEY_DOWNLOAD_ID = "DOWNLOAD_ID"
        const val KEY_MEDIA_URL = "MEDIA_URL"
        const val KEY_FORMAT_ID = "FORMAT_ID"
        const val KEY_FORMAT = "FORMAT"
        const val KEY_QUALITY = "QUALITY"
        const val KEY_OUTPUT_PATH = "OUTPUT_PATH"
        const val KEY_TITLE = "TITLE"
        const val KEY_THUMBNAIL = "THUMBNAIL"
        const val KEY_PROGRESS = "PROGRESS"
        const val TAG_DOWNLOAD = "download_worker"
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val title = inputData.getString(KEY_TITLE) ?: "Downloading…"
        val downloadId = inputData.getString(KEY_DOWNLOAD_ID) ?: id.toString()
        return notificationHelper.createForegroundInfo(downloadId, title, 0)
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val downloadId = inputData.getString(KEY_DOWNLOAD_ID)
            ?: return@withContext Result.failure()
        val mediaUrl = inputData.getString(KEY_MEDIA_URL)
            ?: return@withContext Result.failure()
        val formatId = inputData.getString(KEY_FORMAT_ID)
            ?: return@withContext Result.failure()
        val format = inputData.getString(KEY_FORMAT) ?: "mp4"
        val quality = inputData.getString(KEY_QUALITY) ?: "unknown"
        val outputPath = inputData.getString(KEY_OUTPUT_PATH)
            ?: return@withContext Result.failure()
        val title = inputData.getString(KEY_TITLE) ?: "Media"

        setForeground(notificationHelper.createForegroundInfo(downloadId, title, 0))

        try {
            downloadRepository.updateDownloadStatus(downloadId, DownloadStatus.DOWNLOADING)

            var lastNotifTime = System.currentTimeMillis()

            val result = mediaRepository.downloadMedia(
                url = mediaUrl,
                formatId = formatId,
                outputPath = outputPath,
                onProgress = { progress ->
                    setProgressAsync(workDataOf(KEY_PROGRESS to progress))
                    val now = System.currentTimeMillis()
                    if (now - lastNotifTime > 500) {
                        lastNotifTime = now
                        notificationHelper.updateProgress(downloadId, title, progress)
                    }
                }
            )

            if (result.isSuccess) {
                val fileSize = FileUtils.getFileSize(outputPath)
                downloadRepository.updateCompletion(downloadId, outputPath, fileSize)
                notificationHelper.showCompletionNotification(
                    downloadId, title, fileSize, outputPath, format
                )
                Result.success()
            } else {
                downloadRepository.updateDownloadStatus(downloadId, DownloadStatus.FAILED)
                notificationHelper.cancelNotification(downloadId)
                if (runAttemptCount < 2) Result.retry() else Result.failure()
            }
        } catch (e: Exception) {
            downloadRepository.updateDownloadStatus(downloadId, DownloadStatus.FAILED)
            notificationHelper.cancelNotification(downloadId)
            if (runAttemptCount < 2) Result.retry() else Result.failure()
        }
    }
}
