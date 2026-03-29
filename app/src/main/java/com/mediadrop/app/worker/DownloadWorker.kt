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
import java.io.InputStream
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max

@HiltWorker
class DownloadWorker @AssistedInject constructor(
    @Assisted private val context: Context,
    @Assisted private val params: WorkerParameters,
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

        private const val BUF_SIZE = 131_072  // 128 KB
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(600, TimeUnit.SECONDS)
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
        val format     = inputData.getString(KEY_FORMAT)       ?: "mp4"
        val outputPath = inputData.getString(KEY_OUTPUT_PATH)  ?: return@withContext Result.failure()
        val title      = inputData.getString(KEY_TITLE)        ?: "Media"
        val hasAudio   = inputData.getBoolean(KEY_HAS_AUDIO, true)

        android.util.Log.d("DownloadWorker", "Start id=$downloadId fmt=$formatId hasAudio=$hasAudio")

        setForeground(notificationHelper.createForegroundInfo(downloadId, title, 0))
        downloadRepository.updateDownloadStatus(downloadId, DownloadStatus.DOWNLOADING)

        return@withContext try {
            val base       = BuildConfig.API_BASE_URL.trimEnd('/')
            // URLEncoder encodes the media URL so it is safe as a query parameter
            val encodedUrl = URLEncoder.encode(mediaUrl, "UTF-8")

            val success = if (!hasAudio) {
                downloadSeparateAndMux(base, encodedUrl, formatId, outputPath, downloadId, title)
            } else {
                val proxyUrl = "$base/download?url=$encodedUrl&format_id=$formatId&has_audio=true&stream=video"
                val total    = AtomicLong(0)
                streamToFile(proxyUrl, File(outputPath), total, downloadId, title)
            }

            finalize(success, downloadId, outputPath, title, format)

        } catch (e: CancellationException) {
            downloadRepository.updateDownloadStatus(downloadId, DownloadStatus.CANCELLED)
            notificationHelper.cancelNotification(downloadId)
            runCatching { File(outputPath).delete() }
            Result.failure()
        } catch (e: Exception) {
            android.util.Log.e("DownloadWorker", "Fatal exception", e)
            runCatching { File(outputPath).delete() }
            downloadRepository.updateDownloadStatus(downloadId, DownloadStatus.FAILED)
            notificationHelper.cancelNotification(downloadId)
            if (runAttemptCount < 2) Result.retry() else Result.failure()
        }
    }

    // ─── Download video + audio separately, then mux them ────────────────────
    private suspend fun downloadSeparateAndMux(
        base       : String,
        encodedUrl : String,
        formatId   : String,
        outputPath : String,
        downloadId : String,
        title      : String
    ): Boolean {
        val tempVideo = File("$outputPath.v.tmp")
        val tempAudio = File("$outputPath.a.tmp")
        return try {
            val videoUrl = "$base/download?url=$encodedUrl&format_id=$formatId&has_audio=false&stream=video"
            val audioUrl = "$base/download?url=$encodedUrl&format_id=$formatId&has_audio=false&stream=audio"

            val vBytes   = AtomicLong(0)
            val aBytes   = AtomicLong(0)
            val t0       = System.currentTimeMillis()

            // Animated progress (we don't know total size upfront)
            val progressJob = CoroutineScope(Dispatchers.IO).launch {
                var p = 0
                while (isActive) {
                    p = (p + 1) % 90
                    val done = vBytes.get() + aBytes.get()
                    setProgressAsync(workDataOf(
                        KEY_PROGRESS  to p,
                        KEY_SPEED_BPS to speed(done, t0)
                    ))
                    notificationHelper.updateProgress(downloadId, title, p)
                    delay(600)
                }
            }

            // Download video and audio concurrently
            val vJob = CoroutineScope(Dispatchers.IO).async {
                streamToFile(videoUrl, tempVideo, vBytes, downloadId, title)
            }
            val aJob = CoroutineScope(Dispatchers.IO).async {
                streamToFile(audioUrl, tempAudio, aBytes, downloadId, title)
            }

            val videoOk = vJob.await()
            val audioOk = aJob.await()
            progressJob.cancel()

            android.util.Log.d("DownloadWorker", "videoOk=$videoOk audioOk=$audioOk")

            if (!videoOk || !audioOk) return false

            // Mux phase
            setProgressAsync(workDataOf(KEY_PROGRESS to 92))
            notificationHelper.updateProgress(downloadId, title, 92)
            muxVideoAudio(tempVideo, tempAudio, File(outputPath))

        } finally {
            tempVideo.delete()
            tempAudio.delete()
        }
    }

    // ─── Core stream-to-file function — THE MAIN BUG WAS HERE ────────────────
    /**
     * Downloads bytes from [proxyUrl] and writes them to [outFile].
     *
     * CRITICAL FIX: We call body.byteStream() ONCE, store it in a local val,
     * and call .read() on THAT reference. The previous code called
     * body.byteStream() twice per loop iteration which creates a new stream
     * object each time, causing 0-byte output files.
     */
    private fun streamToFile(
        proxyUrl   : String,
        outFile    : File,
        counter    : AtomicLong,
        downloadId : String,
        title      : String
    ): Boolean {
        android.util.Log.d("DownloadWorker", "streamToFile → ${proxyUrl.take(120)}")
        outFile.parentFile?.mkdirs()

        return try {
            val req = Request.Builder().url(proxyUrl).get().build()
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    val body = try { resp.body?.string()?.take(300) } catch (_: Exception) { null }
                    android.util.Log.e("DownloadWorker", "HTTP ${resp.code}: $body")
                    return false
                }

                val responseBody = resp.body ?: run {
                    android.util.Log.e("DownloadWorker", "Null response body")
                    return false
                }

                // ✅ FIXED: store the stream in ONE variable and read from IT
                val inputStream: InputStream = responseBody.byteStream()
                val buf = ByteArray(BUF_SIZE)

                outFile.outputStream().use { out ->
                    var n = inputStream.read(buf)
                    while (n > 0) {
                        out.write(buf, 0, n)
                        counter.addAndGet(n.toLong())
                        n = inputStream.read(buf)  // ← same inputStream, not byteStream() again
                    }
                }

                val written = outFile.length()
                android.util.Log.d("DownloadWorker", "Written ${FileUtils.formatFileSize(written)} → ${outFile.name}")
                written > 0
            }
        } catch (e: IOException) {
            android.util.Log.e("DownloadWorker", "IO error", e)
            outFile.delete()
            false
        } catch (e: Exception) {
            android.util.Log.e("DownloadWorker", "Stream error", e)
            outFile.delete()
            false
        }
    }

    // ─── Finalize download result ────────────────────────────────────────────
    private suspend fun finalize(
        success    : Boolean,
        downloadId : String,
        outputPath : String,
        title      : String,
        format     : String
    ): Result {
        return if (success) {
            val size = FileUtils.getFileSize(outputPath)
            if (size == 0L) {
                android.util.Log.e("DownloadWorker", "File is 0 bytes!")
                downloadRepository.updateDownloadStatus(downloadId, DownloadStatus.FAILED)
                notificationHelper.cancelNotification(downloadId)
                return if (runAttemptCount < 2) Result.retry() else Result.failure()
            }
            downloadRepository.updateCompletion(downloadId, outputPath, size)
            FileUtils.scanFile(context, outputPath, FileUtils.guessMime(format))
            notificationHelper.showCompletionNotification(downloadId, title, size, outputPath, format)
            setProgressAsync(workDataOf(KEY_PROGRESS to 100))
            android.util.Log.d("DownloadWorker", "✅ Done: ${FileUtils.formatFileSize(size)}")
            Result.success()
        } else {
            runCatching { File(outputPath).delete() }
            downloadRepository.updateDownloadStatus(downloadId, DownloadStatus.FAILED)
            notificationHelper.cancelNotification(downloadId)
            if (runAttemptCount < 2) Result.retry() else Result.failure()
        }
    }

    // ─── MediaMuxer: video + audio → MP4 ────────────────────────────────────
    private fun muxVideoAudio(videoFile: File, audioFile: File, outFile: File): Boolean {
        val videoEx = MediaExtractor()
        val audioEx = MediaExtractor()
        var muxer: MediaMuxer? = null

        return try {
            videoEx.setDataSource(videoFile.absolutePath)
            audioEx.setDataSource(audioFile.absolutePath)

            var vidIn = -1
            var audIn = -1
            var audioMime = ""

            for (i in 0 until videoEx.trackCount) {
                val mime = videoEx.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("video/")) { vidIn = i; break }
            }
            for (i in 0 until audioEx.trackCount) {
                val mime = audioEx.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) { audIn = i; audioMime = mime; break }
            }

            if (vidIn < 0) {
                android.util.Log.e("DownloadWorker", "No video track found")
                return false
            }

            // MediaMuxer only supports AAC/MP4A — not Opus (WebM)
            val canMuxAudio = audIn >= 0 &&
                    (audioMime.contains("mp4a", true) || audioMime.contains("aac", true) ||
                     audioMime == "audio/3gpp"        || audioMime == "audio/amr-wb")

            if (!canMuxAudio && audIn >= 0)
                android.util.Log.w("DownloadWorker", "Skipping incompatible audio: $audioMime")

            muxer = MediaMuxer(outFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val vidOut = muxer.addTrack(videoEx.getTrackFormat(vidIn))
            val audOut = if (canMuxAudio) muxer.addTrack(audioEx.getTrackFormat(audIn)) else -1
            muxer.start()

            val buf  = ByteBuffer.allocate(2 * 1024 * 1024)
            val info = MediaCodec.BufferInfo()

            // Write video samples
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

            // Write audio samples
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
            android.util.Log.d("DownloadWorker", "Mux ✅ ${outFile.length()} bytes")
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

    private fun speed(bytes: Long, startMs: Long): Long {
        val elapsed = max(1L, System.currentTimeMillis() - startMs)
        return (bytes * 1000L) / elapsed
    }
}
