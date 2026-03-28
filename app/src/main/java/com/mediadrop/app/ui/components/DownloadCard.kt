package com.mediadrop.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.mediadrop.app.data.local.entity.DownloadEntity
import com.mediadrop.app.domain.model.DownloadStatus
import com.mediadrop.app.util.FileUtils

@Composable
fun DownloadCard(
    item: DownloadEntity,
    onOpen: (DownloadEntity) -> Unit,
    onShare: (DownloadEntity) -> Unit,
    onDelete: (DownloadEntity) -> Unit,
    modifier: Modifier = Modifier
) {
    val status = runCatching { DownloadStatus.valueOf(item.status) }.getOrDefault(DownloadStatus.FAILED)

    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable { if (status == DownloadStatus.COMPLETED) onOpen(item) },
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Thumbnail
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surface)
            ) {
                AsyncImage(
                    model = item.thumbnailUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
                // Format badge overlay
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(3.dp)
                        .background(
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.85f),
                            RoundedCornerShape(4.dp)
                        )
                        .padding(horizontal = 4.dp, vertical = 1.dp)
                ) {
                    Text(
                        text = item.format.uppercase(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PlatformBadge(
                        platform = runCatching {
                            com.mediadrop.app.domain.model.SupportedPlatform.valueOf(item.platform)
                        }.getOrDefault(com.mediadrop.app.domain.model.SupportedPlatform.GENERIC)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = item.quality,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (item.fileSize > 0) {
                        Text(
                            text = " · ${FileUtils.formatFileSize(item.fileSize)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))

                when (status) {
                    DownloadStatus.DOWNLOADING -> {
                        AnimatedProgressBar(
                            progress = item.progress,
                            showLabel = false,
                            modifier = Modifier.fillMaxWidth()
                        )
                        if (item.speed.isNotEmpty()) {
                            Text(
                                text = "${item.speed} · ${item.eta}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    DownloadStatus.COMPLETED -> {
                        Text(
                            text = "✓ Completed",
                            style = MaterialTheme.typography.labelSmall,
                            color = androidx.compose.ui.graphics.Color(0xFF30D158)
                        )
                    }
                    DownloadStatus.FAILED -> {
                        Text(
                            text = "✗ Failed",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    DownloadStatus.QUEUED -> {
                        Text(
                            text = "Queued",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    DownloadStatus.PAUSED -> {
                        Text(
                            text = "Paused",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.secondary
                        )
                    }
                }
            }

            // Actions
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                if (status == DownloadStatus.COMPLETED) {
                    IconButton(onClick = { onShare(item) }, modifier = Modifier.size(36.dp)) {
                        Icon(
                            Icons.Default.Share,
                            contentDescription = "Share",
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                IconButton(onClick = { onDelete(item) }, modifier = Modifier.size(36.dp)) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = "Delete",
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.error.copy(alpha = 0.7f)
                    )
                }
            }
        }
    }
}
