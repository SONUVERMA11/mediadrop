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
import com.mediadrop.app.domain.model.DownloadStatus
import com.mediadrop.app.domain.repository.DownloadRepository
import com.mediadrop.app.domain.repository.MediaRepository
import com.mediadrop.app.util.FileUtils
import com.mediadrop.app.util.NotificationHelper
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.*
import okhttp3.*
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max

@HiltWorker
class DownloadWorker @AssistedInject constructor(
    @Assisted private val context : Context,
    @Assisted private val params  : WorkerParameters,
    private val mediaRepository   : MediaRepository,
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

        // ── Reliable + fast tuning ────────────────────────────────────────
        // 4 chunks: balanced for mobile CDN reliability; more chunks = more 206 failures
        // 256 KB buffer: balanced for throughput vs memory
        private const val CHUNKS        = 4
        private const val BUFFER_SIZE   = 262_144     // 256 KB
        private const val CHUNK_RETRIES = 4
        private const val TIMEOUT_CONN  = 30L
        private const val TIMEOUT_READ  = 120L
        private const val NOTIF_THROTTLE_MS = 400L    // max notification update rate

        // User-Agent that CDNs (YouTube, etc.) expect to see
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 11; Pixel 5) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
    }

    private val httpClient = OkHttpClient.Builder()
        .connectionPool(ConnectionPool(8, 5, TimeUnit.MINUTES))
        .dispatcher(Dispatcher().apply {
            maxRequests        = 16
            maxRequestsPerHost = 8
        })
        .connectTimeout(TIMEOUT_CONN, TimeUnit.SECONDS)
        .readTimeout(TIMEOUT_READ, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .retryOnConnectionFailure(true)
        .protocols(listOf(Protocol.HTTP_2, Protocol.HTTP_1_1))
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

        setForeground(notificationHelper.createForegroundInfo(downloadId, title, 0))
        downloadRepository.updateDownloadStatus(downloadId, DownloadStatus.DOWNLOADING)

        return@withContext try {
            // ── 1. Resolve CDN URL(s) from backend ────────────────────────
            // Always fetch a fresh URL — CDN signed URLs expire and cannot be reused
            val urlDtoResult = mediaRepository.getDownloadUrlDto(mediaUrl, formatId, hasAudio)
            if (urlDtoResult.isFailure) {
                val errMsg = urlDtoResult.exceptionOrNull()?.message ?: "Unknown"
                android.util.Log.e("DownloadWorker", "URL fetch failed: $errMsg")
                downloadRepository.updateDownloadStatus(downloadId, DownloadStatus.FAILED)
                return@withContext if (runAttemptCount < 2) Result.retry() else Result.failure()
            }
            val urlDto   = urlDtoResult.getOrThrow()
            val videoUrl = urlDto.url
            val audioUrl = urlDto.audioUrl   // non-null → format is video-only

            if (videoUrl.isBlank()) {
                android.util.Log.e("DownloadWorker", "Empty CDN URL returned from backend")
                downloadRepository.updateDownloadStatus(downloadId, DownloadStatus.FAILED)
                return@withContext Result.failure()
            }

            android.util.Log.d("DownloadWorker", "Starting download: hasAudio=$hasAudio audioUrl=${audioUrl?.take(60)}")

            // ── 2. Download ────────────────────────────────────────────────
            val success = if (!audioUrl.isNullOrBlank()) {
                // Video-only format: download video + audio concurrently, then merge
                downloadAndMerge(videoUrl, audioUrl, outputPath, downloadId, title)
            } else {
                // Single stream: try chunked first, fall back to streaming
                val len = probeContentLength(videoUrl)
                android.util.Log.d("DownloadWorker", "Content-Length: $len")
                when {
                    len > 0 -> chunkedDownload(videoUrl, outputPath, len, downloadId, title)
                    else    -> streamingDownload(videoUrl, outputPath, downloadId, title)
                }
            }

            if (success) {
                val fileSize = FileUtils.getFileSize(outputPath)
                if (fileSize == 0L) {
                    // File is empty — download silently failed
                    android.util.Log.e("DownloadWorker", "Downloaded file is 0 bytes: $outputPath")
                    downloadRepository.updateDownloadStatus(downloadId, DownloadStatus.FAILED)
                    notificationHelper.cancelNotification(downloadId)
                    return@withContext if (runAttemptCount < 2) Result.retry() else Result.failure()
                }
                downloadRepository.updateCompletion(downloadId, outputPath, fileSize)
                FileUtils.scanFile(context, outputPath, FileUtils.guessMime(format))
                notificationHelper.showCompletionNotification(downloadId, title, fileSize, outputPath, format)
                setProgressAsync(workDataOf(KEY_PROGRESS to 100))
                Result.success()
            } else {
                android.util.Log.e("DownloadWorker", "Download returned false for: $videoUrl")
                // Clean up partial files
                runCatching { java.io.File(outputPath).delete() }
                downloadRepository.updateDownloadStatus(downloadId, DownloadStatus.FAILED)
                notificationHelper.cancelNotification(downloadId)
                if (runAttemptCount < 2) Result.retry() else Result.failure()
            }
        } catch (e: CancellationException) {
            downloadRepository.updateDownloadStatus(downloadId, DownloadStatus.CANCELLED)
            notificationHelper.cancelNotification(downloadId)
            Result.failure()
        } catch (e: Exception) {
            android.util.Log.e("DownloadWorker", "Unexpected exception", e)
            runCatching { java.io.File(outputPath).delete() }
            downloadRepository.updateDownloadStatus(downloadId, DownloadStatus.FAILED)
            notificationHelper.cancelNotification(downloadId)
            if (runAttemptCount < 2) Result.retry() else Result.failure()
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 8-CHUNK PARALLEL DOWNLOAD using RandomAccessFile
    // Progress is reported from a supervisory coroutine — NO runBlocking in hot path
    // ─────────────────────────────────────────────────────────────────────────
    private suspend fun chunkedDownload(
        url        : String,
        outputPath : String,
        contentLen : Long,
        downloadId : String,
        title      : String
    ): Boolean = withContext(Dispatchers.IO) {

        val outFile = File(outputPath).also { it.parentFile?.mkdirs() }

        // Pre-allocate file space (avoids fragmentation, faster write)
        RandomAccessFile(outFile, "rw").use { it.setLength(contentLen) }

        val downloaded   = AtomicLong(0)
        val startTime    = System.currentTimeMillis()
        val chunkSize    = contentLen / CHUNKS

        // Chunk download jobs — NO progress callbacks inside to avoid deadlock
        val jobs = (0 until CHUNKS).map { i ->
            val start = i * chunkSize
            val end   = if (i == CHUNKS - 1) contentLen - 1 else start + chunkSize - 1
            async(Dispatchers.IO) {
                downloadChunkRaf(url, outFile, start, end, downloaded)
            }
        }

        // Progress reporter — runs in parallel on a timer, never blocks chunks
        val reporterJob = launch {
            var lastProgress = -1
            var lastNotif    = 0L
            while (jobs.any { !it.isCompleted }) {
                val done     = downloaded.get()
                val progress = ((done * 100) / contentLen).toInt().coerceIn(0, 99)
                if (progress != lastProgress) {
                    lastProgress = progress
                    setProgressAsync(workDataOf(
                        KEY_PROGRESS  to progress,
                        KEY_SPEED_BPS to calcSpeed(done, startTime)
                    ))
                    val now = System.currentTimeMillis()
                    if (now - lastNotif >= NOTIF_THROTTLE_MS) {
                        lastNotif = now
                        notificationHelper.updateProgress(downloadId, title, progress)
                    }
                }
                delay(200)
            }
        }

        val results = jobs.awaitAll()
        reporterJob.cancel()
        results.all { it }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Single chunk download to RandomAccessFile (blocking, runs on IO thread)
    // ─────────────────────────────────────────────────────────────────────────
    private fun downloadChunkRaf(
        url          : String,
        file         : File,
        start        : Long,
        end          : Long,
        totalBytes   : AtomicLong
    ): Boolean {
        var attempt = 0
        while (attempt < CHUNK_RETRIES) {
            try {
                val req = Request.Builder()
                    .url(url)
                    .header("Range", "bytes=$start-$end")
                    .header("Accept-Encoding", "identity")
                    .header("User-Agent", USER_AGENT)
                    .header("Accept", "*/*")
                    .header("Connection", "keep-alive")
                    .build()

                httpClient.newCall(req).execute().use { resp ->
                    // Accept 200 (server ignores range) or 206 (partial content)
                    if (resp.code != 200 && resp.code != 206) {
                        android.util.Log.w("DownloadWorker",
                            "Chunk $start-$end got HTTP ${resp.code}, retrying")
                        attempt++; return@use
                    }
                    val body = resp.body ?: run {
                        android.util.Log.w("DownloadWorker", "Null body for chunk $start-$end")
                        attempt++; return@use
                    }

                    // If server returned 200 instead of 206, it sent the full file;
                    // we need to skip to the right offset in the stream
                    val offset = if (resp.code == 200) start else 0L

                    RandomAccessFile(file, "rw").use { raf ->
                        raf.seek(start)  // write position in output file
                        val buf = ByteArray(BUFFER_SIZE)
                        body.byteStream().use { stream ->
                            var skipped = 0L
                            // Skip to the right position in the stream if server sent full file
                            while (skipped < offset) {
                                val toSkip = minOf(offset - skipped, buf.size.toLong())
                                val s = stream.skip(toSkip)
                                if (s <= 0) break
                                skipped += s
                            }
                            var n = stream.read(buf)
                            var written = start
                            while (n > 0 && written <= end) {
                                // Don't write beyond end of this chunk
                                val canWrite = minOf(n, (end - written + 1).toInt())
                                raf.write(buf, 0, canWrite)
                                totalBytes.addAndGet(canWrite.toLong())
                                written += canWrite
                                n = stream.read(buf)
                            }
                        }
                    }
                    return true
                }
            } catch (e: IOException) {
                android.util.Log.w("DownloadWorker", "IOException chunk $start-$end attempt $attempt: ${e.message}")
                attempt++
                if (attempt < CHUNK_RETRIES) Thread.sleep(500L * attempt)
            } catch (e: Exception) {
                android.util.Log.e("DownloadWorker", "Fatal exception in chunk $start-$end", e)
                return false
            }
        }
        android.util.Log.e("DownloadWorker", "Chunk $start-$end failed after $CHUNK_RETRIES attempts")
        return false
    }

    // ─────────────────────────────────────────────────────────────────────────
    // STREAMING FALLBACK (server doesn't support Range)
    // ─────────────────────────────────────────────────────────────────────────
    private suspend fun streamingDownload(
        url        : String,
        outputPath : String,
        downloadId : String,
        title      : String
    ): Boolean = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url(url)
            .header("Accept-Encoding", "identity")
            .header("User-Agent", USER_AGENT)
            .header("Accept", "*/*")
            .build()

        try {
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    android.util.Log.e("DownloadWorker", "Streaming HTTP error: ${resp.code}")
                    return@withContext false
                }
                val body  = resp.body ?: return@withContext false
                val total = body.contentLength()
                var done  = 0L
                val start = System.currentTimeMillis()
                var lastNotif = 0L
                var errorOccurred = false

                val outFile = File(outputPath).also { it.parentFile?.mkdirs() }
                outFile.outputStream().use { out ->
                    val buf = ByteArray(BUFFER_SIZE)
                    try {
                        body.byteStream().use { stream ->
                            var n = stream.read(buf)
                            while (n > 0) {
                                out.write(buf, 0, n)
                                done += n
                                val progress = if (total > 0) ((done * 100) / total).toInt().coerceIn(0, 99) else 0
                                setProgressAsync(workDataOf(
                                    KEY_PROGRESS  to progress,
                                    KEY_SPEED_BPS to calcSpeed(done, start)
                                ))
                                val now = System.currentTimeMillis()
                                if (now - lastNotif >= NOTIF_THROTTLE_MS) {
                                    lastNotif = now
                                    notificationHelper.updateProgress(downloadId, title, progress)
                                }
                                n = stream.read(buf)
                            }
                        }
                    } catch (e: IOException) {
                        android.util.Log.e("DownloadWorker", "Stream read error: ${e.message}")
                        errorOccurred = true
                    }
                }
                if (errorOccurred) outFile.delete()
                !errorOccurred && done > 0
            }
        } catch (e: Exception) {
            android.util.Log.e("DownloadWorker", "Streaming download exception", e)
            false
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // VIDEO + AUDIO concurrent download then MediaMuxer merge
    // Each stream uses its own chunked parallel download
    // ─────────────────────────────────────────────────────────────────────────
    private suspend fun downloadAndMerge(
        videoUrl  : String,
        audioUrl  : String,
        outputPath: String,
        downloadId: String,
        title     : String
    ): Boolean = withContext(Dispatchers.IO) {
        val tempVideo = File("$outputPath.v.tmp")
        val tempAudio = File("$outputPath.a.tmp")

        try {
            val videoLen = probeContentLength(videoUrl)
            val audioLen = probeContentLength(audioUrl)
            val total    = maxOf(videoLen + audioLen, 1L)

            val videoDone    = AtomicLong(0)
            val audioDone    = AtomicLong(0)
            val startTime    = System.currentTimeMillis()

            // Pre-allocate temp files if we know sizes
            if (videoLen > 0) RandomAccessFile(tempVideo, "rw").use { it.setLength(videoLen) }
            if (audioLen > 0) RandomAccessFile(tempAudio, "rw").use { it.setLength(audioLen) }

            // Launch progress reporter (no blocking in chunk callbacks)
            val progressJob = launch {
                var last = -1
                var lastNotif = 0L
                while (isActive) {
                    val done     = videoDone.get() + audioDone.get()
                    val progress = ((done * 90) / total).toInt().coerceIn(0, 90)
                    if (progress != last) {
                        last = progress
                        setProgressAsync(workDataOf(
                            KEY_PROGRESS  to progress,
                            KEY_SPEED_BPS to calcSpeed(done, startTime)
                        ))
                        val now = System.currentTimeMillis()
                        if (now - lastNotif >= NOTIF_THROTTLE_MS) {
                            lastNotif = now
                            notificationHelper.updateProgress(downloadId, title, progress)
                        }
                    }
                    delay(200)
                }
            }

            // Download video and audio at full speed simultaneously
            val videoJob = async(Dispatchers.IO) {
                val chunkSz = if (videoLen > 0) videoLen / CHUNKS else 0L
                if (chunkSz > 0) {
                    (0 until CHUNKS).map { i ->
                        val s = i * chunkSz
                        val e = if (i == CHUNKS - 1) videoLen - 1 else s + chunkSz - 1
                        async(Dispatchers.IO) { downloadChunkRaf(videoUrl, tempVideo, s, e, videoDone) }
                    }.awaitAll().all { it }
                } else {
                    simpleStreamToFile(videoUrl, tempVideo, videoDone)
                }
            }

            val audioJob = async(Dispatchers.IO) {
                val chunkSz = if (audioLen > 0) audioLen / CHUNKS else 0L
                if (chunkSz > 0) {
                    (0 until CHUNKS).map { i ->
                        val s = i * chunkSz
                        val e = if (i == CHUNKS - 1) audioLen - 1 else s + chunkSz - 1
                        async(Dispatchers.IO) { downloadChunkRaf(audioUrl, tempAudio, s, e, audioDone) }
                    }.awaitAll().all { it }
                } else {
                    simpleStreamToFile(audioUrl, tempAudio, audioDone)
                }
            }

            val videoOk = videoJob.await()
            val audioOk = audioJob.await()
            progressJob.cancel()

            if (!videoOk || !audioOk) return@withContext false

            // Mux: 92–100%
            setProgressAsync(workDataOf(KEY_PROGRESS to 92))
            notificationHelper.updateProgress(downloadId, title, 92)

            val muxOk = muxVideoAudio(tempVideo, tempAudio, File(outputPath))
            if (muxOk) {
                setProgressAsync(workDataOf(KEY_PROGRESS to 100))
                notificationHelper.updateProgress(downloadId, title, 100)
            }
            muxOk
        } finally {
            tempVideo.delete()
            tempAudio.delete()
        }
    }

    /** Simple streaming download to file with AtomicLong byte counter */
    private fun simpleStreamToFile(url: String, file: File, counter: AtomicLong): Boolean {
        return try {
            val req = Request.Builder().url(url)
                .header("Accept-Encoding", "identity")
                .header("User-Agent", USER_AGENT)
                .build()
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return false
                val body = resp.body ?: return false
                file.outputStream().use { out ->
                    val buf    = ByteArray(BUFFER_SIZE)
                    val stream = body.byteStream()
                    var n      = stream.read(buf)
                    while (n > 0) {
                        out.write(buf, 0, n)
                        counter.addAndGet(n.toLong())
                        n = stream.read(buf)
                    }
                }
                true
            }
        } catch (e: Exception) { false }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // MediaMuxer: combine video + audio → final MP4
    // Supports: H.264/H.265 video + AAC/MP4A audio
    // Note: MediaMuxer CANNOT mux Opus (WebM) audio into MP4.
    //       If opus audio is detected, we fall back to copying only the video stream.
    // ─────────────────────────────────────────────────────────────────────────
    private fun muxVideoAudio(videoFile: File, audioFile: File, outFile: File): Boolean {
        val videoEx = MediaExtractor()
        val audioEx = MediaExtractor()
        var muxer: MediaMuxer? = null

        return try {
            videoEx.setDataSource(videoFile.absolutePath)
            audioEx.setDataSource(audioFile.absolutePath)

            // Find video track
            var vidIn = -1
            var videoMime = ""
            for (i in 0 until videoEx.trackCount) {
                val mime = videoEx.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("video/")) { vidIn = i; videoMime = mime; break }
            }

            // Find audio track
            var audIn = -1
            var audioMime = ""
            for (i in 0 until audioEx.trackCount) {
                val mime = audioEx.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) { audIn = i; audioMime = mime; break }
            }

            if (vidIn < 0) {
                android.util.Log.e("DownloadWorker", "No video track found in: ${videoFile.name}")
                return false
            }

            // MediaMuxer can't handle Opus (audio/opus) in MP4 container.
            // Fall back to video-only if the audio codec is unsupported.
            val canMuxAudio = audIn >= 0 &&
                    (audioMime.contains("mp4a") || audioMime.contains("aac") ||
                     audioMime.contains("3gpp") || audioMime.contains("amr"))

            if (!canMuxAudio && audIn >= 0) {
                android.util.Log.w("DownloadWorker",
                    "Unsupported audio codec for MP4 muxer: $audioMime — merging video only")
            }

            muxer = MediaMuxer(outFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            val vidOut = muxer.addTrack(videoEx.getTrackFormat(vidIn))
            val audOut = if (canMuxAudio) muxer.addTrack(audioEx.getTrackFormat(audIn)) else -1

            muxer.start()
            val buf  = ByteBuffer.allocate(2 * 1024 * 1024)  // 2 MB
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

            // Write audio samples (if supported)
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

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────
    private fun probeContentLength(url: String): Long {
        return try {
            // Some CDNs (especially YouTube) reject HEAD but allow GET with Range: bytes=0-0
            // Try HEAD first, fall back to a tiny GET range request
            val headReq = Request.Builder().url(url)
                .head()
                .header("Accept-Encoding", "identity")
                .header("User-Agent", USER_AGENT)
                .build()
            val headResp = httpClient.newCall(headReq).execute()
            val headLen  = headResp.header("Content-Length")?.toLong() ?: -1L
            headResp.close()

            if (headLen > 0) return headLen

            // Fall back: use GET with Range: bytes=0-0 and read Content-Range header
            val rangeReq = Request.Builder().url(url)
                .get()
                .header("Range", "bytes=0-0")
                .header("Accept-Encoding", "identity")
                .header("User-Agent", USER_AGENT)
                .build()
            httpClient.newCall(rangeReq).execute().use { resp ->
                // Content-Range: bytes 0-0/TOTAL  → parse TOTAL
                val contentRange = resp.header("Content-Range")
                if (contentRange != null) {
                    val total = contentRange.substringAfterLast("/").trim().toLongOrNull()
                    if (total != null && total > 0) return total
                }
                resp.header("Content-Length")?.toLong() ?: -1L
            }
        } catch (e: Exception) {
            android.util.Log.w("DownloadWorker", "probeContentLength failed: ${e.message}")
            -1L
        }
    }

    private fun calcSpeed(bytes: Long, startMs: Long): Long {
        val elapsed = max(1L, System.currentTimeMillis() - startMs)
        return (bytes * 1000L) / elapsed
    }
}
