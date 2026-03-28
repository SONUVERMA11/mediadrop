package com.mediadrop.app.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(tableName = "downloads")
data class DownloadEntity(
    @PrimaryKey
    val id           : String = UUID.randomUUID().toString(),
    val title        : String,
    val sourceUrl    : String,
    val localPath    : String,
    val format       : String,          // file extension: mp4, mp3, etc.
    val formatId     : String = "",     // yt-dlp format id (e.g. "137" for 1080p) — NEW
    val hasAudio     : Boolean = true,  // false = video-only stream needing merge — NEW
    val quality      : String,          // "1080p", "720p", "128kbps", etc.
    val fileSize     : Long = 0L,
    val platform     : String,
    val thumbnailUrl : String,
    val status       : String = "QUEUED",
    val progress     : Int = 0,
    val speed        : String = "",
    val eta          : String = "",
    val createdAt    : Long = System.currentTimeMillis()
)
