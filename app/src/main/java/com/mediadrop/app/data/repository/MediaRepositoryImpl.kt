package com.mediadrop.app.data.repository

import com.mediadrop.app.data.remote.api.MediaApiService
import com.mediadrop.app.data.remote.dto.FormatDto
import com.mediadrop.app.domain.model.AudioFormat
import com.mediadrop.app.domain.model.MediaInfo
import com.mediadrop.app.domain.model.SupportedPlatform
import com.mediadrop.app.domain.model.VideoFormat
import com.mediadrop.app.domain.repository.MediaRepository
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import javax.inject.Inject

class MediaRepositoryImpl @Inject constructor(
    private val apiService: MediaApiService,
    private val okHttpClient: OkHttpClient
) : MediaRepository {

    override suspend fun fetchMediaInfo(url: String): Result<MediaInfo> = runCatching {
        val dto = apiService.getMediaInfo(url)
        if (dto.error != null) error(dto.error)

        val platform = SupportedPlatform.detect(url)

        val videoFormats = dto.formats
            ?.filter { it.height != null && it.vcodec != null && it.vcodec != "none" }
            ?.map { it.toVideoFormat() }
            ?.sortedByDescending { it.resolution.removeSuffix("p").toIntOrNull() ?: 0 }
            ?: emptyList()

        val audioFormats = dto.formats
            ?.filter {
                (it.vcodec == null || it.vcodec == "none") &&
                        it.acodec != null && it.acodec != "none"
            }
            ?.map { it.toAudioFormat() }
            ?.sortedByDescending { it.bitrate.removeSuffix("kbps").toDoubleOrNull() ?: 0.0 }
            ?: emptyList()

        MediaInfo(
            title = dto.title,
            thumbnailUrl = dto.thumbnail ?: "",
            duration = dto.duration ?: 0L,
            platform = platform,
            sourceUrl = url,
            videoFormats = videoFormats,
            audioFormats = audioFormats
        )
    }

    override suspend fun downloadMedia(
        url: String,
        formatId: String,
        outputPath: String,
        onProgress: (Int) -> Unit
    ): Result<String> = runCatching {
        val downloadUrlDto = apiService.getDownloadUrl(url, formatId)
        val request = Request.Builder().url(downloadUrlDto.url).build()

        okHttpClient.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "HTTP ${response.code}" }
            val body = checkNotNull(response.body) { "Empty response body" }
            val total = body.contentLength()
            var downloaded = 0L

            File(outputPath).also { it.parentFile?.mkdirs() }.outputStream().use { out ->
                body.byteStream().use { input ->
                    val buf = ByteArray(8 * 1024)
                    var read = input.read(buf)
                    while (read >= 0) {
                        out.write(buf, 0, read)
                        downloaded += read
                        if (total > 0) onProgress(((downloaded * 100) / total).toInt())
                        read = input.read(buf)
                    }
                }
            }
        }
        outputPath
    }
}

// ── Extension mappers ────────────────────────────────────────────────────────

private fun FormatDto.toVideoFormat() = VideoFormat(
    formatId = formatId,
    resolution = height?.let { "${it}p" } ?: resolution ?: "unknown",
    extension = ext,
    fileSize = filesize ?: filesizeApprox,
    fps = fps?.toInt(),
    codec = vcodec,
    directUrl = url
)

private fun FormatDto.toAudioFormat() = AudioFormat(
    formatId = formatId,
    bitrate = abr?.let { "${it.toInt()}kbps" } ?: formatNote ?: "unknown",
    extension = ext,
    fileSize = filesize ?: filesizeApprox,
    codec = acodec,
    directUrl = url
)
