package com.mediadrop.app.util

import android.content.Context
import android.os.Environment
import java.io.File

object FileUtils {

    fun sanitizeFileName(name: String): String =
        name.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().take(100)

    fun formatFileSize(bytes: Long): String = when {
        bytes <= 0L -> "0 B"
        bytes < 1_024 -> "$bytes B"
        bytes < 1_048_576 -> "${bytes / 1_024} KB"
        bytes < 1_073_741_824 -> String.format("%.1f MB", bytes / 1_048_576.0)
        else -> String.format("%.2f GB", bytes / 1_073_741_824.0)
    }

    fun formatDuration(seconds: Long): String {
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }

    fun buildOutputPath(
        context: Context,
        title: String,
        format: String,
        platform: String
    ): String {
        val sanitized = sanitizeFileName(title)
        val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: context.filesDir
        dir.mkdirs()
        val timestamp = System.currentTimeMillis()
        return File(dir, "${platform}_${timestamp}_$sanitized.$format").absolutePath
    }

    fun getFileSize(path: String): Long =
        File(path).takeIf { it.exists() }?.length() ?: 0L

    fun fileExists(path: String): Boolean = File(path).exists()
}
