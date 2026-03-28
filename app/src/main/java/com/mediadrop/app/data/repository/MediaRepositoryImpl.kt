package com.mediadrop.app.data.repository

import com.google.gson.Gson
import com.mediadrop.app.data.remote.api.MediaApiService
import com.mediadrop.app.data.remote.dto.FormatDto
import com.mediadrop.app.data.remote.dto.MediaInfoDto
import com.mediadrop.app.domain.model.*
import com.mediadrop.app.domain.repository.MediaRepository
import retrofit2.HttpException
import java.io.IOException
import javax.inject.Inject

class MediaRepositoryImpl @Inject constructor(
    private val apiService: MediaApiService
) : MediaRepository {

    override suspend fun fetchMediaInfo(url: String): Result<MediaInfo> {
        return try {
            val dto = apiService.getMediaInfo(url)
            if (dto.error != null) return Result.failure(Exception(dto.error))

            val platform = SupportedPlatform.detect(url)

            val videoFormats = dto.formats
                ?.filter { it.height != null && it.vcodec != null && it.vcodec != "none" }
                ?.map { it.toVideoFormat() }
                ?.sortedByDescending { it.resolution.removeSuffix("p").toIntOrNull() ?: 0 }
                ?: emptyList()

            val audioFormats = dto.formats
                ?.filter { (it.vcodec == null || it.vcodec == "none") && it.acodec != null && it.acodec != "none" }
                ?.map { it.toAudioFormat() }
                ?.sortedByDescending { it.bitrate.removeSuffix("kbps").toDoubleOrNull() ?: 0.0 }
                ?: emptyList()

            Result.success(MediaInfo(
                title        = dto.title,
                thumbnailUrl = dto.thumbnail ?: "",
                duration     = dto.duration ?: 0L,
                platform     = platform,
                sourceUrl    = url,
                videoFormats = videoFormats,
                audioFormats = audioFormats
            ))
        } catch (e: HttpException) {
            // Read the actual error body from backend (e.g. {"error": "GEO_RESTRICTED"})
            val errorBody = try {
                e.response()?.errorBody()?.string()
            } catch (_: Exception) { null }
            val backendMsg = try {
                Gson().fromJson(errorBody, MediaInfoDto::class.java)?.error
            } catch (_: Exception) { null }
            val message = backendMsg ?: "HTTP ${e.code()}: ${e.message()}"
            android.util.Log.e("MediaRepo", "fetchMediaInfo HTTP error: $message")
            Result.failure(Exception(message))
        } catch (e: IOException) {
            android.util.Log.e("MediaRepo", "fetchMediaInfo network error", e)
            Result.failure(Exception("network: ${e.message}"))
        } catch (e: Exception) {
            android.util.Log.e("MediaRepo", "fetchMediaInfo error", e)
            Result.failure(e)
        }
    }

    override suspend fun fetchPlaylistInfo(url: String): Result<PlaylistInfo> = runCatching {
        val dto = apiService.getPlaylistInfo(url)
        if (dto.error != null) error(dto.error)
        PlaylistInfo(
            title        = dto.title,
            thumbnailUrl = dto.thumbnail ?: "",
            sourceUrl    = url,
            entries      = dto.entries?.map { e ->
                PlaylistEntry(
                    id           = e.id,
                    title        = e.title,
                    url          = e.url,
                    duration     = e.duration ?: 0L,
                    thumbnailUrl = e.thumbnail ?: "",
                    uploader     = e.uploader ?: ""
                )
            } ?: emptyList()
        )
    }
}

// ── Extension mappers ─────────────────────────────────────────────────────────

private fun FormatDto.toVideoFormat() = VideoFormat(
    formatId   = formatId,
    resolution = height?.let { "${it}p" } ?: resolution ?: "unknown",
    extension  = ext,
    fileSize   = filesize ?: filesizeApprox,
    fps        = fps?.toInt(),
    codec      = vcodec,
    directUrl  = url,
    hasAudio   = hasAudio ?: (acodec != null && acodec != "none")
)

private fun FormatDto.toAudioFormat() = AudioFormat(
    formatId  = formatId,
    bitrate   = abr?.let { "${it.toInt()}kbps" } ?: formatNote ?: "unknown",
    extension = ext,
    fileSize  = filesize ?: filesizeApprox,
    codec     = acodec,
    directUrl = url
)
