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
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max
import kotlin.math.min

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

        // ── Speed tuning ────────────────────────────────────────────────────
        private const val CHUNKS        = 32          // 32 parallel HTTP range connections
        private const val BUFFER_SIZE   = 2_097_152   // 2 MB NIO direct buffer per chunk
        private const val CHUNK_RETRIES = 3           // retry each chunk up to 3× on failure
        private const val TIMEOUT_CONN  = 15L         // connect timeout (seconds)
        private const val TIMEOUT_READ  = 60L         // read timeout (seconds)
    }

    // ── Aggressively tuned HTTP client ─────────────────────────────────────
    // • Large connection pool: 32 idle connections, 10-min keep-alive
    // • Dispatcher: 64 max global, 32 per host — saturates CDN connections
    // • followRedirects + retryOnConnectionFailure for resilience
    private val httpClient = OkHttpClient.Builder()
        .connectionPool(ConnectionPool(32, 10, TimeUnit.MINUTES))
        .dispatcher(Dispatcher().apply {
            maxRequests        = 64
            maxRequestsPerHost = 32
        })
        .connectTimeout(TIMEOUT_CONN, TimeUnit.SECONDS)
        .readTimeout(TIMEOUT_READ, TimeUnit.SECONDS)
        .writeTimeout(TIMEOUT_READ, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .retryOnConnectionFailure(true)
        // Disable minSpeed / maxSpeed throttle on Android radio
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
            // ── 1. Resolve CDN URL(s) ─────────────────────────────────────
            val urlDtoResult = mediaRepository.getDownloadUrlDto(mediaUrl, formatId, hasAudio)
            if (urlDtoResult.isFailure) {
                downloadRepository.updateDownloadStatus(downloadId, DownloadStatus.FAILED)
                return@withContext if (runAttemptCount < 2) Result.retry() else Result.failure()
            }
            val urlDto   = urlDtoResult.getOrThrow()
            val videoUrl = urlDto.url
            val audioUrl = urlDto.audioUrl

            // ── 2. Pre-warm connection to CDN (hides first-packet latency) ─
            launch { preWarm(videoUrl) }
            if (audioUrl != null) launch { preWarm(audioUrl) }

            // ── 3. Download ───────────────────────────────────────────────
            val success = if (audioUrl != null) {
                // Video-only stream: parallel download both, then MediaMuxer merge
                downloadAndMerge(videoUrl, audioUrl, outputPath, downloadId, title, format)
            } else {
                val len = probeContentLength(videoUrl)
                if (len > 0) chunkedDownload(videoUrl, outputPath, len, downloadId, title)
                else         streamingDownload(videoUrl, outputPath, downloadId, title)
            }

            if (success) {
                val fileSize = FileUtils.getFileSize(outputPath)
                downloadRepository.updateCompletion(downloadId, outputPath, fileSize)
                FileUtils.scanFile(context, outputPath, FileUtils.guessMime(format))
                notificationHelper.showCompletionNotification(downloadId, title, fileSize, outputPath, format)
                setProgressAsync(workDataOf(KEY_PROGRESS to 100))
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

    // ─────────────────────────────────────────────────────────────────────────
    // CHUNKED PARALLEL DOWNLOAD  (32 concurrent range requests)
    // Uses NIO FileChannel + direct ByteBuffer for maximum I/O throughput
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
        FileChannel.open(
            outFile.toPath(),
            StandardOpenOption.CREATE,
            StandardOpenOption.WRITE
        ).use { ch -> ch.truncate(contentLen) }

        val downloaded   = AtomicLong(0)
        val startTime    = System.currentTimeMillis()
        var lastProgress = -1
        var lastNotif    = 0L

        fun reportProgress() {
            val done     = downloaded.get()
            val progress = ((done * 100) / contentLen).toInt().coerceIn(0, 100)
            if (progress != lastProgress) {
                lastProgress = progress
                setProgressAsync(workDataOf(
                    KEY_PROGRESS  to progress,
                    KEY_SPEED_BPS to calcSpeed(done, startTime)
                ))
                val now = System.currentTimeMillis()
                if (now - lastNotif > 300) {
                    lastNotif = now
                    notificationHelper.updateProgress(downloadId, title, progress)
                }
            }
        }

        val chunkSize = contentLen / CHUNKS

        val jobs = (0 until CHUNKS).map { i ->
            val start = i * chunkSize
            val end   = if (i == CHUNKS - 1) contentLen - 1 else start + chunkSize - 1
            async(Dispatchers.IO) {
                downloadChunk(url, outFile, start, end, downloaded) { reportProgress() }
            }
        }

        jobs.awaitAll().all { it }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // SINGLE CHUNK download — with per-chunk retry + NIO FileChannel writes
    // ─────────────────────────────────────────────────────────────────────────
    private fun downloadChunk(
        url          : String,
        file         : File,
        start        : Long,
        end          : Long,
        totalDownloaded: AtomicLong,
        onProgress   : () -> Unit
    ): Boolean {
        var attempt = 0
        while (attempt < CHUNK_RETRIES) {
            try {
                val req = Request.Builder()
                    .url(url)
                    .header("Range", "bytes=$start-$end")
                    .header("Accept-Encoding", "identity")   // no gzip overhead on binary data
                    .header("Connection", "keep-alive")
                    .build()

                httpClient.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful && resp.code != 206) {
                        attempt++; return@use
                    }
                    val body = resp.body ?: run { attempt++; return@use }

                    // NIO FileChannel write — significantly faster than RandomAccessFile
                    FileChannel.open(
                        file.toPath(),
                        StandardOpenOption.WRITE
                    ).use { channel ->
                        channel.position(start)
                        val directBuf = ByteBuffer.allocateDirect(min(BUFFER_SIZE.toLong(), end - start + 1).toInt())
                        body.byteStream().use { stream ->
                            val javaBuf = ByteArray(directBuf.capacity())
                            var n = stream.read(javaBuf)
                            while (n > 0) {
                                directBuf.clear()
                                directBuf.put(javaBuf, 0, n)
                                directBuf.flip()
                                while (directBuf.hasRemaining()) channel.write(directBuf)
                                totalDownloaded.addAndGet(n.toLong())
                                onProgress()
                                n = stream.read(javaBuf)
                            }
                        }
                    }
                    return true
                }
            } catch (e: IOException) {
                attempt++
                if (attempt < CHUNK_RETRIES) Thread.sleep(200L * attempt)
            } catch (e: Exception) {
                return false
            }
        }
        return false
    }

    // ─────────────────────────────────────────────────────────────────────────
    // STREAMING FALLBACK (server doesn't support Range requests)
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
            .header("Connection", "keep-alive")
            .build()

        httpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return@withContext false
            val body  = resp.body ?: return@withContext false
            val total = body.contentLength()
            var done  = 0L
            val start = System.currentTimeMillis()
            var lastNotif = 0L

            FileChannel.open(
                File(outputPath).also { it.parentFile?.mkdirs() }.toPath(),
                StandardOpenOption.CREATE, StandardOpenOption.WRITE
            ).use { channel ->
                val directBuf = ByteBuffer.allocateDirect(BUFFER_SIZE)
                val javaBuf   = ByteArray(BUFFER_SIZE)
                body.byteStream().use { stream ->
                    var n = stream.read(javaBuf)
                    while (n > 0) {
                        directBuf.clear()
                        directBuf.put(javaBuf, 0, n)
                        directBuf.flip()
                        while (directBuf.hasRemaining()) channel.write(directBuf)
                        done += n
                        val progress = if (total > 0) ((done * 100) / total).toInt() else 0
                        setProgressAsync(workDataOf(KEY_PROGRESS to progress,
                            KEY_SPEED_BPS to calcSpeed(done, start)))
                        val now = System.currentTimeMillis()
                        if (now - lastNotif > 300) {
                            lastNotif = now
                            notificationHelper.updateProgress(downloadId, title, progress)
                        }
                        n = stream.read(javaBuf)
                    }
                }
            }
            true
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // VIDEO + AUDIO concurrent download + MediaMuxer merge
    // ─────────────────────────────────────────────────────────────────────────
    private suspend fun downloadAndMerge(
        videoUrl  : String,
        audioUrl  : String,
        outputPath: String,
        downloadId: String,
        title     : String,
        format    : String
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
            var lastProgress = -1

            suspend fun reportCombined() {
                val done     = videoDone.get() + audioDone.get()
                // Map to 0-90% (last 10% reserved for muxing)
                val progress = ((done * 90) / total).toInt().coerceIn(0, 90)
                if (progress != lastProgress) {
                    lastProgress = progress
                    setProgressAsync(workDataOf(
                        KEY_PROGRESS  to progress,
                        KEY_SPEED_BPS to calcSpeed(done, startTime)
                    ))
                    notificationHelper.updateProgress(downloadId, title, progress)
                }
            }

            // Download video and audio at full speed simultaneously
            val videoJob = async(Dispatchers.IO) {
                if (videoLen > 0) {
                    FileChannel.open(tempVideo.toPath(), StandardOpenOption.CREATE, StandardOpenOption.WRITE)
                        .use { it.truncate(videoLen) }
                    val chunkSz = videoLen / CHUNKS
                    (0 until CHUNKS).map { i ->
                        val s = i * chunkSz
                        val e = if (i == CHUNKS - 1) videoLen - 1 else s + chunkSz - 1
                        async(Dispatchers.IO) { downloadChunk(videoUrl, tempVideo, s, e, videoDone) { runBlocking { reportCombined() } } }
                    }.awaitAll().all { it }
                } else {
                    streamingToFile(videoUrl, tempVideo) { n -> videoDone.addAndGet(n.toLong()); runBlocking { reportCombined() } }
                }
            }
            val audioJob = async(Dispatchers.IO) {
                if (audioLen > 0) {
                    FileChannel.open(tempAudio.toPath(), StandardOpenOption.CREATE, StandardOpenOption.WRITE)
                        .use { it.truncate(audioLen) }
                    val chunkSz = audioLen / CHUNKS
                    (0 until CHUNKS).map { i ->
                        val s = i * chunkSz
                        val e = if (i == CHUNKS - 1) audioLen - 1 else s + chunkSz - 1
                        async(Dispatchers.IO) { downloadChunk(audioUrl, tempAudio, s, e, audioDone) { runBlocking { reportCombined() } } }
                    }.awaitAll().all { it }
                } else {
                    streamingToFile(audioUrl, tempAudio) { n -> audioDone.addAndGet(n.toLong()); runBlocking { reportCombined() } }
                }
            }

            if (!videoJob.await() || !audioJob.await()) return@withContext false

            // Mux at 91–100%
            setProgressAsync(workDataOf(KEY_PROGRESS to 92))
            notificationHelper.updateProgress(downloadId, title, 92)

            val ok = muxVideoAudio(tempVideo, tempAudio, File(outputPath))
            if (ok) {
                setProgressAsync(workDataOf(KEY_PROGRESS to 100))
                notificationHelper.updateProgress(downloadId, title, 100)
            }
            ok
        } finally {
            tempVideo.delete()
            tempAudio.delete()
        }
    }

    private fun streamingToFile(url: String, file: File, onBytes: (Int) -> Unit): Boolean {
        return try {
            val req = Request.Builder().url(url)
                .header("Accept-Encoding", "identity").build()
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return false
                val body = resp.body ?: return false
                FileChannel.open(file.toPath(), StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { ch ->
                    val directBuf = ByteBuffer.allocateDirect(BUFFER_SIZE)
                    val javaBuf   = ByteArray(BUFFER_SIZE)
                    body.byteStream().use { stream ->
                        var n = stream.read(javaBuf)
                        while (n > 0) {
                            directBuf.clear(); directBuf.put(javaBuf, 0, n); directBuf.flip()
                            while (directBuf.hasRemaining()) ch.write(directBuf)
                            onBytes(n); n = stream.read(javaBuf)
                        }
                    }
                }
                true
            }
        } catch (e: Exception) { false }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // MediaMuxer: combine video + audio temp files → final MP4
    // ─────────────────────────────────────────────────────────────────────────
    private fun muxVideoAudio(videoFile: File, audioFile: File, outFile: File): Boolean {
        return try {
            val muxer  = MediaMuxer(outFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val videoEx = MediaExtractor().apply { setDataSource(videoFile.absolutePath) }
            val audioEx = MediaExtractor().apply { setDataSource(audioFile.absolutePath) }

            var vidIn = -1; var vidOut = -1
            for (i in 0 until videoEx.trackCount) {
                val fmt = videoEx.getTrackFormat(i)
                if (fmt.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true) {
                    vidIn = i; vidOut = muxer.addTrack(fmt); break
                }
            }
            var audIn = -1; var audOut = -1
            for (i in 0 until audioEx.trackCount) {
                val fmt = audioEx.getTrackFormat(i)
                if (fmt.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                    audIn = i; audOut = muxer.addTrack(fmt); break
                }
            }

            if (vidIn < 0 || audIn < 0) { muxer.release(); return false }
            muxer.start()

            val buf  = ByteBuffer.allocate(4 * 1024 * 1024)   // 4 MB mux buffer
            val info = MediaCodec.BufferInfo()

            videoEx.selectTrack(vidIn)
            videoEx.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
            while (true) {
                info.size = videoEx.readSampleData(buf, 0)
                if (info.size < 0) break
                info.presentationTimeUs = videoEx.sampleTime
                info.flags = videoEx.sampleFlags
                muxer.writeSampleData(vidOut, buf, info)
                videoEx.advance()
            }

            audioEx.selectTrack(audIn)
            audioEx.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
            while (true) {
                info.size = audioEx.readSampleData(buf, 0)
                if (info.size < 0) break
                info.presentationTimeUs = audioEx.sampleTime
                info.flags = audioEx.sampleFlags
                muxer.writeSampleData(audOut, buf, info)
                audioEx.advance()
            }

            videoEx.release(); audioEx.release()
            muxer.stop(); muxer.release()
            true
        } catch (e: Exception) { outFile.delete(); false }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    /** HEAD request to get Content-Length. Returns -1 if server doesn't support it. */
    private fun probeContentLength(url: String): Long {
        return try {
            val req = Request.Builder().url(url).head()
                .header("Accept-Encoding", "identity").build()
            httpClient.newCall(req).execute().use { resp ->
                resp.header("Content-Length")?.toLong() ?: -1L
            }
        } catch (e: Exception) { -1L }
    }

    /** Fires a GET and immediately closes it — warms TCP connection + TLS in the pool */
    private fun preWarm(url: String) {
        try {
            httpClient.newCall(
                Request.Builder().url(url).head()
                    .header("Accept-Encoding", "identity").build()
            ).execute().close()
        } catch (_: Exception) {}
    }

    private fun calcSpeed(bytes: Long, startMs: Long): Long {
        val elapsed = max(1L, System.currentTimeMillis() - startMs)
        return (bytes * 1000L) / elapsed   // bytes per second
    }
}
