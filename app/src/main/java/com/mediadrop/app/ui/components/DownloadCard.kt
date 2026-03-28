package com.mediadrop.app.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.mediadrop.app.data.local.entity.DownloadEntity
import com.mediadrop.app.domain.model.DownloadStatus
import com.mediadrop.app.ui.theme.*

@Composable
fun DownloadCard(
    item    : DownloadEntity,
    progress: Int = item.progress,
    onOpen  : (DownloadEntity) -> Unit,
    onShare : (DownloadEntity) -> Unit,
    onDelete: (DownloadEntity) -> Unit,
    onCancel: (DownloadEntity) -> Unit = {},
    onRetry : (DownloadEntity) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val status      = runCatching { DownloadStatus.valueOf(item.status) }.getOrElse { DownloadStatus.QUEUED }
    val isActive    = status == DownloadStatus.DOWNLOADING || status == DownloadStatus.QUEUED
    val isFailed    = status == DownloadStatus.FAILED
    val isCompleted = status == DownloadStatus.COMPLETED

    val borderColor by animateColorAsState(
        targetValue = when (status) {
            DownloadStatus.COMPLETED   -> Gold500.copy(alpha = 0.4f)
            DownloadStatus.DOWNLOADING -> Teal400.copy(alpha = 0.5f)
            DownloadStatus.QUEUED      -> MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
            DownloadStatus.FAILED      -> Red400.copy(alpha = 0.4f)
            else                       -> MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)
        },
        animationSpec = tween(400),
        label         = "border"
    )

    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable(enabled = isCompleted) { onOpen(item) },
        shape  = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        border = BorderStroke(1.dp, borderColor)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {

            // ── Top row: thumbnail + info ──────────────────────────────────
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.surface),
                    contentAlignment = Alignment.Center
                ) {
                    if (item.thumbnailUrl.isNotBlank()) {
                        AsyncImage(
                            model              = item.thumbnailUrl,
                            contentDescription = null,
                            contentScale       = ContentScale.Crop,
                            modifier           = Modifier.fillMaxSize()
                        )
                    } else {
                        val isAudio = item.format.lowercase() in listOf("mp3","m4a","aac","opus","flac","wav")
                        Icon(
                            imageVector        = if (isAudio) Icons.Default.AudioFile else Icons.Default.VideoFile,
                            contentDescription = null,
                            modifier           = Modifier.size(28.dp),
                            tint               = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                        )
                    }
                    StatusBadge(status = status, modifier = Modifier.align(Alignment.BottomEnd).padding(2.dp))
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text     = item.title,
                        style    = MaterialTheme.typography.titleSmall,
                        color    = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(3.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(4.dp)) {
                            Text(
                                text     = item.format.uppercase(),
                                style    = MaterialTheme.typography.labelSmall,
                                color    = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                        Text(item.quality, style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (item.fileSize > 0) {
                            Text(formatBytes(item.fileSize), style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }

                // Action icons (right side)
                when {
                    isCompleted -> {
                        IconButton(onClick = { onShare(item) }, modifier = Modifier.size(36.dp)) {
                            Icon(Icons.Default.Share, null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                        }
                        IconButton(onClick = { onDelete(item) }, modifier = Modifier.size(36.dp)) {
                            Icon(Icons.Default.DeleteOutline, null,
                                tint = Red400.copy(0.7f), modifier = Modifier.size(18.dp))
                        }
                    }
                    isActive -> {
                        // Cancel button
                        IconButton(onClick = { onCancel(item) }, modifier = Modifier.size(36.dp)) {
                            Icon(Icons.Default.Cancel, null,
                                tint = Red400.copy(0.8f), modifier = Modifier.size(22.dp))
                        }
                    }
                    isFailed -> {
                        // Retry button
                        IconButton(onClick = { onRetry(item) }, modifier = Modifier.size(36.dp)) {
                            Icon(Icons.Default.Refresh, null,
                                tint = Teal400, modifier = Modifier.size(20.dp))
                        }
                        IconButton(onClick = { onDelete(item) }, modifier = Modifier.size(36.dp)) {
                            Icon(Icons.Default.DeleteOutline, null,
                                tint = Red400.copy(0.7f), modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }

            // ── Progress bar (active downloads) ───────────────────────────
            if (isActive) {
                Spacer(modifier = Modifier.height(10.dp))
                Row(
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier              = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text  = if (status == DownloadStatus.QUEUED) "Queued…" else "Downloading…",
                        style = MaterialTheme.typography.labelSmall,
                        color = Teal400
                    )
                    Text("$progress%", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary)
                }
                Spacer(modifier = Modifier.height(4.dp))
                LinearProgressIndicator(
                    progress   = { if (status == DownloadStatus.QUEUED) 0f else progress / 100f },
                    modifier   = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(50)),
                    color      = if (status == DownloadStatus.QUEUED) MaterialTheme.colorScheme.outline else Gold500,
                    trackColor = MaterialTheme.colorScheme.surface,
                    strokeCap  = StrokeCap.Round
                )
            }

            // ── Error label (failed) ──────────────────────────────────────
            if (isFailed) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.ErrorOutline, null, tint = Red400, modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Download failed — tap ↺ to retry",
                        style = MaterialTheme.typography.labelSmall, color = Red400)
                }
            }

            // ── Open file button (completed) ──────────────────────────────
            if (isCompleted) {
                Spacer(modifier = Modifier.height(10.dp))
                Button(
                    onClick  = { onOpen(item) },
                    modifier = Modifier.fillMaxWidth().height(36.dp),
                    shape    = RoundedCornerShape(10.dp),
                    colors   = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                        contentColor   = MaterialTheme.colorScheme.primary
                    )
                ) {
                    Icon(Icons.Default.PlayArrow, null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Open File", style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

@Composable
private fun StatusBadge(status: DownloadStatus, modifier: Modifier = Modifier) {
    val (icon, color) = when (status) {
        DownloadStatus.COMPLETED   -> Icons.Default.CheckCircle   to Green400
        DownloadStatus.FAILED      -> Icons.Default.ErrorOutline  to Red400
        DownloadStatus.DOWNLOADING -> Icons.Default.CloudDownload to Teal400
        else                       -> return
    }
    Surface(
        modifier = modifier.size(16.dp),
        shape    = RoundedCornerShape(50),
        color    = MaterialTheme.colorScheme.background
    ) {
        Icon(icon, null, tint = color, modifier = Modifier.padding(2.dp))
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1_073_741_824 -> "%.1f GB".format(bytes / 1_073_741_824.0)
    bytes >= 1_048_576     -> "%.1f MB".format(bytes / 1_048_576.0)
    bytes >= 1_024         -> "%.0f KB".format(bytes / 1_024.0)
    else                   -> "$bytes B"
}
