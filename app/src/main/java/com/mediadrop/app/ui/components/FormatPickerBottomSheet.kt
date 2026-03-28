package com.mediadrop.app.ui.components

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.mediadrop.app.domain.model.AudioFormat
import com.mediadrop.app.domain.model.MediaInfo
import com.mediadrop.app.domain.model.VideoFormat
import com.mediadrop.app.ui.theme.*
import com.mediadrop.app.util.FileUtils

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FormatPickerBottomSheet(
    mediaInfo : MediaInfo,
    onDismiss : () -> Unit,
    /** formatId, extension, quality, hasAudio */
    onDownload: (formatId: String, format: String, quality: String, hasAudio: Boolean) -> Unit
) {
    var selectedVideoFormat by remember { mutableStateOf<VideoFormat?>(mediaInfo.videoFormats.firstOrNull()) }
    var selectedAudioFormat by remember { mutableStateOf<AudioFormat?>(null) }
    var isAudioMode         by remember { mutableStateOf(mediaInfo.videoFormats.isEmpty()) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        shape            = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        containerColor   = MaterialTheme.colorScheme.surface,
        dragHandle       = { BottomSheetDefaults.DragHandle() }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp)
        ) {
            // ── Media header ────────────────────────────────────────────────
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                AsyncImage(
                    model              = mediaInfo.thumbnailUrl,
                    contentDescription = null,
                    contentScale       = ContentScale.Crop,
                    modifier           = Modifier
                        .size(72.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                )
                Spacer(modifier = Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(mediaInfo.title, style = MaterialTheme.typography.titleSmall,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        PlatformBadge(platform = mediaInfo.platform)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(FileUtils.formatDuration(mediaInfo.duration),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // ── Video / Audio toggle ────────────────────────────────────────
            Row(modifier = Modifier.fillMaxWidth()) {
                listOf(false to "Video", true to "Audio Only").forEach { (isAudio, label) ->
                    val icon     = if (isAudio) Icons.Default.AudioFile else Icons.Default.VideoFile
                    val selected = isAudioMode == isAudio
                    FilterChip(
                        selected = selected,
                        onClick  = {
                            isAudioMode = isAudio
                            if (isAudio) selectedVideoFormat = null
                            else         selectedAudioFormat = null
                        },
                        label       = { Text(label) },
                        leadingIcon = { Icon(icon, null, modifier = Modifier.size(16.dp)) },
                        modifier    = Modifier.padding(end = 8.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            if (!isAudioMode && mediaInfo.videoFormats.isNotEmpty()) {
                Text("Resolution", style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface)
                Spacer(modifier = Modifier.height(8.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(mediaInfo.videoFormats) { fmt ->
                        val selected = selectedVideoFormat?.formatId == fmt.formatId
                        VideoFormatChip(
                            fmt      = fmt,
                            selected = selected,
                            onClick  = { selectedVideoFormat = fmt }
                        )
                    }
                }

                // Info badge if selected format is video-only
                val selHasAudio = selectedVideoFormat?.hasAudio ?: true
                if (!selHasAudio) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier          = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(Teal400.copy(alpha = 0.1f))
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        Icon(Icons.Default.MergeType, null, tint = Teal400, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            "Best audio will be merged automatically for full quality",
                            style = MaterialTheme.typography.labelSmall,
                            color = Teal400
                        )
                    }
                }

            } else if (isAudioMode && mediaInfo.audioFormats.isNotEmpty()) {
                Text("Audio Quality", style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface)
                Spacer(modifier = Modifier.height(8.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(mediaInfo.audioFormats) { fmt ->
                        val selected = selectedAudioFormat?.formatId == fmt.formatId
                        FormatChip(
                            label    = fmt.bitrate,
                            sublabel = fmt.extension.uppercase(),
                            fileSize = fmt.fileSize,
                            selected = selected,
                            onClick  = { selectedAudioFormat = fmt }
                        )
                    }
                }
            } else {
                Text("No formats available for this type.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            Spacer(modifier = Modifier.height(24.dp))

            // ── Download button ─────────────────────────────────────────────
            val canDownload = (isAudioMode && selectedAudioFormat != null) ||
                              (!isAudioMode && selectedVideoFormat != null)

            Button(
                onClick = {
                    if (isAudioMode) {
                        selectedAudioFormat?.let {
                            onDownload(it.formatId, it.extension, it.bitrate, true)
                        }
                    } else {
                        selectedVideoFormat?.let {
                            onDownload(it.formatId, it.extension, it.resolution, it.hasAudio)
                        }
                    }
                    onDismiss()
                },
                enabled  = canDownload,
                modifier = Modifier.fillMaxWidth().height(54.dp),
                shape    = RoundedCornerShape(14.dp),
                colors   = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor   = MaterialTheme.colorScheme.onPrimary
                )
            ) {
                Icon(Icons.Default.Download, null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Download", style = MaterialTheme.typography.titleSmall)
            }
        }
    }
}

@Composable
private fun VideoFormatChip(
    fmt     : VideoFormat,
    selected: Boolean,
    onClick : () -> Unit
) {
    val borderColor = if (selected) MaterialTheme.colorScheme.primary
                      else          MaterialTheme.colorScheme.outline

    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .border(1.5.dp, borderColor, RoundedCornerShape(12.dp))
            .background(
                if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                else          MaterialTheme.colorScheme.surfaceVariant
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp)
            .animateContentSize(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(fmt.resolution,
            style = MaterialTheme.typography.titleSmall,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
        Text(fmt.extension.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (!fmt.hasAudio) {
            // Small badge showing audio will be merged
            Text("+ audio", style = MaterialTheme.typography.labelSmall, color = Teal400)
        }
        if (fmt.fileSize != null && fmt.fileSize > 0) {
            Text(FileUtils.formatFileSize(fmt.fileSize),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun FormatChip(
    label   : String,
    sublabel: String,
    fileSize: Long?,
    selected: Boolean,
    onClick : () -> Unit
) {
    val borderColor = if (selected) MaterialTheme.colorScheme.primary
                      else          MaterialTheme.colorScheme.outline

    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .border(1.5.dp, borderColor, RoundedCornerShape(12.dp))
            .background(
                if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                else          MaterialTheme.colorScheme.surfaceVariant
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp)
            .animateContentSize(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(label,
            style = MaterialTheme.typography.titleSmall,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
        Text(sublabel,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (fileSize != null && fileSize > 0) {
            Text(FileUtils.formatFileSize(fileSize),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
