package com.mediadrop.app.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(tableName = "downloads")
data class DownloadEntity(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val sourceUrl: String,
    val localPath: String,
    val format: String,
    val quality: String,
    val fileSize: Long = 0L,
    val platform: String,
    val thumbnailUrl: String,
    val status: String = "QUEUED",   // DownloadStatus.name
    val progress: Int = 0,
    val speed: String = "",
    val eta: String = "",
    val createdAt: Long = System.currentTimeMillis()
)
