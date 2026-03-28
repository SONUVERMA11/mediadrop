package com.mediadrop.app.util

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.ForegroundInfo
import com.mediadrop.app.R
import com.mediadrop.app.ui.MainActivity
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NotificationHelper @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        const val CHANNEL_DOWNLOAD = "md_download_channel"
        const val CHANNEL_COMPLETE = "md_complete_channel"
        private const val BASE_ID = 2000
    }

    init {
        createChannels()
    }

    private fun createChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mgr = context.getSystemService(NotificationManager::class.java)
            mgr.createNotificationChannels(
                listOf(
                    NotificationChannel(
                        CHANNEL_DOWNLOAD,
                        "Active Downloads",
                        NotificationManager.IMPORTANCE_LOW
                    ).apply { description = "Shows progress of active downloads" },
                    NotificationChannel(
                        CHANNEL_COMPLETE,
                        "Completed Downloads",
                        NotificationManager.IMPORTANCE_DEFAULT
                    ).apply { description = "Notifies when a download completes" }
                )
            )
        }
    }

    fun createForegroundInfo(downloadId: String, title: String, progress: Int): ForegroundInfo {
        val id = notifId(downloadId)
        val notification = NotificationCompat.Builder(context, CHANNEL_DOWNLOAD)
            .setContentTitle("MediaDrop – Downloading")
            .setContentText(title)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setProgress(100, progress, progress == 0)
            .setOngoing(true)
            .setSilent(true)
            .build()
        return ForegroundInfo(id, notification)
    }

    fun updateProgress(downloadId: String, title: String, progress: Int, speed: String = "", eta: String = "") {
        val id = notifId(downloadId)
        val subText = buildString {
            if (speed.isNotEmpty()) append(speed)
            if (eta.isNotEmpty()) append(" · $eta remaining")
        }
        val notification = NotificationCompat.Builder(context, CHANNEL_DOWNLOAD)
            .setContentTitle("MediaDrop – Downloading")
            .setContentText(title)
            .setSubText(subText.ifEmpty { null })
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setProgress(100, progress, false)
            .setOngoing(true)
            .setSilent(true)
            .build()
        NotificationManagerCompat.from(context).notify(id, notification)
    }

    fun showCompletionNotification(
        downloadId: String,
        title: String,
        fileSize: Long,
        filePath: String,
        format: String
    ) {
        val openIntent = PendingIntent.getActivity(
            context,
            notifId(downloadId) + 1,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra("navigate_to_downloads", true)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_COMPLETE)
            .setContentTitle("✅ Download Complete")
            .setContentText("$title · ${FileUtils.formatFileSize(fileSize)}")
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentIntent(openIntent)
            .setAutoCancel(true)
            .build()

        cancelNotification(downloadId)
        NotificationManagerCompat.from(context).notify(notifId(downloadId) + 1, notification)
    }

    fun cancelNotification(downloadId: String) {
        NotificationManagerCompat.from(context).cancel(notifId(downloadId))
    }

    private fun notifId(downloadId: String) = (downloadId.hashCode() and Int.MAX_VALUE) + BASE_ID
}
