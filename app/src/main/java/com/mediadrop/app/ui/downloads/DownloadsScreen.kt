package com.mediadrop.app.ui.downloads

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mediadrop.app.ui.components.DownloadCard
import com.mediadrop.app.ui.theme.*
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadsScreen(
    viewModel: DownloadsViewModel = hiltViewModel()
) {
    val downloads    by viewModel.filteredDownloads.collectAsStateWithLifecycle()
    val filter       by viewModel.filter.collectAsStateWithLifecycle()
    val liveProgress by viewModel.liveProgress.collectAsStateWithLifecycle()
    val context      = LocalContext.current

    val filters = DownloadFilter.entries

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // ── Filter chips ──────────────────────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            filters.forEach { f ->
                FilterChip(
                    selected = filter == f,
                    onClick  = { viewModel.setFilter(f) },
                    label    = {
                        Text(
                            text = f.name.lowercase().replaceFirstChar { it.uppercase() },
                            style = MaterialTheme.typography.labelMedium
                        )
                    },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = Gold500,
                        selectedLabelColor     = Navy950,
                        containerColor         = Navy800,
                        labelColor             = Grey200
                    ),
                    border = FilterChipDefaults.filterChipBorder(
                        enabled          = true,
                        selected         = filter == f,
                        selectedBorderColor = Gold500,
                        borderColor      = Grey600,
                        borderWidth      = 1.dp,
                        selectedBorderWidth = 1.5.dp
                    )
                )
            }
        }

        // ── Content ───────────────────────────────────────────────────────────
        if (downloads.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
                    Icon(
                        Icons.Default.CloudDownload,
                        contentDescription = null,
                        modifier = Modifier.size(72.dp),
                        tint = Gold500.copy(alpha = 0.3f)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        "No downloads yet",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        "Paste a URL on the Home tab to start",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                }
            }
        } else {
            LazyColumn(
                contentPadding       = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                verticalArrangement  = Arrangement.spacedBy(10.dp),
                modifier             = Modifier.fillMaxSize()
            ) {
                items(downloads, key = { it.id }) { item ->
                    // Merge DB progress with live WorkManager progress
                    val progress = liveProgress[item.id] ?: item.progress

                    DownloadCard(
                        item     = item,
                        progress = progress,
                        onOpen   = { dl ->
                            val file = File(dl.localPath)
                            if (file.exists()) {
                                try {
                                    val uri = FileProvider.getUriForFile(
                                        context,
                                        "${context.packageName}.fileprovider",
                                        file
                                    )
                                    val mime = context.contentResolver.getType(uri)
                                        ?: guessMime(dl.format)
                                    val intent = Intent(Intent.ACTION_VIEW).apply {
                                        setDataAndType(uri, mime)
                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    }
                                    context.startActivity(Intent.createChooser(intent, "Open with"))
                                } catch (e: Exception) { /* file picker fallback */ }
                            }
                        },
                        onShare  = { dl ->
                            val file = File(dl.localPath)
                            if (file.exists()) {
                                try {
                                    val uri = FileProvider.getUriForFile(
                                        context,
                                        "${context.packageName}.fileprovider",
                                        file
                                    )
                                    val intent = Intent(Intent.ACTION_SEND).apply {
                                        type = context.contentResolver.getType(uri) ?: "*/*"
                                        putExtra(Intent.EXTRA_STREAM, uri)
                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    }
                                    context.startActivity(Intent.createChooser(intent, "Share via"))
                                } catch (e: Exception) { }
                            }
                        },
                        onDelete = { dl -> viewModel.deleteDownload(dl.id) }
                    )
                }
                item { Spacer(modifier = Modifier.height(80.dp)) }
            }
        }
    }
}

private fun guessMime(format: String): String = when (format.lowercase()) {
    "mp4", "m4v"        -> "video/mp4"
    "mkv"               -> "video/x-matroska"
    "webm"              -> "video/webm"
    "avi"               -> "video/avi"
    "mov"               -> "video/quicktime"
    "mp3"               -> "audio/mpeg"
    "m4a"               -> "audio/mp4"
    "aac"               -> "audio/aac"
    "opus"              -> "audio/opus"
    "flac"              -> "audio/flac"
    "wav"               -> "audio/wav"
    else                -> "*/*"
}
