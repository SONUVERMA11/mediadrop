package com.mediadrop.app.worker

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.mediadrop.app.BuildConfig
import com.mediadrop.app.domain.model.DownloadStatus
import com.mediadrop.app.domain.repository.DownloadRepository
import com.mediadrop.app.util.FileUtils
import com.mediadrop.app.util.NotificationHelper
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.*
import okhttp3.*
import java.io.File
import java.io.IOException
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max

@HiltWorker
class DownloadWorker @AssistedInject constructor(
    @Assisted private val context : Context,
    @Assisted private val params  : WorkerParameters,
    private val downloadRepository: DownloadRepository,
    private val notificationHelper: NotificationHelper
) : CoroutineWorker(context, params) {

    companion object {
        const val KEY_DOWNLOAD_ID = "DOWNLOAD_ID"
        const val KEY_MEDIA_URL   = "MEDIA_URL"
        const val KEY_FORMAT_ID   = "FORMAT_ID"
        const val KEY_FORMAT      = "FORMAT"
        const val KEY_QUALITY     = "QUALITY"
        const val KEY_OUTPUT_PATH = "OUTPUT_PATH"
        const val KEY_TITLE       = "TITLE"
        const val KEY_THUMBNAIL   = "THUMBNAIL"
        const val KEY_HAS_AUDIO   = "HAS_AUDIO"
        const val KEY_PROGRESS    = "PROGRESS"
        const val KEY_SPEED_BPS   = "SPEED_BPS"
        const val TAG_DOWNLOAD    = "download_worker"

        private const val BUFFER_SIZE       = 65_536   // 64 KB — matched to backend chunk size
        private const val NOTIF_THROTTLE_MS = 400L
    }

    // Shared HTTP client — connects to OUR backend (not CDN directly)
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(600, TimeUnit.SECONDS)   // 10 min for large files
        .writeTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .retryOnConnectionFailure(true)
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
        val hasAudio   = inputData.getBoolean(KEY_HAS_AUDIO, true)

        android.util.Log.d("DownloadWorker",
            "Starting: id=$downloadId format=$formatId hasAudio=$hasAudio")

        setForeground(notificationHelper.createForegroundInfo(downloadId, title, 0))
        downloadRepository.updateDownloadStatus(downloadId, DownloadStatus.DOWNLOADING)

        return@withContext try {
            val baseUrl = BuildConfig.API_BASE_URL.trimEnd('/')
            val encodedUrl = URLEncoder.encode(mediaUrl, "UTF-8")

            val success = if (!hasAudio) {
                // Video-only format: download video stream + audio stream, then mux
                val tempVideo = File("$outputPath.v.tmp")
                val tempAudio = File("$outputPath.a.tmp")
                try {
                    val videoProxyUrl = "$baseUrl/download?url=$encodedUrl&format_id=$formatId&has_audio=false&stream=video"
                    val audioProxyUrl = "$baseUrl/download?url=$encodedUrl&format_id=$formatId&has_audio=false&stream=audio"

                    val videoDone = AtomicLong(0)
                    val audioDone = AtomicLong(0)
                    val startTime = System.currentTimeMillis()

                    // Progress reporter
                    val progressJob = launch {
                        // We don't know total size upfront in proxy mode, show indeterminate
                        var tick = 0
                        while (isActive) {
                            val done = videoDone.get() + audioDone.get()
                            val progress = (tick % 90).also { tick++ }  // show activity
                            setProgressAsync(workDataOf(
                                KEY_PROGRESS  to progress,
                                KEY_SPEED_BPS to calcSpeed(done, startTime)
                            ))
                            notificationHelper.updateProgress(downloadId, title, progress)
                            delay(500)
                        }
                    }

                    val videoJob = async(Dispatchers.IO) {
                        streamFromProxy(videoProxyUrl, tempVideo, videoDone, downloadId, title)
                    }
                    val audioJob = async(Dispatchers.IO) {
                        streamFromProxy(audioProxyUrl, tempAudio, audioDone, downloadId, title)
                    }

                    val videoOk = videoJob.await()
                    val audioOk = audioJob.await()
                    progressJob.cancel()

                    if (!videoOk || !audioOk) {
                        android.util.Log.e("DownloadWorker",
                            "Stream failed: videoOk=$videoOk audioOk=$audioOk")
                        false
                    } else {
                        // Mux
                        setProgressAsync(workDataOf(KEY_PROGRESS to 92))
                        notificationHelper.updateProgress(downloadId, title, 92)
                        val muxOk = muxVideoAudio(tempVideo, tempAudio, File(outputPath))
                        if (muxOk) {
                            setProgressAsync(workDataOf(KEY_PROGRESS to 100))
                        }
                        muxOk
                    }
                } finally {
                    tempVideo.delete()
                    tempAudio.delete()
                }
            } else {
                // Single stream (has audio embedded)
                val proxyUrl = "$baseUrl/download?url=$encodedUrl&format_id=$formatId&has_audio=true&stream=video"
                val bytesWritten = AtomicLong(0)
                streamFromProxy(proxyUrl, File(outputPath), bytesWritten, downloadId, title)
            }

            if (success) {
                val fileSize = FileUtils.getFileSize(outputPath)
                if (fileSize == 0L) {
                    android.util.Log.e("DownloadWorker", "File is 0 bytes after download")
                    downloadRepository.updateDownloadStatus(downloadId, DownloadStatus.FAILED)
                    notificationHelper.cancelNotification(downloadId)
                    return@withContext if (runAttemptCount < 2) Result.retry() else Result.failure()
                }
                downloadRepository.updateCompletion(downloadId, outputPath, fileSize)
                FileUtils.scanFile(context, outputPath, FileUtils.guessMime(format))
                notificationHelper.showCompletionNotification(downloadId, title, fileSize, outputPath, format)
                setProgressAsync(workDataOf(KEY_PROGRESS to 100))
                android.util.Log.d("DownloadWorker", "Completed: ${FileUtils.formatFileSize(fileSize)}")
                Result.success()
            } else {
                android.util.Log.e("DownloadWorker", "Download failed, cleaning up")
                runCatching { File(outputPath).delete() }
                downloadRepository.updateDownloadStatus(downloadId, DownloadStatus.FAILED)
                notificationHelper.cancelNotification(downloadId)
                if (runAttemptCount < 2) Result.retry() else Result.failure()
            }

        } catch (e: CancellationException) {
            downloadRepository.updateDownloadStatus(downloadId, DownloadStatus.CANCELLED)
            notificationHelper.cancelNotification(downloadId)
            runCatching { File(outputPath).delete() }
            Result.failure()
        } catch (e: Exception) {
            android.util.Log.e("DownloadWorker", "Unexpected exception", e)
            runCatching { File(outputPath).delete() }
            downloadRepository.updateDownloadStatus(downloadId, DownloadStatus.FAILED)
            notificationHelper.cancelNotification(downloadId)
            if (runAttemptCount < 2) Result.retry() else Result.failure()
        }
    }

    /**
     * Streams a response body from our backend proxy URL directly to a file.
     * The backend handles the CDN fetching — no IP mismatch issues.
     */
    private fun streamFromProxy(
        proxyUrl   : String,
        outFile    : File,
        counter    : AtomicLong,
        downloadId : String,
        title      : String
    ): Boolean {
        android.util.Log.d("DownloadWorker", "Streaming from: ${proxyUrl.take(100)}")
        outFile.parentFile?.mkdirs()

        return try {
            val req = Request.Builder()
                .url(proxyUrl)
                .get()
                .build()

            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    android.util.Log.e("DownloadWorker",
                        "Proxy returned HTTP ${resp.code}: ${resp.body?.string()?.take(200)}")
                    return false
                }

                val body = resp.body ?: run {
                    android.util.Log.e("DownloadWorker", "Null body from proxy")
                    return false
                }

                outFile.outputStream().use { out ->
                    val buf = ByteArray(BUFFER_SIZE)
                    var n = body.byteStream().read(buf)
                    while (n > 0) {
                        out.write(buf, 0, n)
                        counter.addAndGet(n.toLong())
                        n = body.byteStream().read(buf)
                    }
                }
                true
            }
        } catch (e: IOException) {
            android.util.Log.e("DownloadWorker", "IO error streaming from proxy", e)
            outFile.delete()
            false
        } catch (e: Exception) {
            android.util.Log.e("DownloadWorker", "Error streaming from proxy", e)
            outFile.delete()
            false
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // MediaMuxer: combine video + audio → final MP4
    // Supports H.264/H.265 video + AAC/MP4A audio
    // ─────────────────────────────────────────────────────────────────────────
    private fun muxVideoAudio(videoFile: File, audioFile: File, outFile: File): Boolean {
        val videoEx = MediaExtractor()
        val audioEx = MediaExtractor()
        var muxer: MediaMuxer? = null

        return try {
            videoEx.setDataSource(videoFile.absolutePath)
            audioEx.setDataSource(audioFile.absolutePath)

            var vidIn = -1; var videoMime = ""
            for (i in 0 until videoEx.trackCount) {
                val mime = videoEx.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("video/")) { vidIn = i; videoMime = mime; break }
            }

            var audIn = -1; var audioMime = ""
            for (i in 0 until audioEx.trackCount) {
                val mime = audioEx.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) { audIn = i; audioMime = mime; break }
            }

            if (vidIn < 0) {
                android.util.Log.e("DownloadWorker", "No video track in ${videoFile.name}")
                return false
            }

            // MediaMuxer can only mux AAC (mp4a) audio, not Opus
            val canMuxAudio = audIn >= 0 &&
                    (audioMime.contains("mp4a") || audioMime.contains("aac") ||
                     audioMime.contains("3gpp") || audioMime.contains("amr"))

            if (!canMuxAudio && audIn >= 0) {
                android.util.Log.w("DownloadWorker",
                    "Skipping unmuxable audio codec: $audioMime")
            }

            muxer = MediaMuxer(outFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val vidOut = muxer.addTrack(videoEx.getTrackFormat(vidIn))
            val audOut = if (canMuxAudio) muxer.addTrack(audioEx.getTrackFormat(audIn)) else -1

            muxer.start()
            val buf  = ByteBuffer.allocate(2 * 1024 * 1024)
            val info = MediaCodec.BufferInfo()

            videoEx.selectTrack(vidIn)
            videoEx.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
            while (true) {
                buf.clear()
                info.size = videoEx.readSampleData(buf, 0)
                if (info.size < 0) break
                info.presentationTimeUs = videoEx.sampleTime
                info.flags              = videoEx.sampleFlags
                muxer.writeSampleData(vidOut, buf, info)
                videoEx.advance()
            }

            if (canMuxAudio) {
                audioEx.selectTrack(audIn)
                audioEx.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                while (true) {
                    buf.clear()
                    info.size = audioEx.readSampleData(buf, 0)
                    if (info.size < 0) break
                    info.presentationTimeUs = audioEx.sampleTime
                    info.flags              = audioEx.sampleFlags
                    muxer.writeSampleData(audOut, buf, info)
                    audioEx.advance()
                }
            }

            muxer.stop()
            android.util.Log.d("DownloadWorker",
                "Mux complete: ${outFile.name} (${FileUtils.formatFileSize(outFile.length())})")
            true
        } catch (e: Exception) {
            android.util.Log.e("DownloadWorker", "Mux failed", e)
            outFile.delete()
            false
        } finally {
            runCatching { videoEx.release() }
            runCatching { audioEx.release() }
            runCatching { muxer?.release() }
        }
    }

    private fun calcSpeed(bytes: Long, startMs: Long): Long {
        val elapsed = max(1L, System.currentTimeMillis() - startMs)
        return (bytes * 1000L) / elapsed
    }
}
