package com.mediadrop.app.ui.home

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mediadrop.app.ui.components.ErrorAlertDialog
import com.mediadrop.app.ui.components.FormatPickerBottomSheet
import com.mediadrop.app.ui.components.PlaylistBottomSheet
import com.mediadrop.app.ui.components.RecentDownloadsCarousel
import com.mediadrop.app.ui.theme.*

// ── Platform data ─────────────────────────────────────────────────────────────
private data class PlatformInfo(
    val name   : String,
    val label  : String,
    val emoji  : String,
    val bgHex  : Long,    // background color
    val textHex: Long
)

private val PLATFORMS = listOf(
    PlatformInfo("YouTube",     "YT",  "▶",  0xFFFF0000, 0xFFFFFFFF),
    PlatformInfo("Instagram",   "IG",  "📸", 0xFFE1306C, 0xFFFFFFFF),
    PlatformInfo("TikTok",      "TK",  "🎵", 0xFF010101, 0xFFFFFFFF),
    PlatformInfo("Facebook",    "FB",  "👥", 0xFF1877F2, 0xFFFFFFFF),
    PlatformInfo("Twitter / X", "X",   "𝕏",  0xFF000000, 0xFFFFFFFF),
    PlatformInfo("Twitch",      "TW",  "🎮", 0xFF9146FF, 0xFFFFFFFF),
    PlatformInfo("SoundCloud",  "SC",  "🔊", 0xFFFF5500, 0xFFFFFFFF),
    PlatformInfo("Vimeo",       "VM",  "🎬", 0xFF1AB7EA, 0xFFFFFFFF),
    PlatformInfo("Reddit",      "RD",  "🔴", 0xFFFF4500, 0xFFFFFFFF),
    PlatformInfo("Pinterest",   "PIN", "📌", 0xFFE60023, 0xFFFFFFFF),
    PlatformInfo("Dailymotion", "DM",  "▶", 0xFF0066DC, 0xFFFFFFFF),
    PlatformInfo("LinkedIn",    "LI",  "💼", 0xFF0A66C2, 0xFFFFFFFF),
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun HomeScreen(
    viewModel: HomeViewModel = hiltViewModel(),
    onNavigateToDownloads: () -> Unit = {}
) {
    val uiState         by viewModel.uiState.collectAsStateWithLifecycle()
    val urlInput        by viewModel.urlInput.collectAsStateWithLifecycle()
    val recentDownloads by viewModel.recentDownloads.collectAsStateWithLifecycle()
    val clipboardManager = LocalClipboardManager.current
    val focusManager     = LocalFocusManager.current

    // ── Format picker sheet ──────────────────────────────────────────────────
    if (uiState is HomeUiState.MediaReady) {
        val mediaInfo = (uiState as HomeUiState.MediaReady).mediaInfo
        FormatPickerBottomSheet(
            mediaInfo = mediaInfo,
            onDismiss = { viewModel.dismissSheet() },
            onDownload = { formatId, format, quality, hasAudio ->
                viewModel.startDownload(mediaInfo, formatId, format, quality, hasAudio)
                onNavigateToDownloads()
            }
        )
    }

    // ── Playlist picker sheet ────────────────────────────────────────────────
    if (uiState is HomeUiState.PlaylistReady) {
        val playlist = (uiState as HomeUiState.PlaylistReady).playlistInfo
        PlaylistBottomSheet(
            playlistInfo = playlist,
            onDismiss    = { viewModel.dismissSheet() },
            onDownload   = { selectedIds, formatId, format, quality ->
                viewModel.startBatchDownload(playlist, selectedIds, formatId, format, quality)
                onNavigateToDownloads()
            }
        )
    }

    // ── Error dialog ─────────────────────────────────────────────────────────
    if (uiState is HomeUiState.Error) {
        ErrorAlertDialog(
            message   = (uiState as HomeUiState.Error).message,
            onDismiss = { viewModel.dismissSheet() },
            onRetry   = if (urlInput.isNotBlank()) viewModel::fetchMedia else null
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
    ) {
        // ── Hero section ──────────────────────────────────────────────────────
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        listOf(
                            MaterialTheme.colorScheme.surfaceVariant,
                            MaterialTheme.colorScheme.background
                        )
                    )
                )
                .padding(top = 52.dp, bottom = 32.dp, start = 24.dp, end = 24.dp)
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth()
            ) {
                // Logo
                Text(
                    text  = "DC",
                    style = MaterialTheme.typography.displaySmall.copy(
                        fontWeight = FontWeight.Black,
                        fontSize   = 56.sp,
                        brush      = Brush.linearGradient(listOf(Gold400, Gold600))
                    )
                )
                Text(
                    text     = "Download anything. Fast.",
                    style    = MaterialTheme.typography.bodySmall,
                    color    = MaterialTheme.colorScheme.primary.copy(alpha = 0.75f),
                    modifier = Modifier.padding(bottom = 24.dp)
                )

                // URL input
                OutlinedTextField(
                    value         = urlInput,
                    onValueChange = viewModel::onUrlChanged,
                    modifier      = Modifier.fillMaxWidth(),
                    placeholder   = {
                        Text("Paste any video or playlist URL…",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    },
                    leadingIcon   = {
                        Icon(Icons.Default.Link, null, tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f))
                    },
                    trailingIcon  = {
                        if (urlInput.isNotBlank()) {
                            IconButton(onClick = { viewModel.onUrlChanged("") }) {
                                Icon(Icons.Default.Clear, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        } else {
                            IconButton(onClick = {
                                clipboardManager.getText()?.text?.let { viewModel.onUrlChanged(it) }
                            }) {
                                Icon(Icons.Default.ContentPaste, null,
                                    tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f))
                            }
                        }
                    },
                    singleLine    = true,
                    shape         = RoundedCornerShape(14.dp),
                    colors        = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor      = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor    = MaterialTheme.colorScheme.outline,
                        focusedTextColor        = MaterialTheme.colorScheme.onSurface,
                        unfocusedTextColor      = MaterialTheme.colorScheme.onSurface,
                        cursorColor             = MaterialTheme.colorScheme.primary,
                        focusedContainerColor   = MaterialTheme.colorScheme.surface,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surface
                    ),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Uri,
                        imeAction    = ImeAction.Search
                    ),
                    keyboardActions = KeyboardActions(
                        onSearch = { focusManager.clearFocus(); viewModel.fetchMedia() }
                    )
                )

                Spacer(modifier = Modifier.height(12.dp))

                // Fetch button
                Button(
                    onClick  = { focusManager.clearFocus(); viewModel.fetchMedia() },
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape    = RoundedCornerShape(14.dp),
                    colors   = ButtonDefaults.buttonColors(
                        containerColor        = MaterialTheme.colorScheme.primary,
                        contentColor          = MaterialTheme.colorScheme.onPrimary,
                        disabledContainerColor= MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)
                    ),
                    enabled  = uiState !is HomeUiState.Loading
                ) {
                    AnimatedContent(
                        targetState = uiState is HomeUiState.Loading,
                        label       = "fetch_btn"
                    ) { loading ->
                        if (loading) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(
                                    modifier    = Modifier.size(18.dp),
                                    strokeWidth = 2.dp,
                                    color       = MaterialTheme.colorScheme.onPrimary
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Text("Fetching…", fontWeight = FontWeight.Bold)
                            }
                        } else {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Search, null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Fetch & Download", fontWeight = FontWeight.Bold,
                                    style = MaterialTheme.typography.titleSmall)
                            }
                        }
                    }
                }
            }
        }

        // ── ALL Supported Platforms section ───────────────────────────────────
        Column(modifier = Modifier.padding(horizontal = 20.dp)) {
            Row(
                verticalAlignment     = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier              = Modifier.fillMaxWidth().padding(bottom = 12.dp)
            ) {
                Text(
                    "Supported Platforms",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    "${PLATFORMS.size} platforms",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Two-column platform grid
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement   = Arrangement.spacedBy(10.dp),
                maxItemsInEachRow     = 4,
                modifier              = Modifier.fillMaxWidth()
            ) {
                PLATFORMS.forEach { platform ->
                    PlatformChip(platform)
                }
            }
        }

        // ── Recent downloads ──────────────────────────────────────────────────
        AnimatedVisibility(
            visible = recentDownloads.isNotEmpty(),
            modifier= Modifier.padding(top = 16.dp)
        ) {
            Column {
                Row(
                    modifier              = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment     = Alignment.CenterVertically
                ) {
                    Text("Recent", style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary)
                    TextButton(onClick = onNavigateToDownloads) {
                        Text("See all", color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.labelMedium)
                    }
                }
                RecentDownloadsCarousel(
                    items       = recentDownloads,
                    onItemClick = { onNavigateToDownloads() },
                    modifier    = Modifier.padding(bottom = 8.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
        Text(
            "Made with ❤️ by SONU VERMA",
            style     = MaterialTheme.typography.labelSmall,
            color     = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            modifier  = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(80.dp))
    }
}

@Composable
private fun PlatformChip(platform: PlatformInfo) {
    val bgColor   = androidx.compose.ui.graphics.Color(platform.bgHex)
    val textColor = androidx.compose.ui.graphics.Color(platform.textHex)

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier            = Modifier.width(72.dp)
    ) {
        // Circle icon
        Box(
            modifier = Modifier
                .size(52.dp)
                .clip(CircleShape)
                .background(bgColor),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text      = platform.emoji,
                fontSize  = 22.sp,
                textAlign = TextAlign.Center
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text      = platform.name,
            style     = MaterialTheme.typography.labelSmall,
            color     = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            maxLines  = 1,
            modifier  = Modifier.fillMaxWidth()
        )
    }
}
