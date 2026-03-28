package com.mediadrop.app.domain.model

data class MediaInfo(
    val title: String,
    val thumbnailUrl: String,
    val duration: Long, // seconds
    val platform: SupportedPlatform,
    val sourceUrl: String,
    val videoFormats: List<VideoFormat>,
    val audioFormats: List<AudioFormat>
)

data class VideoFormat(
    val formatId: String,
    val resolution: String,   // "1080p", "720p", etc.
    val extension: String,    // "mp4", "webm", "mkv"
    val fileSize: Long?,      // bytes, null if unknown
    val fps: Int?,
    val codec: String?,
    val directUrl: String?
)

data class AudioFormat(
    val formatId: String,
    val bitrate: String,      // "320kbps"
    val extension: String,   // "mp3", "m4a", "flac", etc.
    val fileSize: Long?,
    val codec: String?,
    val directUrl: String?
)
