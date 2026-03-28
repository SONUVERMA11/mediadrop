package com.mediadrop.app.ui.home

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
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

@OptIn(ExperimentalMaterial3Api::class)
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

    // ── Format picker sheet ────────────────────────────────────────────────
    if (uiState is HomeUiState.MediaReady) {
        val mediaInfo = (uiState as HomeUiState.MediaReady).mediaInfo
        FormatPickerBottomSheet(
            mediaInfo = mediaInfo,
            onDismiss = { viewModel.dismissSheet() },
            onDownload = { formatId, format, quality ->
                viewModel.startDownload(mediaInfo, formatId, format, quality)
                onNavigateToDownloads()
            }
        )
    }

    // ── Playlist picker sheet ──────────────────────────────────────────────
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

    // ── Error dialog ──────────────────────────────────────────────────────
    if (uiState is HomeUiState.Error) {
        ErrorAlertDialog(
            message   = (uiState as HomeUiState.Error).message,
            onDismiss = { viewModel.dismissSheet() },
            onRetry   = if (urlInput.isNotBlank()) viewModel::fetchMedia else null
        )
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
        ) {
            // ── Hero section ─────────────────────────────────────────────────
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            listOf(Navy800, Navy950)
                        )
                    )
                    .padding(top = 52.dp, bottom = 36.dp, start = 24.dp, end = 24.dp)
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    // App logo
                    Text(
                        text  = "DC",
                        style = MaterialTheme.typography.displaySmall.copy(
                            fontWeight = FontWeight.Black,
                            fontSize   = 52.sp,
                            brush      = Brush.linearGradient(listOf(Gold400, Gold600))
                        )
                    )
                    Text(
                        text  = "Download anything. Fast.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Gold500.copy(alpha = 0.7f),
                        modifier = Modifier.padding(bottom = 28.dp)
                    )

                    // ── URL input ───────────────────────────────────────────
                    OutlinedTextField(
                        value       = urlInput,
                        onValueChange = viewModel::onUrlChanged,
                        modifier    = Modifier.fillMaxWidth(),
                        placeholder = {
                            Text(
                                "Paste any video or playlist URL…",
                                style = MaterialTheme.typography.bodyMedium,
                                color = Grey400
                            )
                        },
                        leadingIcon = {
                            Icon(Icons.Default.Link, null, tint = Gold500.copy(alpha = 0.7f))
                        },
                        trailingIcon = {
                            if (urlInput.isNotBlank()) {
                                IconButton(onClick = { viewModel.onUrlChanged("") }) {
                                    Icon(Icons.Default.Clear, null, tint = Grey400)
                                }
                            } else {
                                IconButton(onClick = {
                                    clipboardManager.getText()?.text?.let { viewModel.onUrlChanged(it) }
                                }) {
                                    Icon(Icons.Default.ContentPaste, null, tint = Gold500.copy(alpha = 0.7f))
                                }
                            }
                        },
                        singleLine  = true,
                        shape       = RoundedCornerShape(14.dp),
                        colors      = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor   = Gold500,
                            unfocusedBorderColor = Grey600,
                            focusedTextColor     = White,
                            unfocusedTextColor   = White,
                            cursorColor          = Gold500,
                            focusedContainerColor   = Navy800,
                            unfocusedContainerColor = Navy800
                        ),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Uri,
                            imeAction    = ImeAction.Search
                        ),
                        keyboardActions = KeyboardActions(
                            onSearch = {
                                focusManager.clearFocus()
                                viewModel.fetchMedia()
                            }
                        )
                    )

                    Spacer(modifier = Modifier.height(14.dp))

                    // ── Fetch button ────────────────────────────────────────
                    Button(
                        onClick = {
                            focusManager.clearFocus()
                            viewModel.fetchMedia()
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp),
                        shape  = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Gold500,
                            contentColor   = Navy950,
                            disabledContainerColor = Gold500.copy(alpha = 0.4f)
                        ),
                        enabled = uiState !is HomeUiState.Loading
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
                                        color       = Navy950
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

            // ── Supported platforms row ───────────────────────────────────
            PlatformRow(modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp))

            // ── Recent downloads ──────────────────────────────────────────
            AnimatedVisibility(visible = recentDownloads.isNotEmpty()) {
                Column {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "Recent",
                            style = MaterialTheme.typography.titleSmall,
                            color = Gold400
                        )
                        TextButton(onClick = onNavigateToDownloads) {
                            Text("See all", color = Gold500, style = MaterialTheme.typography.labelMedium)
                        }
                    }
                    RecentDownloadsCarousel(
                        items       = recentDownloads,
                        onItemClick = { onNavigateToDownloads() },
                        modifier    = Modifier.padding(bottom = 16.dp)
                    )
                }
            }

            // Credit at bottom
            Spacer(modifier = Modifier.height(24.dp))
            Text(
                "Made with ❤️ by SONU VERMA",
                style    = MaterialTheme.typography.labelSmall,
                color    = Grey600,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(80.dp))
        }
    }
}

@Composable
private fun PlatformRow(modifier: Modifier = Modifier) {
    val platforms = listOf("YT","IG","TK","FB","X","TW","SC","VM","RM","PIN","DM","LI")
    Column(modifier = modifier) {
        Text(
            "12 platforms supported",
            style = MaterialTheme.typography.labelSmall,
            color = Grey400,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            platforms.take(8).forEach { p ->
                Surface(
                    color = Navy800,
                    shape = RoundedCornerShape(6.dp),
                    border = BorderStroke(1.dp, GlassBorder)
                ) {
                    Text(
                        p,
                        style    = MaterialTheme.typography.labelSmall,
                        color    = Gold400,
                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp)
                    )
                }
            }
            Surface(
                color = Navy800,
                shape = RoundedCornerShape(6.dp)
            ) {
                Text(
                    "+${platforms.size - 8}",
                    style    = MaterialTheme.typography.labelSmall,
                    color    = Grey400,
                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp)
                )
            }
        }
    }
}
