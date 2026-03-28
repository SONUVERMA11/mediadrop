package com.mediadrop.app.util

import android.content.Context
import android.media.MediaScannerConnection
import android.os.Environment
import com.mediadrop.app.ui.settings.SaveLocation
import java.io.File

object FileUtils {

    private val AUDIO_FORMATS = setOf("mp3","m4a","aac","opus","flac","wav","ogg")
    private val VIDEO_FORMATS = setOf("mp4","mkv","webm","avi","mov","m4v","3gp")

    fun sanitizeFileName(name: String): String =
        name.replace(Regex("[\\\\/:*?\"<>|]"), "_")
            .replace(Regex("\\s+"), "_")
            .trim().take(100).ifBlank { "download" }

    fun formatFileSize(bytes: Long): String = when {
        bytes <= 0L           -> "0 B"
        bytes < 1_024         -> "$bytes B"
        bytes < 1_048_576     -> "${bytes / 1_024} KB"
        bytes < 1_073_741_824 -> "%.1f MB".format(bytes / 1_048_576.0)
        else                  -> "%.2f GB".format(bytes / 1_073_741_824.0)
    }

    fun formatDuration(seconds: Long): String {
        val h = seconds / 3600; val m = (seconds % 3600) / 60; val s = seconds % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }

    /**
     * Builds the output path based on the user's chosen SaveLocation.
     * Everything goes to public storage so it shows up in Files and Gallery.
     */
    fun buildOutputPath(
        context      : Context,
        title        : String,
        format       : String,
        platform     : String,
        saveLocation : SaveLocation = SaveLocation.SMART
    ): String {
        val sanitized = sanitizeFileName(title)
        val fileName  = "${sanitized}_${System.currentTimeMillis()}.$format"

        val publicDir: File = when (saveLocation) {
            SaveLocation.SMART -> {
                // Smart: audio → Music, video → Movies, other → Downloads
                when (format.lowercase()) {
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
            }
            SaveLocation.DOWNLOADS ->
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                    .resolve("DC")
            SaveLocation.MOVIES ->
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
                    .resolve("DC")
            SaveLocation.MUSIC ->
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)
                    .resolve("DC")
        }

        val dir = if (publicDir.exists() || publicDir.mkdirs()) {
            publicDir
        } else {
            // Fallback to app-specific dir
            (context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir)
                .also { it.mkdirs() }
        }
        return File(dir, fileName).absolutePath
    }

    fun getFileSize(path: String): Long = File(path).takeIf { it.exists() }?.length() ?: 0L
    fun fileExists(path: String): Boolean = File(path).exists()

    /** Notify the system MediaScanner so the file appears in gallery + file manager */
    fun scanFile(context: Context, path: String, mimeType: String? = null) {
        MediaScannerConnection.scanFile(
            context, arrayOf(path),
            if (mimeType != null) arrayOf(mimeType) else null, null
        )
    }

    fun guessMime(format: String): String = when (format.lowercase()) {
        "mp4","m4v"  -> "video/mp4"
        "mkv"        -> "video/x-matroska"
        "webm"       -> "video/webm"
        "avi"        -> "video/avi"
        "mov"        -> "video/quicktime"
        "3gp"        -> "video/3gpp"
        "mp3"        -> "audio/mpeg"
        "m4a"        -> "audio/mp4"
        "aac"        -> "audio/aac"
        "opus"       -> "audio/opus"
        "flac"       -> "audio/flac"
        "wav"        -> "audio/wav"
        "ogg"        -> "audio/ogg"
        else         -> "*/*"
    }
}
