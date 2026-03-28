package com.mediadrop.app.domain.model

/**
 * Represents a playlist (e.g. YouTube playlist, channel, album).
 */
data class PlaylistInfo(
    val title: String,
    val thumbnailUrl: String,
    val sourceUrl: String,
    val entries: List<PlaylistEntry>
)

data class PlaylistEntry(
    val id: String,
    val title: String,
    val url: String,
    val duration: Long,         // seconds
    val thumbnailUrl: String,
    val uploader: String = ""
)
