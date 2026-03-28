package com.mediadrop.app.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import com.mediadrop.app.ui.theme.Gold500

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
            text     = "Settings",
            style    = MaterialTheme.typography.headlineSmall,
            color    = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.padding(bottom = 8.dp)
        )

        // ── Downloads ────────────────────────────────────────────────────────
        SettingsSectionHeader("Downloads")

        SettingsDropdown(
            label    = "Default Video Quality",
            icon     = Icons.Default.Hd,
            options  = listOf("144p","240p","360p","480p","720p","1080p","1440p","2160p"),
            selected = settings.defaultVideoQuality,
            onSelect = viewModel::setVideoQuality
        )

        SettingsDropdown(
            label    = "Default Audio Format",
            icon     = Icons.Default.AudioFile,
            options  = listOf("mp3","m4a","aac","opus","flac","wav"),
            selected = settings.defaultAudioFormat,
            onSelect = viewModel::setAudioFormat
        )

        SettingsSlider(
            label         = "Max Concurrent Downloads",
            icon          = Icons.Default.Download,
            value         = settings.maxConcurrentDownloads,
            range         = 1..5,
            onValueChange = viewModel::setMaxConcurrent
        )

        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

        // ── Storage ───────────────────────────────────────────────────────────
        SettingsSectionHeader("Save Location")

        // Save location segmented picker
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            SaveLocation.entries.forEach { loc ->
                val selected = settings.saveLocation == loc
                Card(
                    onClick  = { viewModel.setSaveLocation(loc) },
                    shape    = RoundedCornerShape(12.dp),
                    colors   = CardDefaults.cardColors(
                        containerColor = if (selected) Gold500.copy(alpha = 0.12f)
                                         else MaterialTheme.colorScheme.surfaceVariant
                    ),
                    border   = if (selected) BorderStroke(1.5.dp, Gold500.copy(alpha = 0.6f)) else null
                ) {
                    ListItem(
                        headlineContent   = {
                            Text(loc.displayName, style = MaterialTheme.typography.bodyMedium,
                                color = if (selected) Gold500 else MaterialTheme.colorScheme.onSurface)
                        },
                        supportingContent = {
                            Text(loc.hint, style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        },
                        leadingContent    = {
                            Icon(
                                imageVector        = Icons.Default.Folder,
                                contentDescription = null,
                                tint               = if (selected) Gold500 else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier           = Modifier.size(22.dp)
                            )
                        },
                        trailingContent   = {
                            if (selected) Icon(Icons.Default.CheckCircle, null, tint = Gold500, modifier = Modifier.size(20.dp))
                        },
                        colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent)
                    )
                }
            }
        }

        SettingsDropdown(
            label    = "Auto-clear History",
            icon     = Icons.Default.DeleteSweep,
            options  = listOf("Never","After 7 days","After 30 days"),
            selected = when (settings.autoClearDays) {
                7    -> "After 7 days"
                30   -> "After 30 days"
                else -> "Never"
            },
            onSelect = { label ->
                viewModel.setAutoClearDays(when (label) {
                    "After 7 days"  -> 7
                    "After 30 days" -> 30
                    else            -> 0
                })
            }
        )

        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

        // ── Appearance ────────────────────────────────────────────────────────
        SettingsSectionHeader("Appearance")

        // Theme toggle — 3 segmented buttons
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            ThemeMode.entries.forEach { mode ->
                val selected = settings.themeMode == mode
                val modeIcon = when (mode) {
                    ThemeMode.DARK   -> Icons.Default.DarkMode
                    ThemeMode.LIGHT  -> Icons.Default.LightMode
                    ThemeMode.SYSTEM -> Icons.Default.BrightnessMedium
                }
                Card(
                    onClick  = { viewModel.setThemeMode(mode) },
                    shape    = RoundedCornerShape(12.dp),
                    colors   = CardDefaults.cardColors(
                        containerColor = if (selected) Gold500.copy(alpha = 0.12f)
                                         else MaterialTheme.colorScheme.surfaceVariant
                    ),
                    border   = if (selected) BorderStroke(1.5.dp, Gold500.copy(alpha = 0.6f)) else null
                ) {
                    ListItem(
                        headlineContent = {
                            Text(mode.displayName, style = MaterialTheme.typography.bodyMedium,
                                color = if (selected) Gold500 else MaterialTheme.colorScheme.onSurface)
                        },
                        leadingContent  = {
                            Icon(modeIcon, null,
                                tint     = if (selected) Gold500 else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(22.dp))
                        },
                        trailingContent = {
                            if (selected) Icon(Icons.Default.CheckCircle, null, tint = Gold500, modifier = Modifier.size(20.dp))
                        },
                        colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent)
                    )
                }
            }
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

        // ── Notifications ────────────────────────────────────────────────────
        SettingsSectionHeader("Notifications")

        SettingsToggle(
            label           = "Download Notifications",
            icon            = Icons.Default.Notifications,
            checked         = settings.notificationsEnabled,
            onCheckedChange = viewModel::setNotifications
        )

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text     = "DC v1.0 • Made with ❤️ by SONU VERMA",
            style    = MaterialTheme.typography.labelSmall,
            color    = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.align(Alignment.CenterHorizontally)
        )
        Spacer(modifier = Modifier.height(80.dp))
    }
}

// ── Reusable components ───────────────────────────────────────────────────────

@Composable
private fun SettingsSectionHeader(title: String) {
    Text(
        text     = title,
        style    = MaterialTheme.typography.labelLarge,
        color    = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 4.dp, bottom = 2.dp)
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsDropdown(
    label   : String,
    icon    : androidx.compose.ui.graphics.vector.ImageVector,
    options : List<String>,
    selected: String,
    onSelect: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape  = RoundedCornerShape(12.dp)
    ) {
        ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = !expanded }) {
            ListItem(
                modifier          = Modifier.menuAnchor(),
                headlineContent   = { Text(label, style = MaterialTheme.typography.bodyMedium) },
                supportingContent = {
                    Text(selected, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary)
                },
                leadingContent    = { Icon(icon, null, modifier = Modifier.size(22.dp)) },
                trailingContent   = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                colors            = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            )
            ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                options.forEach { opt ->
                    DropdownMenuItem(
                        text    = { Text(opt) },
                        onClick = { onSelect(opt); expanded = false }
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingsSlider(
    label        : String,
    icon         : androidx.compose.ui.graphics.vector.ImageVector,
    value        : Int,
    range        : IntRange,
    onValueChange: (Int) -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape  = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null, modifier = Modifier.size(22.dp))
                Spacer(modifier = Modifier.width(12.dp))
                Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                Text(value.toString(), style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary)
            }
            Slider(
                value         = value.toFloat(),
                onValueChange = { onValueChange(it.toInt()) },
                valueRange    = range.first.toFloat()..range.last.toFloat(),
                steps         = range.last - range.first - 1,
                colors        = SliderDefaults.colors(thumbColor = Gold500, activeTrackColor = Gold500)
            )
        }
    }
}

@Composable
private fun SettingsToggle(
    label          : String,
    icon           : androidx.compose.ui.graphics.vector.ImageVector,
    checked        : Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape  = RoundedCornerShape(12.dp)
    ) {
        ListItem(
            headlineContent = { Text(label, style = MaterialTheme.typography.bodyMedium) },
            leadingContent  = { Icon(icon, null, modifier = Modifier.size(22.dp)) },
            trailingContent = {
                Switch(
                    checked         = checked,
                    onCheckedChange = onCheckedChange,
                    colors          = SwitchDefaults.colors(
                        checkedThumbColor  = MaterialTheme.colorScheme.onPrimary,
                        checkedTrackColor  = Gold500
                    )
                )
            },
            colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        )
    }
}
