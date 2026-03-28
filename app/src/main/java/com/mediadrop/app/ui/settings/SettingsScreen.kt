package com.mediadrop.app.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = "Settings",
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(bottom = 8.dp)
        )

        // ── Downloads ──────────────────────────────────────────────────────────
        SettingsSectionHeader("Downloads")

        // Default video quality
        SettingsDropdown(
            label = "Default Video Quality",
            icon = Icons.Default.Hd,
            options = listOf("144p", "240p", "360p", "480p", "720p", "1080p", "1440p", "2160p"),
            selected = settings.defaultVideoQuality,
            onSelect = viewModel::setVideoQuality
        )

        // Default audio format
        SettingsDropdown(
            label = "Default Audio Format",
            icon = Icons.Default.AudioFile,
            options = listOf("mp3", "m4a", "aac", "opus", "flac", "wav"),
            selected = settings.defaultAudioFormat,
            onSelect = viewModel::setAudioFormat
        )

        // Max concurrent
        SettingsSlider(
            label = "Max Concurrent Downloads",
            icon = Icons.Default.Download,
            value = settings.maxConcurrentDownloads,
            range = 1..5,
            onValueChange = viewModel::setMaxConcurrent
        )

        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

        // ── Storage ───────────────────────────────────────────────────────────
        SettingsSectionHeader("Storage")

        SettingsDropdown(
            label = "Auto-clear History",
            icon = Icons.Default.CleaningServices,
            options = listOf("Never", "After 7 days", "After 30 days"),
            selected = when (settings.autoClearDays) {
                7 -> "After 7 days"
                30 -> "After 30 days"
                else -> "Never"
            },
            onSelect = { label ->
                viewModel.setAutoClearDays(
                    when (label) {
                        "After 7 days" -> 7
                        "After 30 days" -> 30
                        else -> 0
                    }
                )
            }
        )

        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

        // ── Appearance ────────────────────────────────────────────────────────
        SettingsSectionHeader("Appearance")

        SettingsDropdown(
            label = "Theme",
            icon = Icons.Default.DarkMode,
            options = listOf("light", "dark", "system"),
            selected = settings.darkMode,
            onSelect = viewModel::setDarkMode
        )

        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

        // ── Notifications ──────────────────────────────────────────────────────
        SettingsSectionHeader("Notifications")

        SettingsToggle(
            label = "Download Notifications",
            icon = Icons.Default.Notifications,
            checked = settings.notificationsEnabled,
            onCheckedChange = viewModel::setNotifications
        )

        Spacer(modifier = Modifier.height(24.dp))

        // App version
        Text(
            text = "MediaDrop v1.0.0",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.align(Alignment.CenterHorizontally)
        )
    }
}

// ── Reusable setting row components ─────────────────────────────────────────

@Composable
private fun SettingsSectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 4.dp, bottom = 2.dp)
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsDropdown(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    options: List<String>,
    selected: String,
    onSelect: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp)
    ) {
        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { expanded = !expanded }
        ) {
            ListItem(
                modifier = Modifier.menuAnchor(),
                headlineContent = { Text(label, style = MaterialTheme.typography.bodyMedium) },
                supportingContent = {
                    Text(selected, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary)
                },
                leadingContent = { Icon(icon, contentDescription = null, modifier = Modifier.size(22.dp)) },
                trailingContent = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            )
            ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                options.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(option) },
                        onClick = { onSelect(option); expanded = false }
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingsSlider(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    value: Int,
    range: IntRange,
    onValueChange: (Int) -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(22.dp))
                Spacer(modifier = Modifier.width(12.dp))
                Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                Text(
                    text = value.toString(),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Slider(
                value = value.toFloat(),
                onValueChange = { onValueChange(it.toInt()) },
                valueRange = range.first.toFloat()..range.last.toFloat(),
                steps = range.last - range.first - 1
            )
        }
    }
}

@Composable
private fun SettingsToggle(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp)
    ) {
        ListItem(
            headlineContent = { Text(label, style = MaterialTheme.typography.bodyMedium) },
            leadingContent = { Icon(icon, contentDescription = null, modifier = Modifier.size(22.dp)) },
            trailingContent = {
                Switch(checked = checked, onCheckedChange = onCheckedChange)
            },
            colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        )
    }
}
