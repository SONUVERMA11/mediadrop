package com.mediadrop.app.ui.components

import androidx.compose.animation.*
import androidx.compose.foundation.*
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.mediadrop.app.domain.model.PlaylistEntry
import com.mediadrop.app.domain.model.PlaylistInfo
import com.mediadrop.app.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistBottomSheet(
    playlistInfo: PlaylistInfo,
    onDismiss: () -> Unit,
    onDownload: (selectedIds: Set<String>, formatId: String, format: String, quality: String) -> Unit
) {
    var selectedIds  by remember { mutableStateOf(playlistInfo.entries.map { it.id }.toSet()) }
    var audioOnly    by remember { mutableStateOf(false) }
    val sheetState   = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest   = onDismiss,
        sheetState         = sheetState,
        containerColor     = Navy900,
        contentColor       = MaterialTheme.colorScheme.onSurface,
        dragHandle         = {
            Box(
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 4.dp),
                contentAlignment = Alignment.Center
            ) {
                Box(modifier = Modifier.size(40.dp, 4.dp).clip(RoundedCornerShape(50))
                    .background(Grey600))
            }
        }
    ) {
        Column(modifier = Modifier.fillMaxHeight(0.9f)) {
            // ── Header ────────────────────────────────────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Playlist", style = MaterialTheme.typography.labelSmall, color = Gold400)
                    Text(
                        playlistInfo.title,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 2, overflow = TextOverflow.Ellipsis, color = White
                    )
                    Text(
                        "${playlistInfo.entries.size} videos • ${selectedIds.size} selected",
                        style = MaterialTheme.typography.labelSmall, color = Grey400
                    )
                }
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, null, tint = Grey400)
                }
            }

            HorizontalDivider(color = GlassBorder)

            // ── Controls row ─────────────────────────────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Select / deselect all
                Row {
                    TextButton(
                        onClick = { selectedIds = playlistInfo.entries.map { it.id }.toSet() }
                    ) { Text("All", color = Gold400, style = MaterialTheme.typography.labelMedium) }
                    TextButton(
                        onClick = { selectedIds = emptySet() }
                    ) { Text("None", color = Grey400, style = MaterialTheme.typography.labelMedium) }
                }

                // Audio only toggle
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Audio only", style = MaterialTheme.typography.labelSmall, color = Grey400)
                    Spacer(modifier = Modifier.width(6.dp))
                    Switch(
                        checked = audioOnly,
                        onCheckedChange = { audioOnly = it },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor  = Navy950,
                            checkedTrackColor  = Gold500
                        )
                    )
                }
            }

            // ── Video list ───────────────────────────────────────────────────
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(playlistInfo.entries, key = { it.id }) { entry ->
                    PlaylistEntryRow(
                        entry      = entry,
                        selected   = entry.id in selectedIds,
                        onToggle   = {
                            selectedIds = if (entry.id in selectedIds)
                                selectedIds - entry.id else selectedIds + entry.id
                        }
                    )
                }
            }

            // ── Download button ──────────────────────────────────────────────
            Surface(color = Navy900) {
                Button(
                    onClick = {
                        if (selectedIds.isNotEmpty()) {
                            val format  = if (audioOnly) "mp3" else "mp4"
                            val quality = if (audioOnly) "best" else "720p"
                            onDownload(selectedIds, "bestaudio" , format, quality)
                        }
                    },
                    enabled = selectedIds.isNotEmpty(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                        .height(52.dp),
                    shape  = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Gold500,
                        contentColor   = Navy950,
                        disabledContainerColor = Gold500.copy(alpha = 0.3f)
                    )
                ) {
                    Icon(Icons.Default.CloudDownload, null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        "Download ${selectedIds.size} ${if (selectedIds.size == 1) "video" else "videos"}",
                        style = MaterialTheme.typography.titleSmall
                    )
                }
            }
        }
    }
}

@Composable
private fun PlaylistEntryRow(
    entry: PlaylistEntry,
    selected: Boolean,
    onToggle: () -> Unit
) {
    val bg by animateColorAsState(
        if (selected) Gold500.copy(alpha = 0.08f) else Navy800,
        label = "entry_bg"
    )

    Card(
        modifier = Modifier.fillMaxWidth().clickable { onToggle() },
        colors   = CardDefaults.cardColors(containerColor = bg),
        shape    = RoundedCornerShape(12.dp),
        border   = if (selected) BorderStroke(1.dp, Gold500.copy(alpha = 0.4f)) else null
    ) {
        Row(
            modifier = Modifier.padding(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Checkbox
            Checkbox(
                checked = selected,
                onCheckedChange = { onToggle() },
                colors = CheckboxDefaults.colors(
                    checkedColor   = Gold500,
                    checkmarkColor = Navy950
                )
            )

            // Thumbnail
            Box(
                modifier = Modifier.size(64.dp, 40.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Navy700)
            ) {
                if (entry.thumbnailUrl.isNotBlank()) {
                    AsyncImage(
                        model        = entry.thumbnailUrl,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier     = Modifier.fillMaxSize()
                    )
                }
                // Duration overlay
                if (entry.duration > 0) {
                    Text(
                        formatDuration(entry.duration),
                        style    = MaterialTheme.typography.labelSmall.copy(),
                        color    = White,
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .background(Navy950.copy(alpha = 0.7f))
                            .padding(horizontal = 3.dp, vertical = 1.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.width(10.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    entry.title,
                    style    = MaterialTheme.typography.bodySmall,
                    color    = if (selected) White else Grey200,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                if (entry.uploader.isNotBlank()) {
                    Text(
                        entry.uploader,
                        style = MaterialTheme.typography.labelSmall,
                        color = Grey400
                    )
                }
            }
        }
    }
}

private fun formatDuration(seconds: Long): String {
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s)
    else "%d:%02d".format(m, s)
}
