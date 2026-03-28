package com.mediadrop.app.domain.model

data class MediaInfo(
    val title        : String,
    val thumbnailUrl : String,
    val duration     : Long,                   // seconds
    val platform     : SupportedPlatform,
    val sourceUrl    : String,
    val videoFormats : List<VideoFormat>,
    val audioFormats : List<AudioFormat>
)

data class VideoFormat(
    val formatId   : String,
    val resolution : String,    // "1080p", "720p", etc.
    val extension  : String,    // "mp4", "webm"
    val fileSize   : Long?,
    val fps        : Int?,
    val codec      : String?,
    val directUrl  : String?,
    val hasAudio   : Boolean = true   // false = video-only stream, needs merge
)

data class AudioFormat(
    val formatId  : String,
    val bitrate   : String,     // "320kbps"
    val extension : String,     // "mp3", "m4a", "flac"
    val fileSize  : Long?,
    val codec     : String?,
    val directUrl : String?
)
