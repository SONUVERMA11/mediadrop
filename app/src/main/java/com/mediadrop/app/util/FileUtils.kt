package com.mediadrop.app.util

import android.content.Context
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import java.io.File

object FileUtils {

    private val AUDIO_FORMATS = setOf("mp3", "m4a", "aac", "opus", "flac", "wav", "ogg")
    private val VIDEO_FORMATS = setOf("mp4", "mkv", "webm", "avi", "mov", "m4v", "3gp")

    fun sanitizeFileName(name: String): String =
        name.replace(Regex("[\\\\/:*?\"<>|]"), "_")
            .replace(Regex("\\s+"), "_")
            .trim()
            .take(100)
            .ifBlank { "download" }

    fun formatFileSize(bytes: Long): String = when {
        bytes <= 0L           -> "0 B"
        bytes < 1_024         -> "$bytes B"
        bytes < 1_048_576     -> "${bytes / 1_024} KB"
        bytes < 1_073_741_824 -> "%.1f MB".format(bytes / 1_048_576.0)
        else                  -> "%.2f GB".format(bytes / 1_073_741_824.0)
    }

    fun formatDuration(seconds: Long): String {
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }

    /**
     * Build an output path in PUBLIC storage so the file is
     * immediately visible in the system Files app and Gallery.
     *
     * Folder layout:
     *   Audio  →  Music/DC/<title>.<ext>
     *   Video  →  Movies/DC/<title>.<ext>
     *   Other  →  Downloads/DC/<title>.<ext>
     */
    fun buildOutputPath(
        context: Context,
        title: String,
        format: String,
        platform: String
    ): String {
        val sanitized = sanitizeFileName(title)
        val timestamp = System.currentTimeMillis()
        val fileName  = "${sanitized}_${timestamp}.$format"

        val publicDir: File = when (format.lowercase()) {
            in AUDIO_FORMATS ->
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)
                    .resolve("DC")
            in VIDEO_FORMATS ->
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
                    .resolve("DC")
            else ->
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                    .resolve("DC")
        }

        // Fallback to app-specific dir if public storage unavailable
        val dir = if (publicDir.exists() || publicDir.mkdirs()) {
            publicDir
        } else {
            (context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir)
                .also { it.mkdirs() }
        }

        return File(dir, fileName).absolutePath
    }

    fun getFileSize(path: String): Long =
        File(path).takeIf { it.exists() }?.length() ?: 0L

    fun fileExists(path: String): Boolean = File(path).exists()

    /**
     * Tell the system's MediaScanner about a new file so it shows up
     * immediately in the Gallery, Files app, and music players.
     */
    fun scanFile(context: Context, path: String, mimeType: String? = null) {
        MediaScannerConnection.scanFile(
            context,
            arrayOf(path),
            if (mimeType != null) arrayOf(mimeType) else null,
            null
        )
    }

    fun guessMime(format: String): String = when (format.lowercase()) {
        "mp4", "m4v"  -> "video/mp4"
        "mkv"         -> "video/x-matroska"
        "webm"        -> "video/webm"
        "avi"         -> "video/avi"
        "mov"         -> "video/quicktime"
        "3gp"         -> "video/3gpp"
        "mp3"         -> "audio/mpeg"
        "m4a"         -> "audio/mp4"
        "aac"         -> "audio/aac"
        "opus"        -> "audio/opus"
        "flac"        -> "audio/flac"
        "wav"         -> "audio/wav"
        "ogg"         -> "audio/ogg"
        else          -> "*/*"
    }
}
