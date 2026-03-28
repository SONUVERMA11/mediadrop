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
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.TimeUnit
import kotlin.math.max

@HiltWorker
class DownloadWorker @AssistedInject constructor(
    @Assisted private val context: Context,
    @Assisted private val params: WorkerParameters,
    private val mediaRepository: MediaRepository,
    private val downloadRepository: DownloadRepository,
    private val notificationHelper: NotificationHelper
) : CoroutineWorker(context, params) {

    companion object {
        const val KEY_DOWNLOAD_ID  = "DOWNLOAD_ID"
        const val KEY_MEDIA_URL    = "MEDIA_URL"
        const val KEY_FORMAT_ID    = "FORMAT_ID"
        const val KEY_FORMAT       = "FORMAT"
        const val KEY_QUALITY      = "QUALITY"
        const val KEY_OUTPUT_PATH  = "OUTPUT_PATH"
        const val KEY_TITLE        = "TITLE"
        const val KEY_THUMBNAIL    = "THUMBNAIL"
        const val KEY_PROGRESS     = "PROGRESS"
        const val KEY_SPEED_BPS    = "SPEED_BPS"
        const val TAG_DOWNLOAD     = "download_worker"

        private const val CHUNKS       = 4          // parallel HTTP range connections
        private const val BUFFER_SIZE  = 65_536     // 64 KB read buffer
        private const val TIMEOUT_SEC  = 60L
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(TIMEOUT_SEC, TimeUnit.SECONDS)
        .readTimeout(TIMEOUT_SEC, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val title = inputData.getString(KEY_TITLE) ?: "Downloading…"
        val id    = inputData.getString(KEY_DOWNLOAD_ID) ?: this.id.toString()
        return notificationHelper.createForegroundInfo(id, title, 0)
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val downloadId = inputData.getString(KEY_DOWNLOAD_ID)  ?: return@withContext Result.failure()
        val mediaUrl   = inputData.getString(KEY_MEDIA_URL)    ?: return@withContext Result.failure()
        val formatId   = inputData.getString(KEY_FORMAT_ID)    ?: return@withContext Result.failure()
        val format     = inputData.getString(KEY_FORMAT)        ?: "mp4"
        val outputPath = inputData.getString(KEY_OUTPUT_PATH)  ?: return@withContext Result.failure()
        val title      = inputData.getString(KEY_TITLE)        ?: "Media"

        setForeground(notificationHelper.createForegroundInfo(downloadId, title, 0))
        downloadRepository.updateDownloadStatus(downloadId, DownloadStatus.DOWNLOADING)

        return@withContext try {
            // Step 1: resolve the real direct CDN URL from the API
            val directUrlResult = mediaRepository.getDownloadUrl(mediaUrl, formatId)
            if (directUrlResult.isFailure) {
                downloadRepository.updateDownloadStatus(downloadId, DownloadStatus.FAILED)
                return@withContext if (runAttemptCount < 2) Result.retry() else Result.failure()
            }
            val directUrl = directUrlResult.getOrThrow()

            // Step 2: probe content length via HEAD request
            val contentLength = probeContentLength(directUrl)

            // Step 3: download — chunked parallel if server supports ranges, else fallback
            val success = if (contentLength > 0) {
                downloadParallel(directUrl, outputPath, contentLength, downloadId, title)
            } else {
                downloadSequential(directUrl, outputPath, downloadId, title)
            }

            if (success) {
                val fileSize = FileUtils.getFileSize(outputPath)
                downloadRepository.updateCompletion(downloadId, outputPath, fileSize)
                // Make file visible in Files app, Gallery, and music players immediately
                FileUtils.scanFile(context, outputPath, FileUtils.guessMime(format))
                notificationHelper.showCompletionNotification(downloadId, title, fileSize, outputPath, format)
                Result.success()
            } else {
                downloadRepository.updateDownloadStatus(downloadId, DownloadStatus.FAILED)
                notificationHelper.cancelNotification(downloadId)
                if (runAttemptCount < 2) Result.retry() else Result.failure()
            }
        } catch (e: CancellationException) {
            downloadRepository.updateDownloadStatus(downloadId, DownloadStatus.FAILED)
            notificationHelper.cancelNotification(downloadId)
            Result.failure()
        } catch (e: Exception) {
            downloadRepository.updateDownloadStatus(downloadId, DownloadStatus.FAILED)
            notificationHelper.cancelNotification(downloadId)
            if (runAttemptCount < 2) Result.retry() else Result.failure()
        }
    }

    /** HEAD request to get content-length */
    private fun probeContentLength(url: String): Long {
        return try {
            val req = Request.Builder().url(url).head().build()
            httpClient.newCall(req).execute().use { resp ->
                resp.header("Content-Length")?.toLong() ?: -1L
            }
        } catch (e: Exception) { -1L }
    }

    /** 4-chunk parallel download using HTTP Range requests */
    private suspend fun downloadParallel(
        url: String,
        outputPath: String,
        contentLength: Long,
        downloadId: String,
        title: String
    ): Boolean = withContext(Dispatchers.IO) {

        val outFile = File(outputPath)
        // Pre-allocate file size
        RandomAccessFile(outFile, "rw").use { it.setLength(contentLength) }

        val chunkSize    = contentLength / CHUNKS
        val downloaded   = AtomicLong(0)
        val startTime    = System.currentTimeMillis()
        var lastProgress = -1
        var lastNotif    = 0L

        val jobs = (0 until CHUNKS).map { i ->
            val start = i * chunkSize
            val end   = if (i == CHUNKS - 1) contentLength - 1 else start + chunkSize - 1

            async {
                val req = Request.Builder()
                    .url(url)
                    .header("Range", "bytes=$start-$end")
                    .build()

                httpClient.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@async false
                    val body = resp.body ?: return@async false

                    RandomAccessFile(outFile, "rw").use { raf ->
                        raf.seek(start)
                        val buf = ByteArray(BUFFER_SIZE)
                        body.byteStream().use { stream ->
                            var n: Int
                            while (stream.read(buf).also { n = it } != -1) {
                                raf.write(buf, 0, n)
                                val totalDone = downloaded.addAndGet(n.toLong())
                                val progress  = ((totalDone * 100) / contentLength).toInt()

                                if (progress != lastProgress) {
                                    lastProgress = progress
                                    setProgressAsync(workDataOf(
                                        KEY_PROGRESS  to progress,
                                        KEY_SPEED_BPS to calcSpeed(totalDone, startTime)
                                    ))
                                    val now = System.currentTimeMillis()
                                    if (now - lastNotif > 400) {
                                        lastNotif = now
                                        notificationHelper.updateProgress(downloadId, title, progress)
                                    }
                                }
                            }
                        }
                    }
                    true
                }
            }
        }

        val results = jobs.awaitAll()
        results.all { it }
    }

    /** Fallback: single-stream sequential download */
    private suspend fun downloadSequential(
        url: String,
        outputPath: String,
        downloadId: String,
        title: String
    ): Boolean = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(url).build()
        httpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return@withContext false
            val body = resp.body ?: return@withContext false

            val total     = body.contentLength()
            var downloaded = 0L
            val startTime  = System.currentTimeMillis()
            var lastNotif  = 0L
            val buf        = ByteArray(BUFFER_SIZE)

            File(outputPath).outputStream().use { out ->
                body.byteStream().use { stream ->
                    var n: Int
                    while (stream.read(buf).also { n = it } != -1) {
                        out.write(buf, 0, n)
                        downloaded += n
                        val progress = if (total > 0) ((downloaded * 100) / total).toInt() else 0
                        setProgressAsync(workDataOf(
                            KEY_PROGRESS  to progress,
                            KEY_SPEED_BPS to calcSpeed(downloaded, startTime)
                        ))
                        val now = System.currentTimeMillis()
                        if (now - lastNotif > 400) {
                            lastNotif = now
                            notificationHelper.updateProgress(downloadId, title, progress)
                        }
                    }
                }
            }
            true
        }
    }

    private fun calcSpeed(bytes: Long, startMs: Long): Long {
        val elapsed = max(1L, System.currentTimeMillis() - startMs)
        return (bytes * 1000L) / elapsed   // bytes per second
    }
}
