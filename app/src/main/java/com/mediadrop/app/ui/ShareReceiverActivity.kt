package com.mediadrop.app.ui

import android.content.Intent
import android.os.Bundle
import android.webkit.URLUtil
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.mediadrop.app.domain.model.DownloadStatus
import com.mediadrop.app.ui.components.FormatPickerBottomSheet
import com.mediadrop.app.ui.home.HomeUiState
import com.mediadrop.app.ui.home.HomeViewModel
import com.mediadrop.app.ui.theme.MediaDropTheme
import dagger.hilt.android.AndroidEntryPoint

/**
 * Lightweight transparent overlay activity.
 * Catches ACTION_SEND / ACTION_VIEW intents from other apps (YouTube, TikTok, etc.),
 * fetches media info in the background, and immediately shows a format picker
 * bottom sheet — without launching the full app UI.
 */
@AndroidEntryPoint
class ShareReceiverActivity : ComponentActivity() {

    private val viewModel: HomeViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Extract the shared URL from SEND or VIEW intent
        val url: String? = when (intent?.action) {
            Intent.ACTION_SEND -> {
                val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: ""
                text.split("\\s+".toRegex())
                    .firstOrNull { URLUtil.isValidUrl(it) }
                    ?: if (URLUtil.isValidUrl(text)) text else null
            }
            Intent.ACTION_VIEW -> intent?.data?.toString()?.takeIf { URLUtil.isValidUrl(it) }
            else               -> null
        }

        if (url.isNullOrBlank()) {
            // Nothing useful shared — just dismiss
            finish()
            return
        }

        // Start fetching immediately (before UI is even drawn — hides latency)
        viewModel.setUrlFromShare(url)

        setContent {
            MediaDropTheme {
                SharePopupContent(
                    viewModel = viewModel,
                    url       = url,
                    onDismiss = { finish() }
                )
            }
        }
    }
}

@Composable
private fun SharePopupContent(
    viewModel : HomeViewModel,
    url       : String,
    onDismiss : () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()

    when (val state = uiState) {
        // ── Loading — show a compact loading card ──────────────────────────
        is HomeUiState.Loading -> {
            ShareLoadingCard(url = url, onDismiss = onDismiss)
        }

        // ── Media ready — show the full format picker bottom sheet ──────────
        is HomeUiState.MediaReady -> {
            FormatPickerBottomSheet(
                mediaInfo  = state.mediaInfo,
                onDismiss  = {
                    viewModel.dismissSheet()
                    onDismiss()
                },
                onDownload = { formatId, format, quality, hasAudio ->
                    viewModel.startDownload(state.mediaInfo, formatId, format, quality, hasAudio)
                    onDismiss()
                }
            )
        }

        // ── Error ───────────────────────────────────────────────────────────
        is HomeUiState.Error -> {
            ShareErrorCard(message = state.message, onDismiss = onDismiss)
        }

        else -> {
            // Idle (shouldn't normally reach here)
            ShareLoadingCard(url = url, onDismiss = onDismiss)
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Loading card — shown while media info is being fetched
// ─────────────────────────────────────────────────────────────────────────────
@Composable
private fun ShareLoadingCard(url: String, onDismiss: () -> Unit) {
    Dialog(
        onDismissRequest = onDismiss,
        properties       = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp),
            contentAlignment = Alignment.Center
        ) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape    = RoundedCornerShape(20.dp),
                color    = MaterialTheme.colorScheme.surface,
                tonalElevation = 8.dp
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.CloudDownload,
                                contentDescription = null,
                                tint     = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(22.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                "DC Downloader",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Default.Close, null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp))
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Text(
                        text     = url,
                        style    = MaterialTheme.typography.bodySmall,
                        color    = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(20.dp))

                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(4.dp)
                            .clip(RoundedCornerShape(50)),
                        color      = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    Text(
                        "Fetching available formats…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Error card
// ─────────────────────────────────────────────────────────────────────────────
@Composable
private fun ShareErrorCard(message: String, onDismiss: () -> Unit) {
    Dialog(
        onDismissRequest = onDismiss,
        properties       = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp),
            contentAlignment = Alignment.Center
        ) {
            Surface(
                modifier       = Modifier.fillMaxWidth(),
                shape          = RoundedCornerShape(20.dp),
                color          = MaterialTheme.colorScheme.surface,
                tonalElevation = 8.dp
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        Icons.Default.ErrorOutline,
                        contentDescription = null,
                        tint     = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(48.dp)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        "Could not load media",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(20.dp))
                    Button(
                        onClick  = onDismiss,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Close")
                    }
                }
            }
        }
    }
}
