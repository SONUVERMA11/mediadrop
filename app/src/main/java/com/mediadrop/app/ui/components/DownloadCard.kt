package com.mediadrop.app.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.mediadrop.app.data.local.entity.DownloadEntity
import com.mediadrop.app.domain.model.DownloadStatus
import com.mediadrop.app.ui.theme.*

@Composable
fun DownloadCard(
    item: DownloadEntity,
    progress: Int = item.progress,
    onOpen: (DownloadEntity) -> Unit,
    onShare: (DownloadEntity) -> Unit,
    onDelete: (DownloadEntity) -> Unit,
    modifier: Modifier = Modifier
) {
    val status = runCatching { DownloadStatus.valueOf(item.status) }.getOrNull()
        ?: DownloadStatus.PENDING
    val isDownloading = status == DownloadStatus.DOWNLOADING

    val borderColor by animateColorAsState(
        targetValue = when (status) {
            DownloadStatus.COMPLETED   -> Gold500.copy(alpha = 0.4f)
            DownloadStatus.DOWNLOADING -> Teal400.copy(alpha = 0.5f)
            DownloadStatus.FAILED      -> Red400.copy(alpha = 0.4f)
            else                       -> Grey600.copy(alpha = 0.3f)
        },
        animationSpec = tween(400),
        label = "border"
    )

    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable(enabled = status == DownloadStatus.COMPLETED) { onOpen(item) },
        shape  = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Navy800),
        border = BorderStroke(1.dp, borderColor)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Thumbnail
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Navy700)
                ) {
                    if (item.thumbnailUrl.isNotBlank()) {
                        AsyncImage(
                            model = item.thumbnailUrl,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        Icon(
                            if (item.format in listOf("mp3","m4a","aac","opus","flac","wav"))
                                Icons.Default.AudioFile else Icons.Default.VideoFile,
                            contentDescription = null,
                            modifier = Modifier.align(Alignment.Center).size(28.dp),
                            tint = Gold500.copy(alpha = 0.5f)
                        )
                    }
                    // Status badge overlay
                    StatusBadge(
                        status   = status,
                        modifier = Modifier.align(Alignment.BottomEnd).padding(2.dp)
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text     = item.title,
                        style    = MaterialTheme.typography.titleSmall,
                        color    = White,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Format chip
                        Surface(
                            color  = Navy700,
                            shape  = RoundedCornerShape(4.dp)
                        ) {
                            Text(
                                text   = item.format.uppercase(),
                                style  = MaterialTheme.typography.labelSmall,
                                color  = Gold400,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                        // Quality
                        Text(
                            text  = item.quality,
                            style = MaterialTheme.typography.labelSmall,
                            color = Grey400
                        )
                        // File size
                        if (item.fileSize > 0) {
                            Text(
                                text  = formatBytes(item.fileSize),
                                style = MaterialTheme.typography.labelSmall,
                                color = Grey400
                            )
                        }
                    }
                }

                // Action buttons — only show when complete
                if (status == DownloadStatus.COMPLETED) {
                    Row {
                        IconButton(onClick = { onShare(item) }, modifier = Modifier.size(36.dp)) {
                            Icon(Icons.Default.Share, null, tint = Grey400, modifier = Modifier.size(18.dp))
                        }
                        IconButton(onClick = { onDelete(item) }, modifier = Modifier.size(36.dp)) {
                            Icon(Icons.Default.DeleteOutline, null, tint = Red400.copy(alpha = 0.7f), modifier = Modifier.size(18.dp))
                        }
                    }
                } else if (status == DownloadStatus.FAILED) {
                    IconButton(onClick = { onDelete(item) }, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.Default.DeleteOutline, null, tint = Red400.copy(alpha = 0.7f), modifier = Modifier.size(18.dp))
                    }
                }
            }

            // ── Progress bar (visible during download) ────────────────────────
            if (isDownloading) {
                Spacer(modifier = Modifier.height(10.dp))
                Column {
                    Row(
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            "Downloading…",
                            style = MaterialTheme.typography.labelSmall,
                            color = Teal400
                        )
                        Text(
                            "$progress%",
                            style = MaterialTheme.typography.labelSmall,
                            color = Gold400
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    LinearProgressIndicator(
                        progress            = { progress / 100f },
                        modifier            = Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(50)),
                        color               = Gold500,
                        trackColor          = Navy700,
                        strokeCap           = androidx.compose.ui.graphics.StrokeCap.Round
                    )
                }
            }

            // ── Open button for completed downloads ──────────────────────────
            if (status == DownloadStatus.COMPLETED) {
                Spacer(modifier = Modifier.height(10.dp))
                Button(
                    onClick = { onOpen(item) },
                    modifier = Modifier.fillMaxWidth().height(36.dp),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Gold500.copy(alpha = 0.15f),
                        contentColor   = Gold400
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
        DownloadStatus.COMPLETED   -> Icons.Default.CheckCircle to Green400
        DownloadStatus.FAILED      -> Icons.Default.ErrorOutline to Red400
        DownloadStatus.DOWNLOADING -> Icons.Default.CloudDownload to Teal400
        else                       -> return
    }
    Surface(
        modifier = modifier.size(16.dp),
        shape    = RoundedCornerShape(50),
        color    = Navy950
    ) {
        Icon(icon, null, tint = color, modifier = Modifier.padding(2.dp))
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1_073_741_824 -> String.format("%.1f GB", bytes / 1_073_741_824.0)
    bytes >= 1_048_576     -> String.format("%.1f MB", bytes / 1_048_576.0)
    bytes >= 1_024         -> String.format("%.0f KB", bytes / 1_024.0)
    else                   -> "$bytes B"
}
