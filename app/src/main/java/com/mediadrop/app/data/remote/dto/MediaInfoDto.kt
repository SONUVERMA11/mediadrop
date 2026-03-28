package com.mediadrop.app.data.remote.dto

import com.google.gson.annotations.SerializedName

data class MediaInfoDto(
    @SerializedName("title") val title: String = "",
    @SerializedName("thumbnail") val thumbnail: String? = null,
    @SerializedName("duration") val duration: Long? = null,
    @SerializedName("extractor") val extractor: String? = null,
    @SerializedName("formats") val formats: List<FormatDto>? = null,
    @SerializedName("error") val error: String? = null
)

data class FormatDto(
    @SerializedName("format_id") val formatId: String = "",
    @SerializedName("ext") val ext: String = "",
    @SerializedName("resolution") val resolution: String? = null,
    @SerializedName("width") val width: Int? = null,
    @SerializedName("height") val height: Int? = null,
    @SerializedName("fps") val fps: Double? = null,
    @SerializedName("vcodec") val vcodec: String? = null,
    @SerializedName("acodec") val acodec: String? = null,
    @SerializedName("abr") val abr: Double? = null,
    @SerializedName("vbr") val vbr: Double? = null,
    @SerializedName("filesize") val filesize: Long? = null,
    @SerializedName("filesize_approx") val filesizeApprox: Long? = null,
    @SerializedName("url") val url: String? = null,
    @SerializedName("format_note") val formatNote: String? = null
)

data class DownloadUrlDto(
    @SerializedName("url") val url: String = "",
    @SerializedName("filename") val filename: String? = null,
    @SerializedName("filesize") val filesize: Long? = null
)
