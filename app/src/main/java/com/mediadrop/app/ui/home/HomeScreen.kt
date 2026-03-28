package com.mediadrop.app.ui.home

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mediadrop.app.ui.components.ErrorAlertDialog
import com.mediadrop.app.ui.components.FormatPickerBottomSheet
import com.mediadrop.app.ui.components.RecentDownloadsCarousel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    viewModel: HomeViewModel = hiltViewModel(),
    onNavigateToDownloads: () -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val urlInput by viewModel.urlInput.collectAsStateWithLifecycle()
    val recentDownloads by viewModel.recentDownloads.collectAsStateWithLifecycle()
    val clipboardManager: ClipboardManager = LocalClipboardManager.current
    val focusManager = LocalFocusManager.current

    // Show format picker when media is ready
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

    // Error dialog
    if (uiState is HomeUiState.Error) {
        ErrorAlertDialog(
            message = (uiState as HomeUiState.Error).message,
            onDismiss = { viewModel.dismissSheet() },
            onRetry = if (urlInput.isNotBlank()) viewModel::fetchMedia else null
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        // Hero gradient header
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                            MaterialTheme.colorScheme.background
                        )
                    )
                )
                .padding(top = 48.dp, bottom = 32.dp, start = 24.dp, end = 24.dp)
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "MediaDrop",
                    style = MaterialTheme.typography.displaySmall,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = "Download videos & audio from anywhere",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(28.dp))

                // URL input field
                OutlinedTextField(
                    value = urlInput,
                    onValueChange = viewModel::onUrlChanged,
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = {
                        Text(
                            text = "Paste a YouTube, TikTok, Instagram… URL",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    },
                    leadingIcon = {
                        Icon(Icons.Default.Link, contentDescription = null)
                    },
                    trailingIcon = {
                        IconButton(onClick = {
                            val text = clipboardManager.getText()?.text
                            if (text != null) viewModel.onUrlChanged(text)
                        }) {
                            Icon(Icons.Default.ContentPaste, contentDescription = "Paste")
                        }
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Uri,
                        imeAction = ImeAction.Search
                    ),
                    keyboardActions = KeyboardActions(
                        onSearch = {
                            focusManager.clearFocus()
                            viewModel.fetchMedia()
                        }
                    )
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Fetch button
                Button(
                    onClick = {
                        focusManager.clearFocus()
                        viewModel.fetchMedia()
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(54.dp),
                    shape = RoundedCornerShape(14.dp),
                    enabled = uiState !is HomeUiState.Loading
                ) {
                    AnimatedContent(
                        targetState = uiState is HomeUiState.Loading,
                        label = "btn_loading"
                    ) { loading ->
                        if (loading) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.onPrimary
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Text("Fetching media info…")
                            }
                        } else {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Fetch Media", style = MaterialTheme.typography.titleSmall)
                            }
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Recent downloads carousel
        AnimatedVisibility(visible = recentDownloads.isNotEmpty()) {
            RecentDownloadsCarousel(
                items = recentDownloads,
                onItemClick = { onNavigateToDownloads() },
                modifier = Modifier.padding(bottom = 24.dp)
            )
        }
    }
}
