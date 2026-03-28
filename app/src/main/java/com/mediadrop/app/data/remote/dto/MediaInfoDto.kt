package com.mediadrop.app.data.remote.dto

import com.google.gson.annotations.SerializedName

data class MediaInfoDto(
    val title    : String = "",
    val thumbnail: String? = null,
    val duration : Long? = null,
    val extractor: String? = null,
    val formats  : List<FormatDto>? = null,
    val error    : String? = null
)

data class FormatDto(
    @SerializedName("format_id")       val formatId      : String = "",
    val ext        : String = "",
    val resolution : String? = null,
    val width      : Int? = null,
    val height     : Int? = null,
    val fps        : Double? = null,
    val vcodec     : String? = null,
    val acodec     : String? = null,
    @SerializedName("has_video")       val hasVideo      : Boolean? = null,
    @SerializedName("has_audio")       val hasAudio      : Boolean? = null,   // ← NEW
    val abr        : Double? = null,
    val vbr        : Double? = null,
    val filesize   : Long? = null,
    @SerializedName("filesize_approx") val filesizeApprox: Long? = null,
    val url        : String? = null,
    @SerializedName("format_note")     val formatNote    : String? = null
)

data class DownloadUrlDto(
    val url      : String = "",
    @SerializedName("audio_url") val audioUrl: String? = null,
    val headers  : Map<String, String>? = null,   // CDN headers (User-Agent, etc.) from backend
    val filename : String? = null,
    val filesize : Long? = null,
    val error    : String? = null
)

// ── Playlist DTOs ─────────────────────────────────────────────────────────────

data class PlaylistInfoDto(
    val title    : String = "",
    val thumbnail: String? = null,
    val entries  : List<PlaylistEntryDto>? = null,
    val error    : String? = null
)

data class PlaylistEntryDto(
    val id       : String = "",
    val title    : String = "",
    val url      : String = "",
    val duration : Long? = null,
    val thumbnail: String? = null,
    val uploader : String? = null
)
